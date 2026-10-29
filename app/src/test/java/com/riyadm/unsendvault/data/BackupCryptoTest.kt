package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.data.backup.BackupCodec
import com.riyadm.unsendvault.data.backup.BackupCrypto
import com.riyadm.unsendvault.data.backup.BackupCryptoException
import com.riyadm.unsendvault.data.backup.BackupFormatException
import com.riyadm.unsendvault.data.backup.BackupPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class BackupCryptoTest {
    private val crypto = BackupCrypto(iterations = 1_000, minIterations = 1_000)
    private val pw = "correct horse battery staple".toCharArray()
    private val plain = ("hello backup ".repeat(5_000)).toByteArray()

    private fun decrypt(bytes: ByteArray, password: CharArray = pw) = crypto.decrypt(password, ByteArrayInputStream(bytes))

    private fun expectReason(reason: BackupResult.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: BackupCryptoException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun roundTrip_andHeaderLayout() {
        val enc = crypto.encrypt(pw, plain, createdAt = 42L)
        assertEquals("UVB", String(enc, 0, 3, Charsets.US_ASCII))
        assertEquals(1, enc[3].toInt())
        assertEquals(1, enc[4].toInt())
        assertEquals(BackupCrypto.HEADER_BYTES + plain.size + 16, enc.size)
        assertArrayEquals(plain, decrypt(enc))
    }

    @Test
    fun defaultIterations_roundTrip_unicodePassword() {
        val strong = BackupCrypto()
        assertEquals(310_000, strong.iterations)
        val pass = "pässwörd-密码-🔐".toCharArray()
        val enc = strong.encrypt(pass, plain)
        assertEquals(310_000, java.nio.ByteBuffer.wrap(enc, 5, 4).int)
        assertArrayEquals(plain, strong.decrypt(pass, ByteArrayInputStream(enc)))
    }

    @Test
    fun freshSaltAndNonceEveryTime() {
        val a = crypto.encrypt(pw, plain)
        val b = crypto.encrypt(pw, plain)
        assertFalse(a.copyOfRange(17, 45).contentEquals(b.copyOfRange(17, 45)))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun wrongPassword() = expectReason(BackupResult.Reason.WRONG_PASSWORD) {
        decrypt(crypto.encrypt(pw, plain), "nope".toCharArray())
    }

    @Test
    fun tamperedCiphertextByte() = expectReason(BackupResult.Reason.WRONG_PASSWORD) {
        val enc = crypto.encrypt(pw, plain)
        enc[BackupCrypto.HEADER_BYTES + 100] = (enc[BackupCrypto.HEADER_BYTES + 100].toInt() xor 1).toByte()
        decrypt(enc)
    }

    @Test
    fun tamperedTag() = expectReason(BackupResult.Reason.WRONG_PASSWORD) {
        val enc = crypto.encrypt(pw, plain)
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 0x80).toByte()
        decrypt(enc)
    }

    @Test
    fun headerIsAuthenticated_createdAtTamperFails() = expectReason(BackupResult.Reason.WRONG_PASSWORD) {
        // createdAt does not feed the KDF or the nonce, so only AAD binding can detect this change.
        val enc = crypto.encrypt(pw, plain, createdAt = 1000L)
        enc[16] = (enc[16].toInt() xor 1).toByte()
        decrypt(enc)
    }

    @Test
    fun truncatedFiles() {
        val enc = crypto.encrypt(pw, plain)
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt(ByteArray(0)) }
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt(enc.copyOf(2)) }
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt(enc.copyOf(20)) }
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt(enc.copyOf(BackupCrypto.HEADER_BYTES + 5)) }
        expectReason(BackupResult.Reason.WRONG_PASSWORD) { decrypt(enc.copyOf(enc.size - 1)) }
        expectReason(BackupResult.Reason.WRONG_PASSWORD) { decrypt(enc.copyOf(enc.size / 2)) }
    }

    @Test
    fun versionAndMagicChecks() {
        val enc = crypto.encrypt(pw, plain)
        expectReason(BackupResult.Reason.UNSUPPORTED_VERSION) { decrypt(enc.copyOf().also { it[3] = 2 }) }
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt(enc.copyOf().also { it[3] = 0 }) }
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt(enc.copyOf().also { it[0] = 'X'.code.toByte() }) }
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt("PK\u0003\u0004 not a backup at all".toByteArray()) }
        // Unknown KDF id and implausible iteration counts are rejected before any expensive work.
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt(enc.copyOf().also { it[4] = 9 }) }
        expectReason(BackupResult.Reason.CORRUPT_FILE) { decrypt(enc.copyOf().also { it[5] = 0x7F }) }
        expectReason(BackupResult.Reason.CORRUPT_FILE) {
            decrypt(enc.copyOf().also { it[5] = 0; it[6] = 0; it[7] = 0; it[8] = 1 })
        }
    }

    @Test
    fun codec_roundTrip_newerSchema_andGarbage() {
        val payload = BackupPayload(exportedAt = 5, conversations = emptyList(), messages = emptyList())
        val out = ByteArrayOutputStream()
        BackupCodec.write(payload, out)
        assertEquals(payload, BackupCodec.read(out.toByteArray()))

        fun gz(s: String) = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(s.toByteArray()) } }.toByteArray()
        try {
            BackupCodec.read(gz("""{"schemaVersion":7,"exportedAt":1,"conversations":[],"messages":[]}"""))
            fail()
        } catch (e: BackupFormatException) {
            assertEquals(BackupResult.Reason.UNSUPPORTED_VERSION, e.reason)
        }
        try {
            BackupCodec.read(gz("""{"schemaVersion":9,"totallyDifferent":true}"""))
            fail()
        } catch (e: BackupFormatException) {
            assertEquals(BackupResult.Reason.UNSUPPORTED_VERSION, e.reason)
        }
        listOf(gz("{not json"), gz("""{"schemaVersion":1}"""), "plain bytes".toByteArray()).forEach { bad ->
            try {
                BackupCodec.read(bad)
                fail()
            } catch (e: BackupFormatException) {
                assertEquals(BackupResult.Reason.CORRUPT_FILE, e.reason)
            }
        }
    }
}
