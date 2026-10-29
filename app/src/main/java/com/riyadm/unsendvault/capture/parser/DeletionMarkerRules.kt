package com.riyadm.unsendvault.capture.parser

/**
 * Explicit "message deleted / unsent" phrases that source apps display in place of a removed message.
 *
 * ## What counts as deletion evidence
 * Only these exact, whole-text markers produced by the source app are evidence. They are emitted as
 * [com.riyadm.unsendvault.capture.DeletionMarker]s — never archived as messages — and the data layer matches them to
 * the archived message of the same conversation (for WhatsApp, at the same message timestamp, because WhatsApp
 * replaces the deleted message's text in place).
 *
 * The following are **not** evidence and are never reported: a notification being removed or dismissed, a message
 * disappearing from a later MessagingStyle history, an edited text, or any sentence that merely *contains* one of
 * these phrases ("I deleted this message from my notes yesterday" does not match).
 */
object DeletionMarkerRules {
    /** WhatsApp / WhatsApp Business, compared in [TextRules.comparable] form. */
    val WHATSAPP: Set<String> = setOf(
        "this message was deleted",
        "you deleted this message",
        "se eliminó este mensaje", // es
        "eliminaste este mensaje", // es (self)
        "esta mensagem foi apagada", // pt
        "você apagou esta mensagem", // pt (self)
        "ce message a été supprimé", // fr
        "vous avez supprimé ce message", // fr (self)
        "diese nachricht wurde gelöscht", // de
        "du hast diese nachricht gelöscht", // de (self)
        "pesan ini telah dihapus", // id
        "anda menghapus pesan ini", // id (self)
        "यह संदेश हटाया गया", // hi
    )

    /** Self-variants above whose sender is the device owner. */
    private val SELF_PHRASES: Set<String> = setOf(
        "you deleted this message", "eliminaste este mensaje", "você apagou esta mensagem",
        "vous avez supprimé ce message", "du hast diese nachricht gelöscht", "anda menghapus pesan ini",
    )

    /** Telegram / Signal (rarely shown in notifications, but explicit when it is). */
    val DELETED_GENERIC: Set<String> = setOf("this message was deleted", "you deleted this message")

    /** Instagram: "<name> unsent a message". */
    val INSTAGRAM_ACTIONS: List<String> = listOf("unsent a message")

    /** Messenger: "<name> unsent a message" / "<name> removed a message". */
    val MESSENGER_ACTIONS: List<String> = listOf("unsent a message", "removed a message")

    data class Hit(
        /** Sender the marker names, or null when the marker does not identify one. */
        val sender: String?,
        val isSelf: Boolean,
    )

    /** Whole-text phrase match, e.g. WhatsApp's in-place replacement text. */
    fun matchExact(text: String, phrases: Set<String>, messageSender: String?): Hit? {
        val c = TextRules.comparable(text)
        if (c !in phrases) return null
        return if (c in SELF_PHRASES) Hit(null, isSelf = true) else Hit(messageSender, isSelf = false)
    }

    /**
     * "<name> unsent a message" style markers. The prefix must be empty, "you", or exactly one of [knownNames] (the
     * sender/title the same notification already names), so free text like "lol he unsent a message" never matches.
     */
    fun matchAction(text: String, actions: List<String>, messageSender: String?, knownNames: Collection<String>): Hit? {
        val c = TextRules.comparable(text)
        for (action in actions) {
            if (!c.endsWith(action)) continue
            val prefix = c.removeSuffix(action).trim().trimEnd(':').trim()
            if (prefix.isEmpty()) return Hit(messageSender, isSelf = false)
            if (prefix == "you") return Hit(null, isSelf = true)
            val named = knownNames.firstOrNull { TextRules.comparable(it) == prefix }
            if (named != null) return Hit(named, isSelf = false)
        }
        return null
    }
}
