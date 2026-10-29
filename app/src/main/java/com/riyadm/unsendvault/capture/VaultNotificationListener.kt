package com.riyadm.unsendvault.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.riyadm.unsendvault.appContainer

/**
 * Receives notifications after the user explicitly grants Notification Access.
 *
 * Callbacks arrive on the main thread, so they only do cheap rejects (own package, ongoing, missing extras) and copy
 * fields into a [NotificationSnapshot] via [SnapshotFactory]; parsing, filtering and storage happen on the
 * [CaptureEngine]'s single background consumer. No wake locks, alarms, polling or foreground service are used.
 * Every callback is guarded so a malformed notification can never crash the service.
 */
class VaultNotificationListener : NotificationListenerService() {

    private val engine: CaptureEngine? get() = try {
        applicationContext.appContainer.captureEngine
    } catch (e: Exception) {
        null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerConnectionState.set(true)
        CaptureLog.d("listener connected")
        catchUpActiveNotifications()
    }

    override fun onListenerDisconnected() {
        ListenerConnectionState.set(false)
        CaptureLog.d("listener disconnected")
        super.onListenerDisconnected()
        // OEM task killers (Xiaomi, Samsung, Oppo, Vivo, OnePlus, Huawei…) commonly unbind listeners; ask to come back.
        NotificationAccess.requestRebind(applicationContext)
    }

    override fun onDestroy() {
        ListenerConnectionState.set(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val snapshot = SnapshotFactory.fromStatusBarNotification(sbn, packageName) ?: return
            engine?.onPosted(snapshot)
        } catch (e: Exception) {
            CaptureLog.d("onNotificationPosted failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (sbn == null) return
        try {
            val pkg = sbn.packageName ?: return
            if (pkg == packageName) return
            engine?.onRemoved(
                RemovalEvent(
                    packageName = pkg,
                    notificationKey = sbn.key ?: return,
                    reason = RemovalReasons.fromListenerReason(reason),
                    removedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            CaptureLog.d("onNotificationRemoved failed: ${e.javaClass.simpleName}")
        }
    }

    /** One-shot pass over what is already in the shade (e.g. after a reboot or rebind). Deduplicated downstream. */
    private fun catchUpActiveNotifications() {
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        } catch (e: RuntimeException) {
            null
        } ?: return
        val engine = engine ?: return
        var count = 0
        for (sbn in active) {
            try {
                SnapshotFactory.fromStatusBarNotification(sbn ?: continue, packageName)?.let {
                    engine.onPosted(it)
                    count++
                }
            } catch (e: Exception) {
                // Skip the malformed one, keep going.
            }
        }
        CaptureLog.d("catch-up queued $count notifications")
    }
}
