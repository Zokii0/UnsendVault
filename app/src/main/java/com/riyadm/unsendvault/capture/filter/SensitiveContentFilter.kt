package com.riyadm.unsendvault.capture.filter

import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.parser.CapturePlaceholders
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.VaultSettings

/**
 * Pure-Kotlin detector for secrets that must never reach storage. When a pattern matches, the *whole* message text is
 * replaced with a placeholder and the message becomes [ContentKind.FILTERED_SENSITIVE]; the secret is not kept anywhere.
 *
 * - One-time codes ([VaultSettings.filterOtp]): a 4–8 digit code (also "123-456", "123 456", "G-123456") within
 *   [OTP_PROXIMITY] characters of a keyword (code, OTP, verification, PIN, 2FA, "is your", "do not share", and
 *   keywords in es/pt/fr/de/hi/id/ar/ru/bn). Numbers without a keyword nearby (phone numbers, times, prices, order
 *   numbers) are left alone; digits that are part of a longer spaced/dashed sequence (phone numbers) never count.
 * - Payment cards ([VaultSettings.filterSensitive]): 13–19 digits (spaces/dashes allowed) that pass the Luhn check.
 * - IBAN: country code + check digits + BBAN that passes the ISO 13616 mod-97 check.
 * - Passwords: "password: …", "pwd = …", "my password is …" and translations.
 */
class SensitiveContentFilter(private val placeholders: CapturePlaceholders = CapturePlaceholders()) {

    enum class Match { OTP, PAYMENT_CARD, IBAN, PASSWORD }

    fun detect(text: String, filterOtp: Boolean = true, filterSensitive: Boolean = true): Match? {
        if (text.isBlank()) return null
        if (filterSensitive) {
            if (PASSWORD.containsMatchIn(text)) return Match.PASSWORD
            if (containsIban(text)) return Match.IBAN
            if (containsCard(text)) return Match.PAYMENT_CARD
        }
        if (filterOtp && containsOtp(text)) return Match.OTP
        return null
    }

    /** Returns [message] unchanged, or a copy with its text replaced by a placeholder when a secret is detected. */
    fun apply(message: CapturedMessage, settings: VaultSettings): CapturedMessage {
        if (!settings.filterOtp && !settings.filterSensitive) return message
        if (message.contentKind == ContentKind.SYSTEM_REDACTED || message.contentKind == ContentKind.FILTERED_SENSITIVE) {
            return message
        }
        val match = detect(message.text, settings.filterOtp, settings.filterSensitive) ?: return message
        val placeholder = if (match == Match.OTP) placeholders.otpHidden else placeholders.sensitiveHidden
        return message.copy(text = placeholder, contentKind = ContentKind.FILTERED_SENSITIVE)
    }

    // --- OTP ----------------------------------------------------------------------------------------------------------

    private fun containsOtp(text: String): Boolean {
        val codes = OTP_TOKEN.findAll(text).map { it.range }.toList()
        if (codes.isEmpty()) return false
        val keywords = OTP_KEYWORD.findAll(text).map { it.range }.toList()
        if (keywords.isEmpty()) return false
        return codes.any { code -> keywords.any { kw -> distance(code, kw) <= OTP_PROXIMITY } }
    }

    private fun distance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }

    // --- Cards / IBAN ---------------------------------------------------------------------------------------------------

    private fun containsCard(text: String): Boolean = CARD_CANDIDATE.findAll(text).any { m ->
        val digits = m.value.filter { it.isDigit() }
        digits.length in 13..19 && luhnValid(digits)
    }

    private fun containsIban(text: String): Boolean = IBAN_CANDIDATE.findAll(text).any { m ->
        // The greedy candidate may run into a following upper-case word, so also try shorter prefixes — but only at
        // the space-separated group boundaries the text itself shows, to keep the mod-97 check meaningful.
        val groups = m.value.split(' ')
        (groups.size downTo 1).any { n -> ibanValid(groups.subList(0, n).joinToString("")) }
    }

    companion object {
        const val OTP_PROXIMITY = 60

        private val OTP_KEYWORD = Regex(
            "(?<![\\p{L}\\p{M}])(?:otp|2fa|mfa|pin|passcode|pass code|one[- ]time|verif\\w*|code|codes|auth\\w*|" +
                "is your|do not share|don'?t share|never share|security code|login code|" +
                "código|codigo|verificación|verificação|senha|" +
                "bestätigungscode|sicherheitscode|kode|verifikasi|" +
                "код|пароль|подтверждения)(?![\\p{L}\\p{M}])" +
                // Scripts where word boundaries are unreliable (Devanagari, Bengali, Arabic, CJK): plain substrings.
                "|कोड|ओटीपी|কোড|ওটিপি" +
                "|رمز|التحقق|验证码|認証コード|인증번호",
            setOf(RegexOption.IGNORE_CASE),
        )

        /**
         * A candidate code: "G-123456", "123-456", "123 456" or 4–8 digits, not glued to letters/digits, currency
         * symbols, decimals, times or percentages, and not part of a longer spaced/dashed digit sequence.
         */
        private val OTP_TOKEN = Regex(
            "(?<![\\p{L}\\p{N}\$€£₹¥.,:/+#-])(?<!\\d[ -])" +
                "(?:[A-Z]{1,4}-\\d{4,8}|\\d{3}[ -]\\d{3}|\\d{4,8})" +
                "(?![\\p{L}\\p{N}%\$€£₹¥]|[.,:/]\\d|[ -]\\d)",
        )

        private val CARD_CANDIDATE = Regex("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])")

        private val IBAN_CANDIDATE = Regex("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}")

        private val PASSWORD = Regex(
            "(?<![\\p{L}])(?:password|passwd|pwd|pass word|contraseña|contrasena|senha|mot de passe|passwort|" +
                "kata sandi|пароль)(?![\\p{L}])\\s*(?::|=|：|\\bis\\b|\\bes\\b|é(?=\\s)|\\bist\\b)\\s*\\S+",
            setOf(RegexOption.IGNORE_CASE),
        )

        fun luhnValid(digits: String): Boolean {
            var sum = 0
            var double = false
            for (i in digits.length - 1 downTo 0) {
                var d = digits[i] - '0'
                if (d !in 0..9) return false
                if (double) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
                double = !double
            }
            return sum % 10 == 0 && digits.any { it != '0' }
        }

        fun ibanValid(iban: String): Boolean {
            if (iban.length !in 15..34) return false
            if (!iban.all { it.isDigit() || it in 'A'..'Z' }) return false
            val rearranged = iban.substring(4) + iban.substring(0, 4)
            var remainder = 0
            for (ch in rearranged) {
                val value = if (ch.isDigit()) ch - '0' else ch - 'A' + 10
                remainder = if (value >= 10) (remainder * 100 + value) % 97 else (remainder * 10 + value) % 97
            }
            return remainder == 1
        }
    }
}
