package com.riyadm.unsendvault.data.repo

import androidx.room.RoomRawQuery
import com.riyadm.unsendvault.core.model.MessageQuery
import java.util.Locale

/**
 * Builds the archive list/search SQL for a [MessageQuery].
 *
 * Text search strategy:
 *  - FTS4 (unicode61 tokenizer) prefix match on message text and sender, OR a LIKE on the conversation title. User input
 *    never reaches MATCH verbatim: it is split into letter/digit terms and each term is emitted as a quoted prefix
 *    phrase (`"term*"`), so quotes, `*`, `-`, `:`, `(`, AND/OR/NOT/NEAR cannot change the query structure or cause a
 *    syntax error.
 *  - LIKE substring fallback (text, sender, title) when any term is 1–2 characters, when the query contains scripts
 *    written without spaces (CJK, Thai, …) that the tokenizer can't split into words, or when the query has no
 *    letter/digit terms at all (e.g. "?!"). `%`, `_` and `\` are escaped.
 */
object SearchSql {
    data class Built(val sql: String, val args: List<Any?>) {
        fun toRoomRawQuery(): RoomRawQuery = RoomRawQuery(sql) { stmt ->
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> stmt.bindNull(i + 1)
                    is Long -> stmt.bindLong(i + 1, a)
                    is Int -> stmt.bindLong(i + 1, a.toLong())
                    is String -> stmt.bindText(i + 1, a)
                    else -> error("Unsupported arg ${a::class}")
                }
            }
        }
    }

    private const val MAX_PACKAGES = 500
    private const val MAX_LIMIT = 10_000
    private const val MIN_FTS_TERM = 3

    private val NO_SPACE_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
    )

    fun build(query: MessageQuery): Built {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        val text = query.text?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val fts = ftsMatchExpression(text)
            val like = likePattern(text)
            if (fts != null) {
                where += "(m.id IN (SELECT docid FROM messages_fts WHERE messages_fts MATCH ?) " +
                    "OR c.title LIKE ? ESCAPE '\\')"
                args += fts
                args += like
            } else {
                where += "(m.text LIKE ? ESCAPE '\\' OR m.sender LIKE ? ESCAPE '\\' OR c.title LIKE ? ESCAPE '\\')"
                args += like
                args += like
                args += like
            }
        }
        if (query.packages.isNotEmpty()) {
            val pkgs = query.packages.take(MAX_PACKAGES)
            where += "m.package_name IN (${pkgs.joinToString(",") { "?" }})"
            args.addAll(pkgs)
        }
        query.conversationId?.let {
            where += "m.conversation_id = ?"
            args += it
        }
        query.fromMillis?.let {
            where += "m.message_timestamp >= ?"
            args += it
        }
        query.toMillis?.let {
            where += "m.message_timestamp <= ?"
            args += it
        }
        if (query.favoritesOnly) where += "m.is_favorite = 1"
        if (query.possiblyDeletedOnly) where += "m.status = 'POSSIBLY_DELETED'"

        val sql = buildString {
            append("SELECT m.*, c.app_label AS app_label, c.title AS conversation_title ")
            append("FROM messages m INNER JOIN conversations c ON c.id = m.conversation_id")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY m.message_timestamp DESC, m.id DESC LIMIT ?")
        }
        args += query.limit.coerceIn(0, MAX_LIMIT)
        return Built(sql, args)
    }

    /** Letter/digit runs (combining marks kept with their base letter). */
    fun terms(text: String): List<String> {
        val terms = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isTermChar(cp)) {
                sb.appendCodePoint(cp)
            } else if (sb.isNotEmpty()) {
                terms += sb.toString()
                sb.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) terms += sb.toString()
        return terms
    }

    /** MATCH expression for [text], or null when the LIKE fallback must be used. */
    fun ftsMatchExpression(text: String): String? {
        if (containsNoSpaceScript(text)) return null
        val terms = terms(text)
        if (terms.isEmpty() || terms.any { it.codePointCount(0, it.length) < MIN_FTS_TERM }) return null
        return terms.joinToString(" ") { "\"" + it.lowercase(Locale.ROOT) + "*\"" }
    }

    fun likePattern(text: String): String {
        val escaped = buildString {
            text.forEach { ch ->
                if (ch == '\\' || ch == '%' || ch == '_') append('\\')
                append(ch)
            }
        }
        return "%$escaped%"
    }

    private fun isTermChar(cp: Int): Boolean = Character.isLetterOrDigit(cp) || when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun containsNoSpaceScript(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.UnicodeScript.of(cp) in NO_SPACE_SCRIPTS) return true
            i += Character.charCount(cp)
        }
        return false
    }
}
