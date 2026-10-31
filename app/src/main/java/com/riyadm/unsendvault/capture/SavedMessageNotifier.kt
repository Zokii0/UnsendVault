package com.riyadm.unsendvault.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.riyadm.unsendvault.MainActivity
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.di.AppContainer
import com.riyadm.unsendvault.widget.WidgetIntents

/**
 * Optional, off by default. When a new message from someone else is saved, posts one silent notification per
 * conversation so it can be opened here instead of in the chat app. It never shows message text (only the chat
 * name and app), never stays pinned (auto-cancel, no ongoing flag) and uses a low-importance channel, so there is no
 * second sound or heads-up next to the chat app's own notification. The user can still change the channel in system
 * settings. The listener ignores this app's own notifications, so they can never be captured again.
 */
object SavedMessageNotifier {
    private const val CHANNEL_ID = "saved_messages"
    private const val FRESH_MILLIS = 2 * 60_000L

    suspend fun onSaved(container: AppContainer, parsed: ParsedNotification, result: IngestResult, capturedAt: Long) {
        if (result.inserted <= 0) return
        if (!container.captureSettings.get().notifyOnSave) return
        val context = container.context
        if (!canPost(context)) return
        val self = CaptureModule.placeholders(context).selfSender
        // Fresh messages from others only: skips the user's own replies and stale items from a catch-up pass.
        val latest = parsed.messages
            .filter { it.sender != self && capturedAt - it.messageTimestamp <= FRESH_MILLIS }
            .lastOrNull() ?: return
        val conversationId = container.vaultRepository.conversationIdOf(latest.packageName, latest.conversationKey) ?: return
        post(context, conversationId, latest.conversationTitle.ifBlank { parsed.appLabel }, parsed.appLabel)
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun post(context: Context, conversationId: Long, title: String, appLabel: String) {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(WidgetIntents.EXTRA_DESTINATION, WidgetIntents.DESTINATION_CONVERSATION)
            .putExtra(WidgetIntents.EXTRA_CONVERSATION_ID, conversationId)
        val pending = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_vault)
            .setContentTitle(context.getString(R.string.notify_public_title))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_vault)
            .setContentTitle(title)
            .setContentText(appLabel)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        try {
            // One notification per conversation: later messages replace it instead of stacking.
            NotificationManagerCompat.from(context).notify("saved", conversationId.hashCode(), notification)
        } catch (e: SecurityException) {
            CaptureLog.d("saved-message notification refused")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notify_channel_description) },
        )
    }
}
