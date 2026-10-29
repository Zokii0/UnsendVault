package com.riyadm.unsendvault.capture.engine

import com.riyadm.unsendvault.capture.CaptureSettingsCache
import com.riyadm.unsendvault.capture.Fixtures.T0
import com.riyadm.unsendvault.capture.Fixtures.msg
import com.riyadm.unsendvault.capture.Fixtures.snapshot
import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.adapter.AppLabelResolver
import com.riyadm.unsendvault.capture.adapter.KnownApps
import com.riyadm.unsendvault.capture.filter.SensitiveContentFilter
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.core.model.VaultSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coalescing, catch-up priority, removal ordering and the settings snapshot, on virtual time. */
class CoalescingEngineTest {
    private val own = "com.riyadm.unsendvault"

    private class Sink : CaptureSink {
        val events = mutableListOf<String>()
        val ingested = mutableListOf<ParsedNotification>()
        override suspend fun ingest(parsed: ParsedNotification, capturedAt: Long) {
            ingested += parsed
            events += "ingest:${parsed.notificationKey}:${parsed.messages.joinToString("|") { it.text }}"
        }

        override suspend fun recordRemoval(event: RemovalEvent) {
            events += "removed:${event.notificationKey}"
        }
    }

    private fun TestScope.engine(
        sink: CaptureSink,
        settings: suspend () -> VaultSettings = { VaultSettings() },
        window: Long = WINDOW,
        catchUpWindow: Long = CATCH_UP_WINDOW,
    ) = DefaultCaptureEngine(
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        settingsProvider = settings,
        sink = sink,
        registry = AdapterRegistry.createDefault(),
        labels = AppLabelResolver { null },
        sensitiveFilter = SensitiveContentFilter(),
        ownPackage = own,
        clock = { 42L },
        coalesceWindowMillis = window,
        catchUpWindowMillis = catchUpWindow,
        monotonicClock = { testScheduler.currentTime },
    )

    private fun chat(key: String, vararg texts: String, pkg: String = KnownApps.WHATSAPP): NotificationSnapshot =
        snapshot(
            packageName = pkg,
            key = key,
            title = "Alex Rivera",
            messages = texts.mapIndexed { i, t -> msg("Alex Rivera", t, T0 + i) },
        )

    @Test
    fun burstForOneKeyIsIngestedOnceWithTheLatestContent() = runTest {
        val sink = Sink()
        val e = engine(sink)
        // Ten updates for the same conversation, 40 ms apart (typing indicator / re-posts), all inside one window.
        val texts = mutableListOf<String>()
        repeat(10) { i ->
            texts += "m$i"
            e.onPosted(chat("k1", *texts.toTypedArray()))
            advanceTimeBy(40)
        }
        runCurrent()
        assertTrue("nothing processed before the window ends", sink.ingested.isEmpty())
        advanceTimeBy(WINDOW)
        runCurrent()
        assertEquals(1, sink.ingested.size)
        assertEquals((0 until 10).map { "m$it" }, sink.ingested.single().messages.map { it.text })
    }

    @Test
    fun differentKeysAreIngestedSeparately() = runTest {
        val sink = Sink()
        val e = engine(sink)
        e.onPosted(chat("k1", "a"))
        e.onPosted(chat("k2", "b"))
        e.onPosted(chat("k3", "c", pkg = KnownApps.TELEGRAM))
        advanceTimeBy(WINDOW + 1)
        runCurrent()
        assertEquals(listOf("k1", "k2", "k3"), sink.ingested.map { it.notificationKey })
    }

    @Test
    fun continuousUpdatesAreStillProcessedWithinOneWindowOfTheFirst() = runTest {
        val sink = Sink()
        val e = engine(sink)
        // An update every 100 ms for 2 s: latency stays bounded by the window from the first pending update.
        repeat(20) { i ->
            e.onPosted(chat("k1", *(0..i).map { "m$it" }.toTypedArray()))
            advanceTimeBy(100)
            runCurrent()
        }
        val ingests = sink.ingested.size
        assertTrue("expected ~4 ingests (one per window), got $ingests", ingests in 3..5)
        advanceTimeBy(WINDOW + 1)
        runCurrent()
        assertEquals(20, sink.ingested.last().messages.size)
    }

    @Test
    fun identicalContentAfterTheWindowIsNotWrittenAgain() = runTest {
        val sink = Sink()
        val e = engine(sink)
        e.onPosted(chat("k1", "a"))
        advanceTimeBy(WINDOW + 1)
        runCurrent()
        e.onPosted(chat("k1", "a"))
        advanceTimeBy(WINDOW + 1)
        runCurrent()
        assertEquals(1, sink.ingested.size)
    }

    @Test
    fun removalFlushesThePendingPostFirst() = runTest {
        val sink = Sink()
        val e = engine(sink)
        e.onPosted(chat("k1", "a"))
        e.onPosted(chat("other", "x", pkg = KnownApps.TELEGRAM))
        e.onPosted(chat("k2", "b"))
        advanceTimeBy(10)
        e.onRemoved(RemovalEvent(KnownApps.WHATSAPP, "k2", RemovalReason.USER_DISMISSED, 99L))
        runCurrent()
        // Same-package posts that arrived earlier go first, then the removed key's post, then the removal;
        // the unrelated package keeps waiting for its window.
        assertEquals(listOf("ingest:k1:a", "ingest:k2:b", "removed:k2"), sink.events)
        advanceTimeBy(WINDOW)
        runCurrent()
        assertEquals("ingest:other:x", sink.events.last())
    }

