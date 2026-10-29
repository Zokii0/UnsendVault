package com.riyadm.unsendvault.update

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State holder behind the main-screen dialogs and Settings > Updates. The controller runs in backgroundScope (its state
 * flow never completes), so tests drive it with runCurrent(): advanceUntilIdle() does not run background-only work.
 */
class UpdateControllerTest {
    private val now = 1_800_000_000_000L

    private fun kotlinx.coroutines.test.TestScope.controller(
        store: InMemoryUpdateStore,
        source: FakeReleaseSource,
        debug: Boolean = false,
    ) = UpdateController(
        checker = UpdateChecker(source, store, if (debug) "1.0.15-debug" else "1.0.15", isDebugBuild = debug),
        store = store,
        scope = backgroundScope,
        isDebugBuild = debug,
        clock = { now },
    )

    @Test
    fun consentDialog_onlyWhileUnasked() = runTest {
        val store = InMemoryUpdateStore()
        val c = controller(store, FakeReleaseSource(FetchResult.NoRelease))
        runCurrent()
        assertTrue(c.state.value.showConsentDialog)
        c.declineAutomaticChecks()
        runCurrent()
        assertFalse(c.state.value.showConsentDialog)
        assertEquals(UpdateConsent.DECLINED, store.state.value.consent)
        assertFalse(c.state.value.autoCheckEnabled)
    }

    @Test
    fun unasked_mainUiVisible_doesNotContactGitHub() = runTest {
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val c = controller(InMemoryUpdateStore(), source)
        c.onMainUiVisible()
        runCurrent()
        assertEquals(0, source.calls)
        assertNull(c.state.value.prompt)
    }

    @Test
    fun allow_persistsConsent_andRunsTheDueCheck() = runTest {
        val store = InMemoryUpdateStore()
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val c = controller(store, source)
        c.onMainUiVisible()
        c.allowAutomaticChecks()
        runCurrent()
        assertEquals(UpdateConsent.ALLOWED, store.state.value.consent)
        assertEquals(1, source.calls)
        assertEquals(release("1.0.16"), c.state.value.prompt)
        assertEquals(now, c.state.value.lastCheckAt)
    }

    @Test
    fun automaticCheck_runsOncePerProcess() = runTest {
        val source = FakeReleaseSource(FetchResult.Failed(FailureReason.OFFLINE))
        val store = InMemoryUpdateStore(UpdatePrefs(consent = UpdateConsent.ALLOWED))
        val c = controller(store, source)
        repeat(3) { c.onMainUiVisible() }
        runCurrent()
        assertEquals(1, source.calls)
        assertNull(c.state.value.prompt)
    }

    @Test
    fun automaticCheck_skippedInDebugBuilds_butConsentDialogStillWorks() = runTest {
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val store = InMemoryUpdateStore()
        val c = controller(store, source, debug = true)
        runCurrent()
        assertTrue(c.state.value.showConsentDialog)
        assertTrue(c.state.value.autoCheckDisabledInBuild)
        c.onMainUiVisible()
        c.allowAutomaticChecks()
        runCurrent()
        assertEquals(0, source.calls)
        c.checkNow()
        runCurrent()
        assertEquals(1, source.calls)
        assertEquals(release("1.0.16"), c.state.value.prompt)
    }

    @Test
    fun later_hidesForThisProcess() = runTest {
        val store = InMemoryUpdateStore(UpdatePrefs(consent = UpdateConsent.ALLOWED))
        val c = controller(store, FakeReleaseSource(FetchResult.Found(release("1.0.16"))))
        c.onMainUiVisible()
        runCurrent()
        assertEquals("1.0.16", c.state.value.prompt?.version)
        c.dismissForNow()
        // Re-enabling the toggle re-evaluates the (cached) result but must not re-show a dismissed version.
        c.setAutomaticChecks(true)
        runCurrent()
        assertNull(c.state.value.prompt)
        assertNull(store.state.value.skippedVersion)
    }

    @Test
    fun skip_persistsTheVersion() = runTest {
        val store = InMemoryUpdateStore(UpdatePrefs(consent = UpdateConsent.ALLOWED))
        val c = controller(store, FakeReleaseSource(FetchResult.Found(release("1.0.16"))))
        c.onMainUiVisible()
        runCurrent()
        c.skipVersion()
        runCurrent()
        assertNull(c.state.value.prompt)
        assertEquals("1.0.16", store.state.value.skippedVersion)
    }

    @Test
    fun checkNow_upToDate() = runTest {
        val store = InMemoryUpdateStore(UpdatePrefs(consent = UpdateConsent.DECLINED))
        val c = controller(store, FakeReleaseSource(FetchResult.Found(release("1.0.15"))))
        c.checkNow()
        runCurrent()
        assertEquals(ManualCheckState.UpToDate("1.0.15"), c.state.value.manual)
        assertNull(c.state.value.prompt)
        assertEquals(now, c.state.value.lastCheckAt)
        assertEquals(UpdateConsent.DECLINED, c.state.value.consent)
    }

    @Test
    fun checkNow_failuresMapToInlineResults() = runTest {
        for (reason in FailureReason.entries) {
            val c = controller(InMemoryUpdateStore(), FakeReleaseSource(FetchResult.Failed(reason)))
            c.checkNow()
            runCurrent()
            assertEquals(ManualCheckState.Failed(reason), c.state.value.manual)
        }
    }

    @Test
    fun checkNow_showsSkippedVersionsToo_andCanReopenTheDialog() = runTest {
        val store = InMemoryUpdateStore(UpdatePrefs(skippedVersion = "1.0.16"))
        val c = controller(store, FakeReleaseSource(FetchResult.Found(release("1.0.16"))))
        c.checkNow()
        runCurrent()
        assertEquals(ManualCheckState.Available(release("1.0.16")), c.state.value.manual)
        assertEquals(release("1.0.16"), c.state.value.prompt)
        c.dismissForNow()
        runCurrent()
        assertNull(c.state.value.prompt)
        c.showAvailableUpdate()
        runCurrent()
        assertEquals(release("1.0.16"), c.state.value.prompt)
    }

    @Test
    fun checkNow_showsCheckingWhileTheRequestIsInFlight() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<FetchResult>()
        val store = InMemoryUpdateStore()
        val c = UpdateController(UpdateChecker({ gate.await() }, store, "1.0.15", false), store, backgroundScope, false) { now }
        c.checkNow()
        runCurrent()
        assertEquals(ManualCheckState.Checking, c.state.value.manual)
        gate.complete(FetchResult.Failed(FailureReason.OFFLINE))
        runCurrent()
        assertEquals(ManualCheckState.Failed(FailureReason.OFFLINE), c.state.value.manual)
    }

    @Test
    fun checkNow_ignoresDoubleTaps() = runTest {
        val source = FakeReleaseSource(FetchResult.NoRelease)
        val c = controller(InMemoryUpdateStore(), source)
        c.checkNow()
        c.checkNow()
        runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun toggle_reflectsConsent() = runTest {
        val store = InMemoryUpdateStore(UpdatePrefs(consent = UpdateConsent.DECLINED, lastCheckAt = now - HOUR))
        val source = FakeReleaseSource(FetchResult.NoRelease)
        val c = controller(store, source)
        c.setAutomaticChecks(true)
        runCurrent()
        assertTrue(c.state.value.autoCheckEnabled)
        assertEquals(0, source.calls) // checked an hour ago: not due yet
        c.setAutomaticChecks(false)
        runCurrent()
        assertFalse(c.state.value.autoCheckEnabled)
        assertEquals(UpdateConsent.DECLINED, store.state.value.consent)
    }
}
