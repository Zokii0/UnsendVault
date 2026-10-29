package com.riyadm.unsendvault.update

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides whether to contact GitHub and whether a fetched release is an update.
 *
 * - [checkIfDue] is the automatic check: it runs only when the user ALLOWED it, never in debug builds, and at most once
 *   per [CHECK_INTERVAL_MILLIS] ([RETRY_AFTER_FAILURE_MILLIS] after a failed attempt). Within the window the cached
 *   [UpdatePrefs.lastKnownLatest] is reused instead of fetching again. A skipped version is not offered.
 * - [checkNow] is the explicit "Check now" button: it always fetches, ignores the window and the consent (pressing the
 *   button is the consent for that one request) and the skipped version (the user asked), and works in debug builds.
 *
 * Every attempt records [UpdatePrefs.lastCheckAt], including failures, so a flaky network cannot cause repeated
 * requests on every app start.
 */
class UpdateChecker(
    private val source: ReleaseSource,
    private val store: UpdateStore,
    val currentVersion: String,
    private val isDebugBuild: Boolean,
) {
    private val mutex = Mutex()

    suspend fun checkIfDue(now: Long): CheckResult = mutex.withLock {
        val prefs = store.current()
        if (prefs.consent != UpdateConsent.ALLOWED) return CheckResult.NotRun(NotRunReason.NO_CONSENT)
        if (isDebugBuild) return CheckResult.NotRun(NotRunReason.DEBUG_BUILD)
        if (!isDue(prefs, now)) {
            val cached = prefs.lastKnownLatest ?: return CheckResult.NotRun(NotRunReason.NOT_DUE)
            if (prefs.lastCheckFailed) return CheckResult.NotRun(NotRunReason.NOT_DUE)
            return evaluate(cached, prefs.skippedVersion, honorSkip = true)
        }
        fetchAndRecord(now, honorSkip = true)
    }

    suspend fun checkNow(now: Long): CheckResult = mutex.withLock { fetchAndRecord(now, honorSkip = false) }

    private fun isDue(prefs: UpdatePrefs, now: Long): Boolean {
        val last = prefs.lastCheckAt ?: return true
        if (now < last) return true // clock moved backwards; don't stay blocked until it catches up
        val window = if (prefs.lastCheckFailed) RETRY_AFTER_FAILURE_MILLIS else CHECK_INTERVAL_MILLIS
        return now - last >= window
    }

    private suspend fun fetchAndRecord(now: Long, honorSkip: Boolean): CheckResult {
        val result = source.fetchLatest()
        val skipped = store.current().skippedVersion
        return when (result) {
            is FetchResult.Found -> {
                store.edit { it.copy(lastCheckAt = now, lastCheckFailed = false, lastKnownLatest = result.release) }
                evaluate(result.release, skipped, honorSkip)
            }
            FetchResult.NoRelease -> {
                store.edit { it.copy(lastCheckAt = now, lastCheckFailed = false, lastKnownLatest = null) }
                CheckResult.UpToDate(currentVersion, latestVersion = null)
            }
            is FetchResult.Failed -> {
                store.edit { it.copy(lastCheckAt = now, lastCheckFailed = true) }
                CheckResult.Failed(result.reason)
            }
        }
    }

    private fun evaluate(release: ReleaseInfo, skippedVersion: String?, honorSkip: Boolean): CheckResult = when {
        !VersionComparator.isNewer(release.version, currentVersion) -> CheckResult.UpToDate(currentVersion, release.version)
        honorSkip && skippedVersion != null && VersionComparator.parse(skippedVersion) == VersionComparator.parse(release.version) ->
            CheckResult.SkippedByUser(release)
        else -> CheckResult.UpdateAvailable(release)
    }

    companion object {
        const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        const val RETRY_AFTER_FAILURE_MILLIS = 6L * 60 * 60 * 1000
    }
}
