package com.riyadm.unsendvault.capture.filter

import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.parser.TextRules

/**
 * Best-effort detection of content the *system* withheld from UnsendVault's listener. This is the single place that
 * knows the system placeholder strings.
 *
 * - Android 15+ redacts one-time-password content from listeners that are not trusted, replacing the text with a
 *   framework string such as "Sensitive notification content hidden".
 * - Android 17 adds a messaging read restriction (framework config `config_messaging_read_restriction_allowlist`):
 *   listeners that are not allow-listed may receive MessagingStyle notifications whose message texts are empty.
 *
 * When this matches, parsers store a [com.riyadm.unsendvault.core.model.ContentKind.SYSTEM_REDACTED] placeholder
 * instead of the garbage text. Detection can miss localized or OEM-specific variants; those are stored as normal text.
 */
object SystemRedaction {
    /** Known system placeholder texts, in [TextRules.comparable] form (lower-case, no trailing punctuation). */
    val PLACEHOLDER_TEXTS: Set<String> = setOf(
        "sensitive notification content hidden",
        "sensitive content hidden",
        "notification content hidden",
        "content hidden",
        "contents hidden",
    )

    fun isPlaceholderText(text: String?): Boolean =
        text != null && TextRules.comparable(text) in PLACEHOLDER_TEXTS

    fun isMessagingTemplate(template: String?): Boolean = template?.contains("MessagingStyle") == true

    /**
     * True when the snapshot looks like the system removed its content: the listener flagged it, a known placeholder
     * text is present, or a MessagingStyle notification arrived with messages whose texts are all empty (and that are
     * not media items).
     */
    fun looksRedacted(s: NotificationSnapshot): Boolean {
        if (s.looksSystemRedacted) return true
        if (isPlaceholderText(s.text) || isPlaceholderText(s.bigText)) return true
        if (s.messages.any { isPlaceholderText(it.text) }) return true
        if (s.messages.isNotEmpty() && s.messages.all { it.text.isNullOrBlank() && it.dataMimeType == null }) return true
        // MessagingStyle with no messages at all and no text: content stripped before it reached us.
        return isMessagingTemplate(s.template) && s.messages.isEmpty() && s.historicMessages.isEmpty() &&
            s.text.isNullOrBlank() && s.bigText.isNullOrBlank() && s.textLines.isEmpty() && !s.title.isNullOrBlank()
    }
}
