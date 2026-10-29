package com.riyadm.unsendvault.capture.adapter

import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.parser.CapturePlaceholders
import com.riyadm.unsendvault.capture.parser.DeletionMarkerRules
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import com.riyadm.unsendvault.capture.parser.RejectReason
import com.riyadm.unsendvault.capture.parser.TextRules
import com.riyadm.unsendvault.capture.parser.TitleInfo

/**
 * WhatsApp and WhatsApp Business.
 *
 * Modern builds post MessagingStyle notifications (group name in EXTRA_CONVERSATION_TITLE, per-message senders).
 * Legacy flat layouts are also understood: "Group (3 messages): Sender" and "Sender @ Group" titles. The group
 * summary ("N messages from M chats") is skipped. Deletion evidence: WhatsApp replaces a deleted message's text in
 * place with "This message was deleted" / "You deleted this message" (plus a few translations), at the original
 * message timestamp — see [DeletionMarkerRules.WHATSAPP].
 */
class WhatsAppAdapter(placeholders: CapturePlaceholders) : BaseAdapter(
    placeholders, ID, setOf(KnownApps.WHATSAPP, KnownApps.WHATSAPP_BUSINESS), dedicatedMessenger = true,
) {
    override fun parseTitle(s: NotificationSnapshot): TitleInfo {
        val raw = TextRules.cleanName(s.title)
        if (raw != null && TextRules.cleanTitle(s.conversationTitle) == null) {
            GROUP_WITH_COUNT.matchEntire(raw)?.let { m ->
                return TitleInfo(TextRules.cleanTitle(m.groupValues[1]), TextRules.cleanName(m.groupValues[2]), isGroup = true)
            }
            SENDER_AT_GROUP.matchEntire(raw)?.let { m ->
                return TitleInfo(TextRules.cleanTitle(m.groupValues[2]), TextRules.cleanName(m.groupValues[1]), isGroup = true)
            }
        }
        return super.parseTitle(s)
    }

    override fun deletionMarker(text: String, sender: String?, knownNames: Collection<String>) =
        DeletionMarkerRules.matchExact(text, DeletionMarkerRules.WHATSAPP, sender)

    companion object {
        const val ID = "whatsapp"
        private val GROUP_WITH_COUNT = Regex("^(.+?)\\s*\\(\\d+\\s+(?:new\\s+)?messages?\\):\\s*(.+)$", RegexOption.IGNORE_CASE)
        private val SENDER_AT_GROUP = Regex("^(.+?)\\s+@\\s+(.+)$")
    }
}

/**
 * Instagram (and Instagram Lite / Threads). Instagram also posts likes, follows and comments, so it only claims
 * MessagingStyle / CATEGORY_MESSAGE notifications or those on a direct-message channel. Deletion evidence:
 * "<name> unsent a message" where the name is one the notification already shows.
 */
class InstagramAdapter(placeholders: CapturePlaceholders) : BaseAdapter(
    placeholders, ID, setOf(KnownApps.INSTAGRAM, KnownApps.INSTAGRAM_LITE, KnownApps.THREADS), dedicatedMessenger = false,
) {
    override fun isMessageNotification(s: NotificationSnapshot): Boolean =
        super.isMessageNotification(s) || isDirectChannel(s.channelId)

    override fun deletionMarker(text: String, sender: String?, knownNames: Collection<String>) =
        DeletionMarkerRules.matchAction(text, DeletionMarkerRules.INSTAGRAM_ACTIONS, sender, knownNames)

    companion object {
        const val ID = "instagram"

        /** Channel-id heuristic for direct messages; only widens the messages-only gate. */
        fun isDirectChannel(channelId: String?): Boolean {
            val c = channelId?.lowercase() ?: return false
            return "direct" in c || c.startsWith("dm") || "_dm" in c || "message" in c
        }
    }
}

/** Messenger / Messenger Lite. Deletion evidence: "<name> unsent a message" / "<name> removed a message". */
class MessengerAdapter(placeholders: CapturePlaceholders) : BaseAdapter(
    placeholders, ID, setOf(KnownApps.MESSENGER, KnownApps.MESSENGER_LITE), dedicatedMessenger = true,
) {
    override fun deletionMarker(text: String, sender: String?, knownNames: Collection<String>) =
        DeletionMarkerRules.matchAction(text, DeletionMarkerRules.MESSENGER_ACTIONS, sender, knownNames)

    companion object {
        const val ID = "messenger"
    }
}

