package com.riyadm.unsendvault.ui

import app.cash.turbine.test
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.security.lock.AppLockManager
import com.riyadm.unsendvault.security.lock.AppLockStateMachine
import com.riyadm.unsendvault.security.lock.LockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lock state machine tests driven by a fake clock (no Android, no real time). */
class AppLockStateMachineTest {
    private var now = 1_000_000L
    private val machine = AppLockStateMachine(clock = { now }, externalGraceMillis = 120_000)

    private fun advance(seconds: Int) {
        now += seconds * 1000L
    }

    @Test
    fun initializingUntilFirstSettings() {
        assertEquals(LockState.Initializing, machine.state)
        // Lifecycle events before settings must not unlock.
        machine.onBackground()
        assertEquals(LockState.Initializing, machine.onForeground())
    }

    @Test
    fun coldStartLockedWhenEnabled() {
        assertEquals(LockState.Locked, machine.onSettings(lockEnabled = true, timeoutSeconds = 60))
    }

    @Test
    fun coldStartUnlockedWhenDisabled() {
        assertEquals(LockState.Unlocked, machine.onSettings(lockEnabled = false, timeoutSeconds = 60))
    }

    @Test
    fun authenticationUnlocks() {
        machine.onSettings(true, 60)
        assertEquals(LockState.Unlocked, machine.onAuthenticated())
    }

    @Test
    fun shortBackgroundTripStaysUnlocked() {
        machine.onSettings(true, 60)
        machine.onAuthenticated()
        machine.onBackground()
        advance(59)
        assertEquals(LockState.Unlocked, machine.onForeground())
    }

    @Test
    fun backgroundLongerThanTimeoutRelocks() {
        machine.onSettings(true, 60)
        machine.onAuthenticated()
        machine.onBackground()
        advance(60)
        assertEquals(LockState.Locked, machine.onForeground())
    }

    @Test
    fun zeroTimeoutLocksAsSoonAsBackgrounded() {
        machine.onSettings(true, 0)
        machine.onAuthenticated()
        assertEquals(LockState.Locked, machine.onBackground())
        assertEquals(LockState.Locked, machine.onForeground())
    }

    @Test
    fun externalActivityGetsGracePeriod() {
        machine.onSettings(true, 0)
        machine.onAuthenticated()
        machine.expectExternalActivity()
        assertEquals(LockState.Unlocked, machine.onBackground())
        advance(90)
        assertEquals(LockState.Unlocked, machine.onForeground())
    }

    @Test
    fun externalGraceExpires() {
        machine.onSettings(true, 30)
        machine.onAuthenticated()
        machine.expectExternalActivity()
        machine.onBackground()
        advance(121)
        assertEquals(LockState.Locked, machine.onForeground())
    }

    @Test
    fun externalGraceIsSingleUse() {
        machine.onSettings(true, 30)
        machine.onAuthenticated()
        machine.expectExternalActivity()
        machine.onBackground()
        advance(10)
        assertEquals(LockState.Unlocked, machine.onForeground())
        machine.onBackground()
        advance(31)
        assertEquals(LockState.Locked, machine.onForeground())
    }

    @Test
    fun enablingWhileInAppDoesNotLockImmediately() {
        machine.onSettings(false, 30)
        assertEquals(LockState.Unlocked, machine.onSettings(true, 30))
        machine.onBackground()
        advance(31)
        assertEquals(LockState.Locked, machine.onForeground())
    }

    @Test
    fun disablingUnlocks() {
        machine.onSettings(true, 30)
        assertEquals(LockState.Unlocked, machine.onSettings(false, 30))
    }

    @Test
    fun nonMonotonicClockFailsClosed() {
        machine.onSettings(true, 600)
        machine.onAuthenticated()
        machine.onBackground()
        now -= 5_000
        assertEquals(LockState.Locked, machine.onForeground())
    }

    @Test
    fun manualLockOnlyWhenEnabled() {
        machine.onSettings(false, 0)
        assertEquals(LockState.Unlocked, machine.lock())
        machine.onSettings(true, 0)
        assertEquals(LockState.Locked, machine.lock())
    }

    @Test
    fun foregroundWithoutBackgroundIsNoOp() {
        machine.onSettings(true, 0)
        machine.onAuthenticated()
        assertEquals(LockState.Unlocked, machine.onForeground())
    }

    @Test
    fun managerPublishesStateFromSettingsAndLifecycle() = runTest(UnconfinedTestDispatcher()) {
        val settings = MutableStateFlow(VaultSettings(appLockEnabled = true, lockTimeoutSeconds = 0))
        var clock = 0L
        val manager = AppLockManager(settings, backgroundScope, clock = { clock })
        manager.state.test {
            assertEquals(LockState.Locked, awaitItem())
            manager.onAuthenticated()
            assertEquals(LockState.Unlocked, awaitItem())
            manager.onBackground()
            assertEquals(LockState.Locked, awaitItem())
            manager.onAuthenticated()
            assertEquals(LockState.Unlocked, awaitItem())
            settings.update { it.copy(appLockEnabled = false) }
            clock += 10_000
            manager.onBackground()
            manager.onForeground()
            expectNoEvents()
        }
    }
}
