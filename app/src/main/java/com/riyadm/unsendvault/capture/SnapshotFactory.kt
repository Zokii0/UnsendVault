package com.riyadm.unsendvault.capture

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.riyadm.unsendvault.capture.filter.SystemRedaction

/**
 * Copies the fields UnsendVault needs out of a [StatusBarNotification] into a plain [NotificationSnapshot].
 *
 * Runs on the listener callback thread, so it only performs cheap field reads. It never keeps references to the
 * Notification, its Bitmaps/Icons or PendingIntents: every value is converted to String/primitive. Each extra is read
 * defensively — a malformed or hostile notification yields missing fields, never an exception.
 */
object SnapshotFactory {
    /** Upper bound on MessagingStyle entries copied per notification (newest kept). */
    const val MAX_MESSAGES = 100
    private const val MAX_FIELD_LENGTH = 5_000

    // Notification.EXTRA_* keys as literals so reading them never depends on the runtime API level.
    private const val EXTRA_TITLE = "android.title"
    private const val EXTRA_TITLE_BIG = "android.title.big"
    private const val EXTRA_TEXT = "android.text"
    private const val EXTRA_BIG_TEXT = "android.bigText"
    private const val EXTRA_SUB_TEXT = "android.subText"
    private const val EXTRA_SUMMARY_TEXT = "android.summaryText"
    private const val EXTRA_TEXT_LINES = "android.textLines"
    private const val EXTRA_CONVERSATION_TITLE = "android.conversationTitle"
    private const val EXTRA_IS_GROUP_CONVERSATION = "android.isGroupConversation"
    private const val EXTRA_SELF_DISPLAY_NAME = "android.selfDisplayName"
    private const val EXTRA_MESSAGING_PERSON = "android.messagingUser"
    private const val EXTRA_COMPAT_MESSAGING_STYLE_USER = "android.messagingStyleUser"
    private const val EXTRA_MESSAGES = "android.messages"
    private const val EXTRA_HISTORIC_MESSAGES = "android.messages.historic"
    private const val EXTRA_TEMPLATE = "android.template"
    private const val EXTRA_COMPAT_TEMPLATE = "androidx.core.app.extra.COMPAT_TEMPLATE"

    // Keys inside each EXTRA_MESSAGES bundle (Notification.MessagingStyle.Message / NotificationCompat).
    private const val KEY_TEXT = "text"
    private const val KEY_TIMESTAMP = "time"
    private const val KEY_SENDER = "sender"
    private const val KEY_SENDER_PERSON = "sender_person"
    private const val KEY_COMPAT_PERSON = "person"
    private const val KEY_DATA_MIME_TYPE = "type"
    private const val KEY_PERSON_NAME = "name"

    /** Returns null for notifications that are cheap to reject: own package, ongoing/foreground, no extras. */
    fun fromStatusBarNotification(sbn: StatusBarNotification, ownPackage: String): NotificationSnapshot? {
        val packageName = sbn.packageName ?: return null
        if (packageName == ownPackage) return null
        val notification = sbn.notification ?: return null
        return fromNotification(
            packageName = packageName,
            key = sbn.key ?: "$packageName|${sbn.id}|${sbn.tag}",
            id = sbn.id,
            tag = sbn.tag,
            postTime = sbn.postTime,
            notification = notification,
        )
    }

