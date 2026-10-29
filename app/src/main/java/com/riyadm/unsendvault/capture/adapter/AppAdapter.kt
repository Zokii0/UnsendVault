package com.riyadm.unsendvault.capture.adapter

import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.parser.CapturePlaceholders
import com.riyadm.unsendvault.capture.parser.GenericNotificationParser
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import com.riyadm.unsendvault.capture.parser.TextRules

/**
 * Per-app knowledge layered on top of [GenericNotificationParser]. Adapters must stay resilient: correctness of normal
 * message capture must not depend on exact UI strings — those are only used in clearly isolated marker / noise lists.
 * [AdapterRegistry] wraps every call, so an exception or an empty result falls back to the generic parser.
 */
interface AppAdapter {
    val id: String
    val packages: Set<String>

    /**
     * Gate for the "messages only" setting: true when this notification should be treated as a message. Dedicated
     * messengers claim everything they post (minus noise); mixed apps (Instagram, Facebook, Slack…) only claim
     * MessagingStyle / CATEGORY_MESSAGE notifications.
     */
    fun isMessageNotification(s: NotificationSnapshot): Boolean =
        mayBeMessage(s.category, s.channelId, s.template, s.messages.isNotEmpty())

    /**
     * The same gate evaluated on raw notification fields, before a [NotificationSnapshot] is built (listener hot path,
     * see [com.riyadm.unsendvault.capture.CapturePreFilter]). [hasMessages] is "EXTRA_MESSAGES is present", which is
     * true whenever the snapshot's message list could be non-empty, so this never rejects what the full gate accepts.
     * The default (true) is the conservative answer for adapters that only implement [isMessageNotification].
     */
    fun mayBeMessage(category: String?, channelId: String?, template: String?, hasMessages: Boolean): Boolean = true

    /** Parses [s]. Returning null (or throwing, or an empty [ParseOutcome.Parsed]) means "use the generic parser". */
    fun parse(s: NotificationSnapshot): ParseOutcome?
}

/** Shared base: generic parsing with hook overrides. */
abstract class BaseAdapter(
    placeholders: CapturePlaceholders,
    final override val id: String,
    final override val packages: Set<String>,
    /** True for apps whose notifications are essentially all chat messages. */
    private val dedicatedMessenger: Boolean,
) : GenericNotificationParser(placeholders), AppAdapter {

    final override val adapterId: String get() = id

    override fun mayBeMessage(category: String?, channelId: String?, template: String?, hasMessages: Boolean): Boolean =
        dedicatedMessenger || isMessagingStyle(template, hasMessages) || category == TextRules.CATEGORY_MESSAGE

    override fun parse(s: NotificationSnapshot): ParseOutcome? = parseNotification(s)
}

/** Default adapter for packages without dedicated knowledge. */
class GenericAdapter(placeholders: CapturePlaceholders) :
    BaseAdapter(placeholders, GenericNotificationParser.GENERIC_ID, emptySet(), dedicatedMessenger = false)
