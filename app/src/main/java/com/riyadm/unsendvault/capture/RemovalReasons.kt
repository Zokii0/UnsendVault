package com.riyadm.unsendvault.capture

import android.service.notification.NotificationListenerService
import com.riyadm.unsendvault.core.model.RemovalReason

/**
 * Maps NotificationListenerService.REASON_* codes to [RemovalReason]. Recorded for transparency only — a removal is
 * never treated as evidence that a message was deleted. Constants are compile-time ints, so newer codes are safe to
 * reference on older devices (they simply never occur there).
 */
object RemovalReasons {
    fun fromListenerReason(reason: Int): RemovalReason = when (reason) {
        NotificationListenerService.REASON_CLICK -> RemovalReason.CLICKED
        NotificationListenerService.REASON_CANCEL,
        NotificationListenerService.REASON_CANCEL_ALL -> RemovalReason.USER_DISMISSED
        NotificationListenerService.REASON_APP_CANCEL,
        NotificationListenerService.REASON_APP_CANCEL_ALL -> RemovalReason.APP_CANCELLED
        NotificationListenerService.REASON_LISTENER_CANCEL,
        NotificationListenerService.REASON_LISTENER_CANCEL_ALL -> RemovalReason.LISTENER_CANCELLED
        NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED -> RemovalReason.GROUP_SUMMARY_CANCELED
        NotificationListenerService.REASON_TIMEOUT -> RemovalReason.TIMEOUT
        NotificationListenerService.REASON_CHANNEL_BANNED,
        NotificationListenerService.REASON_CHANNEL_REMOVED,
        NotificationListenerService.REASON_PACKAGE_BANNED -> RemovalReason.CHANNEL_DISABLED
        NotificationListenerService.REASON_PACKAGE_CHANGED,
        NotificationListenerService.REASON_CLEAR_DATA -> RemovalReason.PACKAGE_CHANGED
        NotificationListenerService.REASON_USER_STOPPED -> RemovalReason.USER_STOPPED
        NotificationListenerService.REASON_ERROR,
        NotificationListenerService.REASON_GROUP_OPTIMIZATION,
        NotificationListenerService.REASON_PACKAGE_SUSPENDED,
        NotificationListenerService.REASON_PROFILE_TURNED_OFF,
        NotificationListenerService.REASON_UNAUTOBUNDLED,
        NotificationListenerService.REASON_SNOOZED,
        NotificationListenerService.REASON_ASSISTANT_CANCEL,
        NotificationListenerService.REASON_LOCKDOWN -> RemovalReason.OTHER
        else -> RemovalReason.UNKNOWN
    }
}
