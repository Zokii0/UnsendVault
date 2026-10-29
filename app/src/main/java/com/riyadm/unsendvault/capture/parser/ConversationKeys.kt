package com.riyadm.unsendvault.capture.parser

/**
 * Builds the stable per-app conversation key stored with every message.
 *
 * Priority: conversation shortcut id (apps that publish conversation shortcuts, the norm on API 30+) → conversation
 * title → notification title → sender. Titles and senders share one `name:` namespace so a 1:1 chat keyed by its
 * title and the same chat keyed by its sender land in the same conversation. Names are cleaned with
 * [TextRules.cleanTitle] (invisible marks, unread counters such as " (3 messages)" / " (2)" removed) and lower-cased.
 */
object ConversationKeys {
    const val SHORTCUT_PREFIX = "shortcut:"
    const val NAME_PREFIX = "name:"
    const val UNKNOWN = "unknown"

    fun of(shortcutId: String?, vararg names: String?): String {
        shortcutId?.trim()?.takeIf { it.isNotEmpty() }?.let { return SHORTCUT_PREFIX + it }
        for (name in names) {
            val key = TextRules.keyOf(name)
            if (!key.isNullOrEmpty()) return NAME_PREFIX + key
        }
        return UNKNOWN
    }
}
