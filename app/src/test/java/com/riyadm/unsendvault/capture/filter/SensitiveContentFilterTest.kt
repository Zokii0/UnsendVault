package com.riyadm.unsendvault.capture.filter

import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.filter.SensitiveContentFilter.Match
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.VaultSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveContentFilterTest {
    private val filter = SensitiveContentFilter()

    @Test
    fun otpPositivesAcrossLanguages() {
        val samples = listOf(
            "123456 is your verification code",
            "Your WhatsApp code: 123-456",
            "G-482913 is your Google verification code.",
            "Use 4821 as your login PIN. Do not share it with anyone.",
            "Your OTP is 99812345",
            "Enter 553 201 to verify your account",
            "Tu código de verificación es 583920", // es
            "Seu código é 772341", // pt
            "Votre code de vérification est 991204", // fr
            "Ihr Bestätigungscode lautet 448812", // de
            "Ihr Code: 448812", // de
            "आपका कोड 662291 है", // hi
            "Kode verifikasi Anda adalah 120934", // id
            "رمز التحقق الخاص بك هو 554433", // ar
            "Ваш код подтверждения: 789012", // ru
            "আপনার কোড 341256", // bn
            "2FA code 004213",
        )
        for (s in samples) {
            assertEquals(s, Match.OTP, filter.detect(s))
        }
    }

    @Test
    fun otpNegatives() {
        val samples = listOf(
            "Call me at +1 555 123 4567",
            "My number is 555-1234",
            "Call me on 9876543210 tonight",
            "Meet at 10:30 tomorrow",
            "It costs 1500",
            "Price is \$1500 or €1200",
            "Your order 48213 has shipped",
            "We moved to room 4021",
            "See you in 2026!",
            "Discount of 2500%? no way",
            "Barcode scanner arrived, 1234 units",
            "Pinned the 2048 game for later",
        )
        for (s in samples) {
            assertNull(s, filter.detect(s))
        }
    }

    @Test
    fun paymentCardsUseLuhn() {
        assertEquals(Match.PAYMENT_CARD, filter.detect("card 4111 1111 1111 1111 exp 12/29"))
        assertEquals(Match.PAYMENT_CARD, filter.detect("5500-0000-0000-0004"))
        assertEquals(Match.PAYMENT_CARD, filter.detect("Use 378282246310005 for the booking"))
        // Fails Luhn: left alone.
        assertNull(filter.detect("ref 4111 1111 1111 1112"))
        // Long tracking numbers that fail Luhn are not cards.
        assertNull(filter.detect("Tracking 1234567890123456"))
    }

    @Test
    fun ibanUsesMod97() {
        assertEquals(Match.IBAN, filter.detect("Send it to DE89 3704 0044 0532 0130 00 please"))
        assertEquals(Match.IBAN, filter.detect("IBAN GB82WEST12345698765432"))
        assertEquals(Match.IBAN, filter.detect("GB82 WEST 1234 5698 7654 32 OK THANKS"))
        assertNull(filter.detect("DE89 3704 0044 0532 0130 01"))
        assertNull(filter.detect("ABCD 1234 is the model number"))
    }

    @Test
    fun passwords() {
        assertEquals(Match.PASSWORD, filter.detect("wifi password: hunter2"))
        assertEquals(Match.PASSWORD, filter.detect("pwd=Sup3rS3cret"))
        assertEquals(Match.PASSWORD, filter.detect("The password is tulip42"))
        assertEquals(Match.PASSWORD, filter.detect("contraseña: abc123"))
        assertEquals(Match.PASSWORD, filter.detect("Passwort: geheim"))
        assertNull(filter.detect("I forgot my password again"))
        assertNull(filter.detect("Passwords are hard"))
    }

    @Test
    fun settingsTogglesAreRespected() {
        assertNull(filter.detect("123456 is your verification code", filterOtp = false, filterSensitive = true))
        assertNull(filter.detect("wifi password: hunter2", filterOtp = true, filterSensitive = false))
        assertEquals(Match.OTP, filter.detect("123456 is your verification code", filterOtp = true, filterSensitive = false))
    }

    @Test
    fun applyReplacesWholeTextAndNeverKeepsTheSecret() {
        val message = CapturedMessage(
            packageName = "com.google.android.apps.messaging",
            conversationKey = "name:bank",
            conversationTitle = "Bank",
            isGroup = false,
            sender = "Bank",
            text = "483920 is your verification code. Do not share it.",
            contentKind = ContentKind.TEXT,
            messageTimestamp = 1L,
            hasPreciseTimestamp = true,
        )
        val filtered = filter.apply(message, VaultSettings())
        assertEquals(ContentKind.FILTERED_SENSITIVE, filtered.contentKind)
        assertEquals("One-time code hidden by UnsendVault", filtered.text)
        assertFalse(filtered.text.contains("483920"))

        val card = filter.apply(message.copy(text = "4111111111111111"), VaultSettings())
        assertEquals("Sensitive content hidden by UnsendVault", card.text)

        val untouched = filter.apply(message, VaultSettings(filterOtp = false, filterSensitive = false))
        assertEquals(message, untouched)

        val normal = message.copy(text = "See you at 10:30, table for 4")
        assertEquals(normal, filter.apply(normal, VaultSettings()))
    }

    @Test
    fun systemRedactedMessagesAreNotTouched() {
        val m = CapturedMessage("p", "k", "t", false, null, "Content hidden by Android", ContentKind.SYSTEM_REDACTED, 1L, false)
        assertEquals(m, filter.apply(m, VaultSettings()))
    }

    @Test
    fun luhnAndIbanHelpers() {
        assertTrue(SensitiveContentFilter.luhnValid("4111111111111111"))
        assertFalse(SensitiveContentFilter.luhnValid("4111111111111112"))
        assertFalse(SensitiveContentFilter.luhnValid("0000000000000"))
        assertTrue(SensitiveContentFilter.ibanValid("DE89370400440532013000"))
        assertFalse(SensitiveContentFilter.ibanValid("DE89370400440532013001"))
    }
}
