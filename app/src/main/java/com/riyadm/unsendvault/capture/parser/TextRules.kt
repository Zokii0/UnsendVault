package com.riyadm.unsendvault.capture.parser

import java.util.Locale

/**
 * Text normalisation plus the noise / call / media-placeholder phrase lists shared by every parser.
 *
 * All phrase matching is done on [comparable] text (invisible characters and leading emoji removed, trailing
 * punctuation dropped, lower-cased) and uses whole-text matches, so a person typing a sentence that merely contains a
 * phrase is never mistaken for app chatter. Lists here only affect what is skipped or labelled; they are never
 * required for a normal message to be captured.
 */
object TextRules {
    /** Maximum characters kept for one message body (the framework itself caps notification text at 5 KB). */
    const val MAX_TEXT_LENGTH = 5_000

    private val INVISIBLE = Regex("[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    private val WHITESPACE = Regex("\\s+")
    private val LEADING_SYMBOLS = Regex("^[\\p{So}\\p{Sk}\\uFE0F\\u200D\\u20E3\\u2298\\s]+")
    private val TRAILING_PUNCTUATION = Regex("[\\s.!\\u3002\\u2026]+$")

    /** Trailing unread counters apps append to titles: "Team (3 messages)", "Team (2)", "Team · 4 new messages". */
    private val TITLE_COUNTERS = listOf(
        Regex("\\s*\\(\\d+\\s+(?:new\\s+|unread\\s+)?messages?\\)\\s*$", RegexOption.IGNORE_CASE),
        Regex("\\s*\\(\\d+\\)\\s*$"),
        Regex("\\s*[\\u00B7\\u2022]\\s*\\d+\\s+(?:new\\s+|unread\\s+)?messages?\\s*$", RegexOption.IGNORE_CASE),
    )

    fun stripInvisible(text: String): String = text.replace(INVISIBLE, "")

    /** Cleans a person/sender name: invisible marks removed, whitespace collapsed. Null when blank. */
    fun cleanName(raw: String?): String? {
        if (raw == null) return null
        return stripInvisible(raw).replace(WHITESPACE, " ").trim().ifEmpty { null }
    }

    /** Cleans a conversation title: like [cleanName] plus trailing unread counters and dangling colons removed. */
    fun cleanTitle(raw: String?): String? {
        var t = cleanName(raw) ?: return null
        repeat(2) { TITLE_COUNTERS.forEach { t = it.replace(t, "") } }
        return t.trimEnd(':', ' ').trim().ifEmpty { null }
    }

    /** Case-insensitive identity of a title/name used inside conversation keys. */
    fun keyOf(raw: String?): String? = cleanTitle(raw)?.lowercase(Locale.ROOT)

    /** Normalised form for exact phrase comparison. */
    fun comparable(text: String): String {
        var t = stripInvisible(text).replace(WHITESPACE, " ").trim()
        t = LEADING_SYMBOLS.replace(t, "")
        t = TRAILING_PUNCTUATION.replace(t, "")
        return t.lowercase(Locale.ROOT)
    }

    fun clampBody(text: String): String =
        if (text.length <= MAX_TEXT_LENGTH) text else text.substring(0, MAX_TEXT_LENGTH)

    // --- Notification categories ------------------------------------------------------------------------------------

    /** Notification.CATEGORY_CALL / CATEGORY_MISSED_CALL — calls are not messages. */
    private val CALL_CATEGORIES = setOf("call", "missed_call")

    /** Categories that never carry chat messages. */
    private val NON_MESSAGE_CATEGORIES = setOf(
        "alarm", "progress", "transport", "service", "sys", "status", "navigation", "stopwatch",
        "location_sharing", "workout", "car_emergency", "car_warning", "car_information",
    )

    const val CATEGORY_MESSAGE = "msg"

    fun isCallCategory(category: String?): Boolean = category != null && category in CALL_CATEGORIES
    fun isNonMessageCategory(category: String?): Boolean = category != null && category in NON_MESSAGE_CATEGORIES

    // --- Noise --------------------------------------------------------------------------------------------------------

    private fun fullMatch(vararg patterns: String): List<Regex> =
        patterns.map { Regex("^(?:$it)$", setOf(RegexOption.IGNORE_CASE)) }

    /** Summary counters and app chatter that carry no message content. Matched against [comparable] text. */
    private val NOISE = fullMatch(
        "\\d+\\s+(?:new\\s+|unread\\s+)?messages?",
        "\\d+\\s+(?:new\\s+|unread\\s+)?messages?\\s+(?:from|in)\\s+\\d+\\s+(?:chats?|conversations?|contacts?|people|groups?)",
        "you have \\d+ (?:new |unread )?messages?",
        "(?:you have )?(?:a )?new messages?",
        "you may have new messages",
        "checking for (?:new )?messages",
        "whatsapp web is currently active",
        "(?:chat )?backup (?:in progress|paused|complete|completed|failed|finished)",
        "backing up(?: \\w+)*",
        "(?:uploading|preparing|restoring) (?:backup|chats|messages|media)(?: .*)?",
        "sending(?: messages?| media| photo| video)?",
        "message not sent(?:\\W.*)?",
        "(?:couldn't|could not|failed to|unable to) send(?:\\W.*)?",
        "not delivered(?:\\W.*)?",
        "(?:.{1,60} )?(?:is|are) typing",
        "typing",
        "(?:.{1,60} )?(?:is|are) recording(?: audio| a voice message| video)?",
        "(?:syncing|connecting|waiting for network|updating|downloading (?:messages|media))",
        "(?:.{1,60} )?reacted .{1,16} to (?:your|a) message(?: .*)?",
        "(?:.{1,60} )?liked (?:your|a) message",
        "tap to (?:view|see|open|load)(?:\\W.*)?",
    )

    private val CALLS = fullMatch(
        "(?:incoming|ongoing|outgoing) (?:voice |video |group |audio )?call(?: from .*)?",
        "(?:\\d+ )?missed (?:voice |video |group |audio )?calls?(?: from .*)?",
        "calling",
        "ringing",
        "call ended",
        "(?:voice|video) call",
    )

    fun isNoise(text: String): Boolean {
        val c = comparable(text)
        return c.isEmpty() || NOISE.any { it.matches(c) }
    }

    fun isCallText(text: String): Boolean {
        val c = comparable(text)
        return CALLS.any { it.matches(c) }
    }

    // --- Media placeholders -------------------------------------------------------------------------------------------

    /** Whole-text placeholders apps show instead of media (compared after leading emoji are stripped). */
    private val MEDIA_EXACT = setOf(
        "photo", "photos", "image", "video", "videos", "gif", "sticker", "voice message", "voice note", "audio",
        "attachment", "document", "file", "location", "live location", "contact", "video message", "view once photo",
        "view once video", "view once message", "poll", "snap", "video note",
        // A few unambiguous translations.
        "foto", "vídeo", "nota de voz", "mensaje de voz", "mensagem de voz", "áudio",
    )

    private val MEDIA_SENT = Regex(
        "^(?:.{1,60} )?sent (?:you )?(?:an? |\\d+ )?(?:photos?|videos?|attachments?|voice messages?|gifs?|stickers?|" +
            "files?|audio|images?|snaps?|reels?|posts?|links?|documents?|locations?)$",
        RegexOption.IGNORE_CASE,
    )

    fun isMediaPlaceholder(text: String): Boolean {
        val c = comparable(text)
        if (c.isEmpty()) return false
        return c in MEDIA_EXACT || MEDIA_SENT.matches(c)
    }

    /** "Name: body" prefix used by InboxStyle lines and group notifications. */
    private val SENDER_PREFIX = Regex("^([^:\\n]{1,50}):\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)

    /** Splits "Alex Rivera: hello" into ("Alex Rivera", "hello"). Null when the text has no such prefix. */
    fun splitSenderPrefix(text: String): Pair<String, String>? {
        val m = SENDER_PREFIX.matchEntire(text) ?: return null
        val name = cleanName(m.groupValues[1]) ?: return null
        // URLs ("https://…") and times ("10:30") are not sender prefixes.
        if (name.any { it == '/' } || name.all { it.isDigit() || it.isWhitespace() }) return null
        return name to m.groupValues[2].trim()
    }
}