/**
 * Facebook main app: messages only, regardless of the "messages only" setting. Social notifications (likes,
 * comments, friend requests, memories) are always rejected.
 */
class FacebookAdapter(placeholders: CapturePlaceholders) : BaseAdapter(
    placeholders, ID, setOf(KnownApps.FACEBOOK, KnownApps.FACEBOOK_LITE), dedicatedMessenger = false,
) {
    override fun parse(s: NotificationSnapshot): ParseOutcome? {
        if (!isMessageNotification(s)) return ParseOutcome.Rejected(RejectReason.NOT_A_MESSAGE)
        return super.parse(s)
    }

    override fun deletionMarker(text: String, sender: String?, knownNames: Collection<String>) =
        DeletionMarkerRules.matchAction(text, DeletionMarkerRules.MESSENGER_ACTIONS, sender, knownNames)

    companion object {
        const val ID = "facebook"
    }
}

/** Telegram, Telegram (web build) and Telegram X. Supports legacy "Sender @ Group" titles. */
class TelegramAdapter(placeholders: CapturePlaceholders) : BaseAdapter(
    placeholders, ID, setOf(KnownApps.TELEGRAM, KnownApps.TELEGRAM_WEB, KnownApps.TELEGRAM_X), dedicatedMessenger = true,
) {
    override fun parseTitle(s: NotificationSnapshot): TitleInfo {
        val raw = TextRules.cleanName(s.title)
        if (raw != null && TextRules.cleanTitle(s.conversationTitle) == null) {
            SENDER_AT_GROUP.matchEntire(raw)?.let { m ->
                return TitleInfo(TextRules.cleanTitle(m.groupValues[2]), TextRules.cleanName(m.groupValues[1]), isGroup = true)
            }
        }
        return super.parseTitle(s)
    }

    override fun deletionMarker(text: String, sender: String?, knownNames: Collection<String>) =
        DeletionMarkerRules.matchExact(text, DeletionMarkerRules.DELETED_GENERIC, sender)

    companion object {
        const val ID = "telegram"
        private val SENDER_AT_GROUP = Regex("^(.+?)\\s+@\\s+(.+)$")
    }
}

/** Signal. */
class SignalAdapter(placeholders: CapturePlaceholders) : BaseAdapter(
    placeholders, ID, setOf(KnownApps.SIGNAL), dedicatedMessenger = true,
) {
    override fun deletionMarker(text: String, sender: String?, knownNames: Collection<String>) =
        DeletionMarkerRules.matchExact(text, DeletionMarkerRules.DELETED_GENERIC, sender)

    companion object {
        const val ID = "signal"
    }
}

/** SMS/RCS apps (Google Messages, Samsung Messages): every non-noise notification is a message. */
class SmsAdapter(placeholders: CapturePlaceholders, id: String, packages: Set<String>) :
    BaseAdapter(placeholders, id, packages, dedicatedMessenger = true) {
    companion object {
        const val GOOGLE_MESSAGES_ID = "google_messages"
        const val SAMSUNG_MESSAGES_ID = "samsung_messages"
    }
}

/**
 * Data-driven adapter for messengers that need no special parsing beyond the generic rules, optionally with extra
 * noise phrases and a body cleaner.
 */
class SimpleAdapter(
    placeholders: CapturePlaceholders,
    id: String,
    packages: Set<String>,
    dedicatedMessenger: Boolean,
    /** Extra whole-text noise patterns, matched against [TextRules.comparable] text. */
    private val extraNoise: List<Regex> = emptyList(),
    private val bodyCleaner: ((String) -> String)? = null,
    private val splitSenderPrefix: Boolean = false,
) : BaseAdapter(placeholders, id, packages, dedicatedMessenger) {

    override val alwaysSplitSenderPrefix: Boolean get() = splitSenderPrefix

    override fun cleanBody(text: String): String = bodyCleaner?.invoke(text) ?: text

    override fun isFlatNoise(text: String): Boolean {
        if (super.isFlatNoise(text)) return true
        val c = TextRules.comparable(text)
        return extraNoise.any { it.matches(c) }
    }
}