    fun fromNotification(
        packageName: String,
        key: String,
        id: Int,
        tag: String?,
        postTime: Long,
        notification: Notification,
    ): NotificationSnapshot? {
        val flags = notification.flags
        val ongoing = flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (ongoing) return null
        val extras: Bundle = notification.extras ?: return null

        val template = extras.string(EXTRA_TEMPLATE) ?: extras.string(EXTRA_COMPAT_TEMPLATE)
        val snapshot = NotificationSnapshot(
            packageName = packageName,
            key = key,
            id = id,
            tag = tag,
            postTime = postTime,
            whenTime = notification.`when`,
            category = notification.category,
            channelId = safe { notification.channelId },
            groupKey = safe { notification.group },
            isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = false,
            shortcutId = safe { notification.shortcutId },
            template = template,
            title = extras.text(EXTRA_TITLE) ?: extras.text(EXTRA_TITLE_BIG),
            text = extras.text(EXTRA_TEXT),
            bigText = extras.text(EXTRA_BIG_TEXT),
            subText = extras.text(EXTRA_SUB_TEXT),
            summaryText = extras.text(EXTRA_SUMMARY_TEXT),
            textLines = extras.textArray(EXTRA_TEXT_LINES),
            conversationTitle = extras.text(EXTRA_CONVERSATION_TITLE),
            isGroupConversation = safe { extras.getBoolean(EXTRA_IS_GROUP_CONVERSATION, false) } ?: false,
            selfName = extras.text(EXTRA_SELF_DISPLAY_NAME)
                ?: personName(extras.raw(EXTRA_MESSAGING_PERSON))
                ?: personName(extras.raw(EXTRA_COMPAT_MESSAGING_STYLE_USER)),
            messages = readMessages(extras.raw(EXTRA_MESSAGES)),
            historicMessages = readMessages(extras.raw(EXTRA_HISTORIC_MESSAGES)),
            tickerText = safe { notification.tickerText?.toString() }?.clamp(),
        )
        return snapshot.copy(looksSystemRedacted = SystemRedaction.looksRedacted(snapshot))
    }

    /** Parses an EXTRA_MESSAGES value (Parcelable[] of Bundles). Unknown shapes yield an empty list. */
    internal fun readMessages(raw: Any?): List<SnapshotMessage> {
        val items: List<Any?> = when (raw) {
            is Array<*> -> raw.asList()
            is List<*> -> raw
            else -> return emptyList()
        }
        val tail = if (items.size > MAX_MESSAGES) items.subList(items.size - MAX_MESSAGES, items.size) else items
        return tail.mapNotNull { item -> (item as? Bundle)?.let { safe { messageFromBundle(it) } } }
    }

    internal fun messageFromBundle(bundle: Bundle): SnapshotMessage? {
        if (!bundle.containsKey(KEY_TEXT) && !bundle.containsKey(KEY_DATA_MIME_TYPE)) return null
        val sender = bundle.text(KEY_SENDER)?.takeIf { it.isNotBlank() }
            ?: personName(bundle.raw(KEY_SENDER_PERSON))
            ?: personName(bundle.raw(KEY_COMPAT_PERSON))
        val timestamp = (bundle.raw(KEY_TIMESTAMP) as? Number)?.toLong() ?: 0L
        return SnapshotMessage(
            sender = sender,
            text = bundle.text(KEY_TEXT),
            timestamp = timestamp,
            dataMimeType = bundle.string(KEY_DATA_MIME_TYPE)?.takeIf { it.isNotBlank() },
        )
    }

    /** Name of an android.app.Person (API 28+) or of a Person serialised as a Bundle by AndroidX on older APIs. */
    private fun personName(value: Any?): String? {
        val name = when {
            value is Bundle -> value.text(KEY_PERSON_NAME)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && value is Person -> safe { value.name?.toString() }
            else -> null
        }
        return name?.takeIf { it.isNotBlank() }
    }

    // --- Defensive Bundle access -------------------------------------------------------------------------------------

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    @Suppress("DEPRECATION")
    private fun Bundle.raw(key: String): Any? = safe { get(key) }

    private fun Bundle.text(key: String): String? = (raw(key) as? CharSequence)?.toString()?.clamp()

    private fun Bundle.string(key: String): String? = (raw(key) as? CharSequence)?.toString()

    private fun Bundle.textArray(key: String): List<String> {
        val value = raw(key) as? Array<*> ?: return emptyList()
        return value.mapNotNull { (it as? CharSequence)?.toString()?.clamp() }.take(MAX_MESSAGES)
    }

    private fun String.clamp(): String = if (length <= MAX_FIELD_LENGTH) this else substring(0, MAX_FIELD_LENGTH)
}
