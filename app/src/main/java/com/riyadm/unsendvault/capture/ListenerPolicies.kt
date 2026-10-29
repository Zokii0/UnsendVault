package com.riyadm.unsendvault.capture

/**
 * Decides when [VaultNotificationListener] walks `getActiveNotifications()` after (re)connecting.
 *
 * - First connect in a process: full pass (cold start after reboot / process death: nothing is known).
 * - Later connects: incremental — only notifications posted or updated since the last disconnect (minus
 *   [slackMillis]); everything older was already delivered live.
 * - At most one pass per [minIntervalMillis]: some OEMs unbind/rebind listeners every few seconds, and each pass is a
 *   binder call that parcels every active notification. A connect inside the interval is deferred (not skipped) to
 *   the end of the interval, so notifications posted while disconnected are still picked up.
 *
 * Times: [decide]/[onRan] take monotonic elapsed millis, [onDisconnected] takes wall-clock millis (compared with
 * StatusBarNotification.postTime). Thread-safe.
 */
class CatchUpPolicy(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MS,
    private val slackMillis: Long = DEFAULT_SLACK_MS,
) {
    sealed interface Decision {
        /** Process active notifications with `postTime >= minPostTime` ([FULL] = all). */
        data class Run(val minPostTime: Long) : Decision

        /** Try again after [delayMillis]. */
        data class Defer(val delayMillis: Long) : Decision
    }

    private var lastRunElapsed: Long? = null
    private var lastDisconnectWall: Long? = null

    @Synchronized
    fun onDisconnected(wallNow: Long) {
        lastDisconnectWall = wallNow
    }

    @Synchronized
    fun decide(elapsedNow: Long): Decision {
        val last = lastRunElapsed ?: return Decision.Run(FULL)
        val since = elapsedNow - last
        if (since in 0 until minIntervalMillis) return Decision.Defer(minIntervalMillis - since)
        val disconnectedAt = lastDisconnectWall ?: return Decision.Run(FULL)
        return Decision.Run(disconnectedAt - slackMillis)
    }

    @Synchronized
    fun onRan(elapsedNow: Long) {
        lastRunElapsed = elapsedNow
    }

    companion object {
        const val FULL = Long.MIN_VALUE
        const val DEFAULT_MIN_INTERVAL_MS = 2 * 60_000L
        const val DEFAULT_SLACK_MS = 10_000L
    }
}

/**
 * Backoff for `requestRebind()` after an unexpected disconnect, so a system/OEM that keeps unbinding the listener can't
 * drive a tight bind/unbind loop. The first rebind is immediate; disconnects that follow a short-lived connection
 * escalate through [backoffMillis]; a connection that stayed up for [stableMillis] resets the ladder. Thread-safe.
 */
class RebindPolicy(
    private val backoffMillis: LongArray = DEFAULT_BACKOFF_MS,
    private val stableMillis: Long = DEFAULT_STABLE_MS,
) {
    private var attempt = 0
    private var lastConnectedAt: Long? = null

    @Synchronized
    fun onConnected(elapsedNow: Long) {
        lastConnectedAt = elapsedNow
    }

    /** Delay before asking for a rebind after a disconnect at [elapsedNow]. */
    @Synchronized
    fun onDisconnected(elapsedNow: Long): Long {
        val connectedAt = lastConnectedAt
        if (connectedAt != null && elapsedNow - connectedAt >= stableMillis) attempt = 0
        val delay = backoffMillis[attempt.coerceAtMost(backoffMillis.size - 1)]
        attempt++
        return delay
    }

    companion object {
        val DEFAULT_BACKOFF_MS = longArrayOf(0L, 10_000L, 60_000L, 5 * 60_000L, 30 * 60_000L)
        const val DEFAULT_STABLE_MS = 10 * 60_000L
    }
}

/** What [CapturePauseController] needs from the platform. Production: [AndroidListenerControl]. */
interface ListenerControl {
    fun isAccessGranted(): Boolean
    fun isConnected(): Boolean

    /** Asks the currently bound listener to unbind. False when no listener instance is bound. */
    fun requestUnbind(): Boolean
    fun requestRebind()
}

/**
 * "Pause" stops the callbacks instead of ignoring them: while capture is paused the listener asks the system to unbind
 * it (`requestUnbind()`), so no notification on the phone reaches the app at all. Resuming — from the app, the widget,
 * or a backup import, anything that writes `captureEnabled = true` — calls the static `requestRebind()`, which works
 * while unbound. A reconnect while paused (e.g. after a reboot, the system's unbind request is not persistent)
 * immediately unbinds again, and a self-requested unbind never triggers the automatic rebind.
 *
 * Safety net: whenever capture is enabled, access is granted and the listener isn't connected, a rebind is requested
 * (a no-op in the system when the listener isn't in the unbound state), so the app can't get stuck unbound.
 * Thread-safe; [onCaptureEnabledChanged] runs on the settings collector, the others on the listener's main thread.
 */
class CapturePauseController(
    private val control: ListenerControl,
    private val unbindWhilePaused: Boolean = UNBIND_WHILE_PAUSED,
) {
    private var captureEnabled: Boolean? = null
    private var selfUnbinding = false

    /** Last known captureEnabled (null until settings were read in this process). */
    val knownCaptureEnabled: Boolean? @Synchronized get() = captureEnabled

    @Synchronized
    fun onCaptureEnabledChanged(enabled: Boolean) {
        captureEnabled = enabled
        if (!enabled) {
            if (unbindWhilePaused && control.isConnected()) unbind()
        } else {
            selfUnbinding = false
            if (!control.isConnected() && control.isAccessGranted()) {
                CaptureStats.inc(CaptureStats.Counter.REBIND_REQUESTS)
                control.requestRebind()
            }
        }
    }

    @Synchronized
    fun onListenerConnected() {
        selfUnbinding = false
        if (unbindWhilePaused && captureEnabled == false) unbind()
    }

    /** True when an automatic rebind may follow this disconnect. */
    @Synchronized
    fun onListenerDisconnected(): Boolean = !selfUnbinding && captureEnabled != false

    private fun unbind() {
        selfUnbinding = true
        if (control.requestUnbind()) {
            CaptureStats.inc(CaptureStats.Counter.UNBIND_FOR_PAUSE)
        } else {
            selfUnbinding = false
        }
    }

    companion object {
        /** Kill switch for the unbind-while-paused behaviour (false = keep the binding and drop callbacks early). */
        const val UNBIND_WHILE_PAUSED = true
    }
}
