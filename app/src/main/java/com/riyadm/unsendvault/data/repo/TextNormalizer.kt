package com.riyadm.unsendvault.data.repo

import java.text.Normalizer

/** Normalization used for message identity (imprecise timestamps) and "same text" comparisons. */
object TextNormalizer {
    // Zero-width and bidi control characters that apps sprinkle into notification text inconsistently.
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(INVISIBLE, "")
            .replace(WHITESPACE, " ")
            .trim()

    fun senderKey(sender: String?): String = sender?.let(::normalize).orEmpty()

    /** Truncates to at most [max] chars without splitting a surrogate pair. */
    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }
}
