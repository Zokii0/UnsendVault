package com.riyadm.unsendvault.capture

import com.riyadm.unsendvault.capture.CatchUpPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerPoliciesTest {

    // --- Catch-up throttling ------------------------------------------------------------------------------------------

    @Test
    fun firstCatchUpInAProcessIsFull() {
        val p = CatchUpPolicy(minIntervalMillis = 120_000, slackMillis = 10_000)
        assertEquals(Decision.Run(CatchUpPolicy.FULL), p.decide(elapsedNow = 5_000))
    }

    @Test
    fun rebindStormIsDeferredThenIncremental() {
        val p = CatchUpPolicy(minIntervalMillis = 120_000, slackMillis = 10_000)
        p.decide(1_000)
        p.onRan(1_000)
        // OEM unbinds and rebinds every few seconds: no new binder walk inside the interval, only a deferral.
        p.onDisconnected(wallNow = 1_700_000_000_000)
        assertEquals(Decision.Defer(115_000), p.decide(6_000))
        p.onDisconnected(wallNow = 1_700_000_005_000)
        assertEquals(Decision.Defer(110_000), p.decide(11_000))
        // When the deferred pass runs, it only looks at what was posted since the last disconnect (minus slack).
        assertEquals(Decision.Run(1_700_000_005_000 - 10_000), p.decide(121_000))
        p.onRan(121_000)
        assertTrue(p.decide(122_000) is Decision.Defer)
    }

    @Test
    fun catchUpAfterIntervalWithoutKnownDisconnectIsFull() {
        val p = CatchUpPolicy(minIntervalMillis = 1_000, slackMillis = 0)
        p.onRan(0)
        assertEquals(Decision.Run(CatchUpPolicy.FULL), p.decide(5_000))
    }

    // --- Rebind backoff -----------------------------------------------------------------------------------------------

    @Test
    fun rebindBacksOffWhenDisconnectsRepeat() {
        val p = RebindPolicy(backoffMillis = longArrayOf(0, 10_000, 60_000, 300_000), stableMillis = 600_000)
        var now = 0L
        val delays = (1..6).map {
            p.onConnected(now)
            now += 2_000 // connection lasts 2 s, then the system drops it again
            p.onDisconnected(now)
        }
        assertEquals(listOf(0L, 10_000L, 60_000L, 300_000L, 300_000L, 300_000L), delays)
    }

    @Test
    fun stableConnectionResetsTheBackoff() {
        val p = RebindPolicy(backoffMillis = longArrayOf(0, 10_000, 60_000), stableMillis = 600_000)
        p.onConnected(0)
        assertEquals(0L, p.onDisconnected(1_000))
        p.onConnected(2_000)
        assertEquals(10_000L, p.onDisconnected(3_000))
        p.onConnected(10_000)
        assertEquals("connected for 10 min: fresh start", 0L, p.onDisconnected(10_000 + 600_000))
    }

    // --- Pause = unbind / resume = rebind -----------------------------------------------------------------------------

    private class FakeControl : ListenerControl {
        var granted = true
        var connected = false
        var hasBoundInstance = true
        val calls = mutableListOf<String>()
        override fun isAccessGranted() = granted
        override fun isConnected() = connected
        override fun requestUnbind(): Boolean {
            calls += "unbind"
            return hasBoundInstance
        }

        override fun requestRebind() {
            calls += "rebind"
        }
    }

    @Test
    fun pausingWhileConnectedUnbindsAndTheDisconnectIsNotRebound() {
        val control = FakeControl().apply { connected = true }
        val c = CapturePauseController(control, unbindWhilePaused = true)
        c.onCaptureEnabledChanged(true)
        assertTrue(control.calls.isEmpty())
        c.onCaptureEnabledChanged(false)
        assertEquals(listOf("unbind"), control.calls)
        control.connected = false
        assertFalse("self-requested unbind must not trigger the auto-rebind", c.onListenerDisconnected())
    }

    @Test
    fun resumingWhileUnboundRebinds() {
        val control = FakeControl().apply { connected = true }
        val c = CapturePauseController(control, unbindWhilePaused = true)
        c.onCaptureEnabledChanged(false)
        control.connected = false
        c.onListenerDisconnected()
        c.onCaptureEnabledChanged(true) // e.g. the widget's "Resume" or the app's toggle
        assertEquals(listOf("unbind", "rebind"), control.calls)
        control.connected = true
        c.onListenerConnected()
        assertEquals("connected while enabled: stays bound", 2, control.calls.size)
        control.connected = false
        assertTrue("unexpected disconnect while enabled may rebind", c.onListenerDisconnected())
    }

    @Test
    fun reconnectWhilePausedUnbindsAgainWithoutLooping() {
        val control = FakeControl()
        val c = CapturePauseController(control, unbindWhilePaused = true)
        c.onCaptureEnabledChanged(false) // not connected yet (e.g. right after boot): nothing to do
        assertTrue(control.calls.isEmpty())
        control.connected = true
        c.onListenerConnected() // the system bound us after a reboot
        assertEquals(listOf("unbind"), control.calls)
        control.connected = false
        assertFalse(c.onListenerDisconnected())
        assertEquals(listOf("unbind"), control.calls)
    }

    @Test
    fun pausedListenerIsNotRebound() {
        val control = FakeControl()
        val c = CapturePauseController(control, unbindWhilePaused = false)
        c.onCaptureEnabledChanged(false)
        // Kill switch off: stays bound while paused (callbacks dropped by the pre-filter), but never auto-rebinds.
        control.connected = true
        c.onListenerConnected()
        assertTrue(control.calls.isEmpty())
        assertFalse(c.onListenerDisconnected())
    }

    @Test
    fun enabledButNotConnectedHealsWithARebind() {
        val control = FakeControl()
        val c = CapturePauseController(control, unbindWhilePaused = true)
        assertNull(c.knownCaptureEnabled)
        c.onCaptureEnabledChanged(true)
        assertEquals(listOf("rebind"), control.calls)
        control.granted = false
        c.onCaptureEnabledChanged(true)
        assertEquals("no access: no rebind", listOf("rebind"), control.calls)
    }

    @Test
    fun failedUnbindDoesNotSuppressRebind() {
        val control = FakeControl().apply { connected = true; hasBoundInstance = false }
        val c = CapturePauseController(control, unbindWhilePaused = true)
        c.onCaptureEnabledChanged(false)
        c.onCaptureEnabledChanged(true)
        assertTrue(c.onListenerDisconnected())
    }
}
