package com.riyadm.unsendvault.update

import kotlinx.serialization.Serializable

/**
 * Opt-in update check (see docs/UPDATES.md).
 *
 * This package is the ONLY place in the app allowed to open a network connection (enforced by NetworkIsolationTest).
 * It talks to exactly one fixed endpoint, [GitHubReleaseClient.LATEST_RELEASE_URL], and only from the foreground UI.
 * Nothing in capture/, widget/ or data/ references it.
 */

/** Whether the user agreed to the automatic (daily, foreground-only) check. "Check now" in Settings ignores this. */
enum class UpdateConsent { UNASKED, ALLOWED, DECLINED }

/** A published, non-draft, non-prerelease GitHub release, reduced to what the app needs. */
@Serializable
data class ReleaseInfo(
    /** Normalized "major.minor.patch", e.g. "1.0.16" for tag "v1.0.16". */
    val version: String,
    val tagName: String,
    val name: String,
    /** Release page (html_url), always on github.com/riyadmondol2006/UnsendVault. */
    val pageUrl: String,
    /** Browser download URL of `UnsendVault-<version>.apk`, or null when the release has no such asset. */
    val apkUrl: String? = null,
    val apkSizeBytes: Long? = null,
    /** Browser download URL of `UnsendVault-<version>.apk.sha256`, when present. */
    val sha256Url: String? = null,
    /** Raw release notes (markdown, capped). Shown only as plain text via [ReleaseNotes.excerpt]. */
    val notes: String = "",
    val publishedAt: String? = null,
)

enum class FailureReason {
    /** No network / DNS failure / connection refused. */
    OFFLINE,
    TIMEOUT,

    /** HTTP 429, or 403 with an exhausted rate limit. */
    RATE_LIMITED,

    /** Any other non-success HTTP status. */
    HTTP_ERROR,

    /** Body larger than the client's limit. */
    TOO_LARGE,

    /** Body was not the JSON shape we expect. */
    INVALID_RESPONSE,

    /** TLS failure or another I/O error. */
    NETWORK,
}

/** Outcome of one request to the GitHub releases API. */
sealed interface FetchResult {
    data class Found(val release: ReleaseInfo) : FetchResult

    /** 404 (no published release yet), or the latest release is a draft/prerelease/unparseable tag. */
    data object NoRelease : FetchResult

    data class Failed(val reason: FailureReason, val httpStatus: Int? = null) : FetchResult
}

/** Abstraction over [GitHubReleaseClient] so the checker can be tested with a fake. */
fun interface ReleaseSource {
    suspend fun fetchLatest(): FetchResult
}

/** Outcome of [UpdateChecker.checkIfDue] / [UpdateChecker.checkNow]. */
sealed interface CheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : CheckResult

    /** [latestVersion] is null when GitHub has no eligible release. */
    data class UpToDate(val currentVersion: String, val latestVersion: String?) : CheckResult

    /** A newer version exists but the user chose "Skip this version" (automatic check only). */
    data class SkippedByUser(val release: ReleaseInfo) : CheckResult

    data class Failed(val reason: FailureReason) : CheckResult

    /** The automatic check did not contact GitHub. */
    data class NotRun(val why: NotRunReason) : CheckResult
}

enum class NotRunReason { NO_CONSENT, DEBUG_BUILD, NOT_DUE }
