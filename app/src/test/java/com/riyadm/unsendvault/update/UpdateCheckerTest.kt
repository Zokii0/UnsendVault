package com.riyadm.unsendvault.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val t0 = 1_800_000_000_000L
    private val allowed = UpdatePrefs(consent = UpdateConsent.ALLOWED)

    private fun checker(source: ReleaseSource, store: UpdateStore, current: String = "1.0.15", debug: Boolean = false) =
        UpdateChecker(source, store, current, isDebugBuild = debug)

    @Test
    fun autoCheck_requiresConsent() = runBlocking {
        for (consent in listOf(UpdateConsent.UNASKED, UpdateConsent.DECLINED)) {
            val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
            val store = InMemoryUpdateStore(UpdatePrefs(consent = consent))
            assertEquals(CheckResult.NotRun(NotRunReason.NO_CONSENT), checker(source, store).checkIfDue(t0))
            assertEquals(0, source.calls)
            assertNull(store.state.value.lastCheckAt)
        }
    }

    @Test
    fun autoCheck_neverRunsInDebugBuilds() = runBlocking {
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val result = checker(source, InMemoryUpdateStore(allowed), current = "1.0.15-debug", debug = true).checkIfDue(t0)
        assertEquals(CheckResult.NotRun(NotRunReason.DEBUG_BUILD), result)
        assertEquals(0, source.calls)
    }

    @Test
    fun autoCheck_findsNewerVersion_andRecordsTheAttempt() = runBlocking {
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val store = InMemoryUpdateStore(allowed)
        assertEquals(CheckResult.UpdateAvailable(release("1.0.16")), checker(source, store).checkIfDue(t0))
        assertEquals(t0, store.state.value.lastCheckAt)
        assertFalse(store.state.value.lastCheckFailed)
        assertEquals(release("1.0.16"), store.state.value.lastKnownLatest)
    }

    @Test
    fun sameOrOlderVersionIsUpToDate() = runBlocking {
        val store = InMemoryUpdateStore(allowed)
        assertEquals(
            CheckResult.UpToDate("1.0.15", "1.0.15"),
            checker(FakeReleaseSource(FetchResult.Found(release("1.0.15"))), store).checkIfDue(t0),
        )
        val older = InMemoryUpdateStore(allowed)
        assertEquals(
            CheckResult.UpToDate("1.0.15", "1.0.9"),
            checker(FakeReleaseSource(FetchResult.Found(release("1.0.9"))), older).checkIfDue(t0),
        )
    }

    @Test
    fun noRelease_isUpToDate_andClearsCache() = runBlocking {
        val store = InMemoryUpdateStore(allowed.copy(lastKnownLatest = release("1.0.16")))
        assertEquals(CheckResult.UpToDate("1.0.15", null), checker(FakeReleaseSource(FetchResult.NoRelease), store).checkIfDue(t0))
        assertNull(store.state.value.lastKnownLatest)
    }

    @Test
    fun autoCheck_atMostOncePer24Hours_usingTheCachedRelease() = runBlocking {
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val store = InMemoryUpdateStore(allowed)
        val checker = checker(source, store)
        checker.checkIfDue(t0)
        // Within the window: no request, cached release still offered (e.g. after an app restart).
        assertEquals(CheckResult.UpdateAvailable(release("1.0.16")), checker.checkIfDue(t0 + 23 * HOUR))
        assertEquals(1, source.calls)
        assertEquals(t0, store.state.value.lastCheckAt)
        // Window elapsed: fetch again.
        source.enqueue(FetchResult.Found(release("1.0.17")))
        checker.checkIfDue(t0 + 24 * HOUR)
        assertEquals(2, source.calls)
        assertEquals(t0 + 24 * HOUR, store.state.value.lastCheckAt)
    }

    @Test
    fun notDue_withoutCache() = runBlocking {
        val source = FakeReleaseSource(FetchResult.NoRelease)
        val store = InMemoryUpdateStore(allowed.copy(lastCheckAt = t0))
        assertEquals(CheckResult.NotRun(NotRunReason.NOT_DUE), checker(source, store).checkIfDue(t0 + HOUR))
        assertEquals(0, source.calls)
    }

    @Test
    fun failure_isRecorded_andRetriedOnlyAfterTheShorterWindow() = runBlocking {
        val source = FakeReleaseSource(FetchResult.Failed(FailureReason.OFFLINE))
        val store = InMemoryUpdateStore(allowed)
        val checker = checker(source, store)
        assertEquals(CheckResult.Failed(FailureReason.OFFLINE), checker.checkIfDue(t0))
        assertEquals(t0, store.state.value.lastCheckAt)
        assertTrue(store.state.value.lastCheckFailed)

        assertEquals(CheckResult.NotRun(NotRunReason.NOT_DUE), checker.checkIfDue(t0 + 5 * HOUR))
        assertEquals(1, source.calls)

        source.enqueue(FetchResult.Found(release("1.0.16")))
        assertEquals(CheckResult.UpdateAvailable(release("1.0.16")), checker.checkIfDue(t0 + 6 * HOUR))
        assertEquals(2, source.calls)
        assertFalse(store.state.value.lastCheckFailed)
        // Back on the normal daily window after a success.
        assertEquals(CheckResult.UpdateAvailable(release("1.0.16")), checker.checkIfDue(t0 + 12 * HOUR))
        assertEquals(2, source.calls)
    }

    @Test
    fun failure_keepsThePreviousCacheButDoesNotOfferItUntilRetry() = runBlocking {
        val store = InMemoryUpdateStore(allowed.copy(lastKnownLatest = release("1.0.16"), lastCheckAt = t0 - 25 * HOUR))
        val source = FakeReleaseSource(FetchResult.Failed(FailureReason.RATE_LIMITED))
        val checker = checker(source, store)
        assertEquals(CheckResult.Failed(FailureReason.RATE_LIMITED), checker.checkIfDue(t0))
        assertEquals(release("1.0.16"), store.state.value.lastKnownLatest)
        assertEquals(CheckResult.NotRun(NotRunReason.NOT_DUE), checker.checkIfDue(t0 + HOUR))
    }

    @Test
    fun clockMovedBackwards_isTreatedAsDue() = runBlocking {
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val store = InMemoryUpdateStore(allowed.copy(lastCheckAt = t0 + 1000 * HOUR))
        checker(source, store).checkIfDue(t0)
        assertEquals(1, source.calls)
        assertEquals(t0, store.state.value.lastCheckAt)
    }

    @Test
    fun skippedVersion_isNotOfferedAutomatically_butANewerOneIs() = runBlocking {
        val store = InMemoryUpdateStore(allowed.copy(skippedVersion = "1.0.16"))
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val checker = checker(source, store)
        assertEquals(CheckResult.SkippedByUser(release("1.0.16")), checker.checkIfDue(t0))
        source.enqueue(FetchResult.Found(release("1.0.17")))
        assertEquals(CheckResult.UpdateAvailable(release("1.0.17")), checker.checkIfDue(t0 + 24 * HOUR))
    }

    @Test
    fun checkNow_ignoresConsentWindowSkipAndDebug() = runBlocking {
        val store = InMemoryUpdateStore(
            UpdatePrefs(consent = UpdateConsent.DECLINED, lastCheckAt = t0, skippedVersion = "1.0.16"),
        )
        val source = FakeReleaseSource(FetchResult.Found(release("1.0.16")))
        val checker = checker(source, store, current = "1.0.15-debug", debug = true)
        assertEquals(CheckResult.UpdateAvailable(release("1.0.16")), checker.checkNow(t0 + 1))
        assertEquals(1, source.calls)
        assertEquals(t0 + 1, store.state.value.lastCheckAt)
        assertEquals(UpdateConsent.DECLINED, store.state.value.consent) // manual check never changes consent
    }

    @Test
    fun checkNow_reportsFailures() = runBlocking {
        val store = InMemoryUpdateStore()
        val checker = checker(FakeReleaseSource(FetchResult.Failed(FailureReason.RATE_LIMITED, 403)), store)
        assertEquals(CheckResult.Failed(FailureReason.RATE_LIMITED), checker.checkNow(t0))
        assertTrue(store.state.value.lastCheckFailed)
    }

    @Test
    fun debugSuffixOnCurrentVersionIsIgnored() = runBlocking {
        val store = InMemoryUpdateStore()
        val result = checker(FakeReleaseSource(FetchResult.Found(release("1.0.15"))), store, current = "1.0.15-debug").checkNow(t0)
        assertEquals(CheckResult.UpToDate("1.0.15-debug", "1.0.15"), result)
    }
}
