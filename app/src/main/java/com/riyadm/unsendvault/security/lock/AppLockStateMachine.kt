package com.riyadm.unsendvault.security.lock

/** What the UI may render. Archive content is composed only in [Unlocked]. */
enum class LockState {
    /** Settings not loaded yet: render a blank surface (never archive content, never a lock prompt). */
    Initializing,
    Locked,
    Unlocked,
}

/**
 * Pure, single-threaded app-lock state machine. All time comes from [clock] (monotonic millis in production,
 * a fake in tests). Rules:
 *
 * - Cold start (first settings snapshot) with the lock enabled -> [LockState.Locked].
 * - Enabling the lock while the app is open does not lock immediately; disabling it unlocks.
 * - Going to background records the time; returning after more than `timeoutSeconds` re-locks
 *   (timeout 0 = re-lock on every return).
 * - [expectExternalActivity] grants a short grace period for a user-initiated trip to another activity
 *   (system file picker, share sheet, notification-access settings) so the user is not locked out mid-task.
 */
class AppLockStateMachine(
    private val clock: () -> Long,
    private val externalGraceMillis: Long = DEFAULT_EXTERNAL_GRACE_MILLIS,
) {
    var state: LockState = LockState.Initializing
        private set

    var enabled: Boolean = false
        private set

    private var timeoutMillis: Long = 0
    private var backgroundedAt: Long? = null
    private var externalActivityAt: Long? = null

    fun onSettings(lockEnabled: Boolean, timeoutSeconds: Int): LockState {
        val firstSnapshot = state == LockState.Initializing
        timeoutMillis = timeoutSeconds.coerceAtLeast(0) * 1000L
        enabled = lockEnabled
        state = when {
            firstSnapshot -> if (lockEnabled) LockState.Locked else LockState.Unlocked
            !lockEnabled -> LockState.Unlocked
            // Enabled while in-app: keep the user where they are; the next background trip applies the timeout.
            else -> state
        }
        return state
    }

    /**
     * With a zero timeout the archive is hidden as soon as the app leaves the foreground (unless a user-initiated
     * external activity is in progress), so it is never visible for even a frame when the user returns.
     */
    fun onBackground(): LockState {
        val now = clock()
        backgroundedAt = now
        val external = externalActivityAt
        val inGrace = external != null && now - external in 0..externalGraceMillis
        if (enabled && state == LockState.Unlocked && timeoutMillis == 0L && !inGrace) state = LockState.Locked
        return state
    }

    fun onForeground(): LockState {
        val now = clock()
        val since = backgroundedAt
        val external = externalActivityAt
        backgroundedAt = null
        externalActivityAt = null
        if (!enabled || state != LockState.Unlocked || since == null) return state
        val inGrace = external != null && now - external in 0..externalGraceMillis
        val elapsed = now - since
        // A negative elapsed time means the clock is not monotonic; fail closed.
        if (!inGrace && (elapsed < 0 || elapsed >= timeoutMillis)) state = LockState.Locked
        return state
    }

    fun onAuthenticated(): LockState {
        if (state == LockState.Locked) state = LockState.Unlocked
        return state
    }

    /** Manual "lock now". */
    fun lock(): LockState {
        if (enabled && state == LockState.Unlocked) state = LockState.Locked
        return state
    }

    fun expectExternalActivity() {
        externalActivityAt = clock()
    }

    companion object {
        const val DEFAULT_EXTERNAL_GRACE_MILLIS: Long = 2 * 60 * 1000L
        val TIMEOUT_OPTIONS_SECONDS: List<Int> = listOf(0, 30, 60, 300, 900)
    }
}