    @Test
    fun catchUpItemsYieldToLiveNotifications() = runTest {
        val sink = Sink()
        val e = engine(sink)
        e.onCatchUp(chat("old", "from the shade"))
        advanceTimeBy(100)
        e.onPosted(chat("live", "just now", pkg = KnownApps.TELEGRAM))
        advanceTimeBy(CATCH_UP_WINDOW + 1)
        runCurrent()
        assertEquals(listOf("live", "old"), sink.ingested.map { it.notificationKey })
    }

    @Test
    fun liveUpdateOfACatchUpItemIsNotDelayedByTheCatchUpLane() = runTest {
        val sink = Sink()
        val e = engine(sink)
        e.onCatchUp(chat("k1", "a"))
        advanceTimeBy(100)
        e.onPosted(chat("k1", "a", "b"))
        advanceTimeBy(WINDOW + 1)
        runCurrent()
        assertEquals("processed by the live window (t=600), not the catch-up one (t=2000)", 1, sink.ingested.size)
        assertEquals(listOf("a", "b"), sink.ingested.single().messages.map { it.text })
    }

    @Test
    fun trackedKeysCoverQueuedAndIngestedNotifications() = runTest {
        val sink = Sink()
        val e = engine(sink)
        assertFalse(e.isTracked("k1"))
        e.onPosted(chat("k1", "a"))
        assertTrue("queued", e.isTracked("k1"))
        advanceTimeBy(WINDOW + 1)
        runCurrent()
        assertTrue("ingested", e.isTracked("k1"))
        e.onRemoved(RemovalEvent(KnownApps.WHATSAPP, "k1", RemovalReason.APP_CANCELLED, 1L))
        runCurrent()
        assertFalse(e.isTracked("k1"))
    }

    @Test
    fun excludedPackageIsNeverCapturedEvenBeforeSettingsLoad() = runTest {
        val sink = Sink()
        val source = MutableSharedFlow<VaultSettings>(replay = 1)
        val cache = CaptureSettingsCache()
        cache.attach(source, backgroundScope)
        runCurrent()
        assertNull("cold start: nothing read yet", cache.current)
        val e = engine(sink, settings = cache::get, window = 0)

        // The listener's pre-filter passes this on (settings unknown); the consumer must wait for the real settings.
        e.onPosted(chat("k1", "secret"))
        runCurrent()
        assertTrue(sink.ingested.isEmpty())

        source.emit(VaultSettings(excludedPackages = setOf(KnownApps.WHATSAPP)))
        runCurrent()
        assertTrue("excluded package must not be stored", sink.ingested.isEmpty())
        assertEquals(setOf(KnownApps.WHATSAPP), cache.current!!.excludedPackages)
    }

    @Test
    fun settingsSnapshotFollowsChanges() = runTest {
        val sink = Sink()
        val source = MutableSharedFlow<VaultSettings>(replay = 1)
        source.emit(VaultSettings())
        var reads = 0
        val cache = CaptureSettingsCache()
        cache.attach(source, backgroundScope)
        runCurrent()
        val e = engine(sink, settings = { reads++; cache.get() }, window = 0)

        e.onPosted(chat("k1", "a"))
        runCurrent()
        assertEquals(1, sink.ingested.size)

        source.emit(VaultSettings(captureEnabled = false))
        runCurrent()
        assertEquals(false, cache.current!!.captureEnabled)
        e.onPosted(chat("k2", "b"))
        runCurrent()
        assertEquals("paused: dropped", 1, sink.ingested.size)

        source.emit(VaultSettings(captureEnabled = true))
        runCurrent()
        e.onPosted(chat("k3", "c"))
        runCurrent()
        assertEquals(2, sink.ingested.size)
        assertEquals(3, reads)
    }

    @Test
    fun pauseCallbackFiresOnlyOnCaptureEnabledChanges() = runTest {
        val source = MutableSharedFlow<VaultSettings>(replay = 1)
        val calls = mutableListOf<Boolean>()
        val cache = CaptureSettingsCache(onCaptureEnabledChanged = { calls += it })
        cache.attach(source, backgroundScope)
        source.emit(VaultSettings())
        runCurrent()
        source.emit(VaultSettings(filterOtp = false))
        runCurrent()
        source.emit(VaultSettings(captureEnabled = false))
        runCurrent()
        source.emit(VaultSettings(captureEnabled = true))
        runCurrent()
        assertEquals(listOf(true, false, true), calls)
    }

    @Test
    fun queueStaysBoundedUnderABurstOfDistinctKeys() = runTest {
        val sink = Sink()
        val e = DefaultCaptureEngine(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            settingsProvider = { VaultSettings() },
            sink = sink,
            registry = AdapterRegistry.createDefault(),
            labels = AppLabelResolver { null },
            sensitiveFilter = SensitiveContentFilter(),
            ownPackage = own,
            capacity = 8,
            coalesceWindowMillis = WINDOW,
            monotonicClock = { testScheduler.currentTime },
        )
        repeat(50) { i -> e.onPosted(chat("k$i", "m$i")) }
        advanceTimeBy(WINDOW + 1)
        runCurrent()
        assertEquals((42 until 50).map { "k$it" }, sink.ingested.map { it.notificationKey })
    }

    private companion object {
        const val WINDOW = 500L
        const val CATCH_UP_WINDOW = 2_000L
    }
}
