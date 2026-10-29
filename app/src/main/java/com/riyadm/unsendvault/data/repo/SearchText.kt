package com.riyadm.unsendvault.data.repo

import java.text.Normalizer
import java.util.Locale

/**
 * Text folding and query parsing for the in-memory search over decrypted rows (pure JVM).
 *
 * Matching rule: the query is folded and split into terms; a row matches when EVERY term is a substring of the folded
 * message text, sender or conversation title (each term may come from a different field). Folding is
 * case-insensitive (Locale.ROOT) and diacritic-insensitive for Latin-style combining accents (U+0300–U+036F, after
 * NFD), and drops the zero-width/bidi characters [TextNormalizer] also ignores.
 *
 * Terms are the letter/digit runs of the query (combining marks stay with their base letter), so punctuation such as
 * quotes, `*`, `-` or `%` never has to appear in the text; a query with no letters or digits at all (e.g. "%" or "?!")
 * uses its whitespace-separated tokens literally. Scripts written without spaces (CJK, Thai, …) need no special case
 * because matching is by substring, not by word.
 */
object SearchText {
    private const val COMBINING_FIRST = 0x0300
    private const val COMBINING_LAST = 0x036F

    fun fold(text: String): String {
        var ascii = true
        for (ch in text) {
            if (ch.code >= 0x80) {
                ascii = false
                break
            }
        }
        if (ascii) return text.lowercase(Locale.ROOT)
        val lowered = Normalizer.normalize(text, Normalizer.Form.NFD).lowercase(Locale.ROOT)
        val sb = StringBuilder(lowered.length)
        for (ch in lowered) {
            val c = ch.code
            if (c in COMBINING_FIRST..COMBINING_LAST || isInvisible(c)) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    /** Folded search terms of [query]; empty when the query is null or blank (no text filter). */
    fun terms(query: String?): List<String> {
        val folded = fold(query?.trim().orEmpty())
        if (folded.isBlank()) return emptyList()
        val runs = letterDigitRuns(folded)
        val terms = runs.ifEmpty { folded.split(WHITESPACE).filter { it.isNotEmpty() } }
        return terms.distinct()
    }

    /** True when every term occurs in at least one of [fields] (already folded; nulls ignored). */
    fun matchesAll(terms: List<String>, vararg fields: String?): Boolean =
        terms.all { term -> fields.any { it != null && it.contains(term) } }

    internal fun letterDigitRuns(text: String): List<String> {
        val runs = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isTermChar(cp)) {
                sb.appendCodePoint(cp)
            } else if (sb.isNotEmpty()) {
                runs += sb.toString()
                sb.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) runs += sb.toString()
        return runs
    }

    private val WHITESPACE = Regex("\\s+")

    private fun isTermChar(cp: Int): Boolean = Character.isLetterOrDigit(cp) || when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** Same set as TextNormalizer's INVISIBLE pattern. */
    private fun isInvisible(c: Int): Boolean =
        c in 0x200B..0x200F || c in 0x202A..0x202E || c in 0x2060..0x2064 || c in 0x2066..0x2069 || c == 0xFEFF
}
