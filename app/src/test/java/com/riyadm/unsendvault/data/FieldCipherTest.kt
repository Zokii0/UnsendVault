package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.FieldCipher.Field
import com.riyadm.unsendvault.data.security.FieldCipher.IndexDomain
import com.riyadm.unsendvault.data.security.Hkdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM: field encryption, blind indexes and the HKDF they are derived with. */
class FieldCipherTest {
    private val root = ByteArray(32) { (it * 7 + 3).toByte() }
    private val cipher = FieldCipher.fromRootSecret(root)
    private val owner = FieldCipher.forMessage(42)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun hkdf_rfc5869_testCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            Hkdf.expand(prk, info, 42),
        )
        assertArrayEquals(Hkdf.expand(prk, info, 42), Hkdf.derive(ikm, salt, info, 42))
    }

    @Test
    fun hkdf_rfc5869_testCase3_zeroLengthSaltAndInfo() {
        val ikm = ByteArray(22) { 0x0b }
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            Hkdf.derive(ikm, null, ByteArray(0), 42),
        )
    }

    @Test
    fun roundTrip_unicode_empty_andLong() {
        listOf("", "hi", "Café 😀 我们明天见面吧 مرحبا", "x".repeat(100_000)).forEach { s ->
            assertEquals(s, cipher.decryptOrNull(cipher.encrypt(s, Field.MESSAGE_TEXT, owner), Field.MESSAGE_TEXT, owner))
        }
        assertNull(cipher.encryptOrNull(null, Field.MESSAGE_SENDER, owner))
        assertNull(cipher.decryptNullable(null, Field.MESSAGE_SENDER, owner))
    }

    @Test
    fun blobFormat_versionNonceTag_andNoPlaintext() {
        val blob = cipher.encrypt("PLAINTEXT_MARKER", Field.MESSAGE_TEXT, owner)
        assertEquals(FieldCipher.VERSION, blob[0])
        assertEquals(1 + 12 + "PLAINTEXT_MARKER".length + 16, blob.size)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("PLAINTEXT"))
    }

    @Test
    fun nonces_areRandom_soEqualPlaintextsGiveDifferentCiphertexts() {
        val blobs = (1..200).map { cipher.encrypt("same", Field.MESSAGE_TEXT, owner) }
        assertEquals(200, blobs.map { it.toList() }.toSet().size)
        assertEquals(200, blobs.map { it.copyOfRange(1, 13).toList() }.toSet().size)
    }

    @Test
    fun wrongKey_failsAndYieldsPlaceholder() {
        val blob = cipher.encrypt("secret", Field.MESSAGE_TEXT, owner)
        val other = FieldCipher.random()
        assertNull(other.decryptOrNull(blob, Field.MESSAGE_TEXT, owner))
        assertEquals(FieldCipher.UNREADABLE, other.decrypt(blob, Field.MESSAGE_TEXT, owner))
        // Same root secret → same keys (a new process derives identical keys).
        assertEquals("secret", FieldCipher.fromRootSecret(root.copyOf()).decryptOrNull(blob, Field.MESSAGE_TEXT, owner))
    }

    @Test
    fun aad_preventsSwappingBetweenColumnsOrOwners() {
        val text = cipher.encrypt("message body", Field.MESSAGE_TEXT, owner)
        assertNull(cipher.decryptOrNull(text, Field.MESSAGE_SENDER, owner))
        assertNull(cipher.decryptOrNull(text, Field.REVISION_TEXT, owner))
        assertNull(cipher.decryptOrNull(text, Field.CONVERSATION_PREVIEW, owner))
        // Same column, another conversation.
        assertNull(cipher.decryptOrNull(text, Field.MESSAGE_TEXT, FieldCipher.forMessage(43)))
        val title = cipher.encrypt("Alice", Field.CONVERSATION_TITLE, FieldCipher.forConversation("a.b", "idx1"))
        assertNull(cipher.decryptOrNull(title, Field.CONVERSATION_TITLE, FieldCipher.forConversation("a.b", "idx2")))
        assertNull(cipher.decryptOrNull(title, Field.CONVERSATION_TITLE, FieldCipher.forConversation("a.c", "idx1")))
    }

    @Test
    fun corruptBlobs_neverThrow_andGivePlaceholder() {
        val good = cipher.encrypt("hello", Field.MESSAGE_TEXT, owner)
        val corrupt = listOf(
            ByteArray(0),
            byteArrayOf(1),
            good.copyOf(20),
            good.copyOf().also { it[0] = 9 },
            good.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() },
            good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() },
            "plain old text from somewhere".toByteArray(),
        )
        var failures = 0
        val counting = FieldCipher.fromRootSecret(root, onDecryptFailure = { _, _ -> failures++ })
        corrupt.forEach { blob ->
            assertEquals(FieldCipher.UNREADABLE, counting.decrypt(blob, Field.MESSAGE_TEXT, owner))
        }
        assertEquals(corrupt.size, failures)
        // The cipher still works after failures (per-thread Cipher state is re-initialized on every call).
        assertEquals("hello", counting.decrypt(good, Field.MESSAGE_TEXT, owner))
    }

    @Test
    fun blindIndex_isStable_distinct_domainSeparated_andKeyed() {
        val a = cipher.blindIndex("Hello there", IndexDomain.TEXT)
        assertEquals(a, cipher.blindIndex("Hello there", IndexDomain.TEXT))
        assertEquals(a, FieldCipher.fromRootSecret(root.copyOf()).blindIndex("Hello there", IndexDomain.TEXT))
        assertEquals(22, a.length) // 16 bytes, base64url without padding
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]{22}")))
        assertNotEquals(a, cipher.blindIndex("Hello there!", IndexDomain.TEXT))
        assertNotEquals(a, cipher.blindIndex("hello there", IndexDomain.TEXT))
        assertNotEquals(a, cipher.blindIndex("Hello there", IndexDomain.SENDER))
        assertNotEquals(a, FieldCipher.random().blindIndex("Hello there", IndexDomain.TEXT))
        // No collisions across many distinct values.
        val many = (0 until 20_000).map { cipher.blindIndex("message $it", IndexDomain.TEXT) }
        assertEquals(20_000, many.toSet().size)
        // The empty sender key has an index too (identity uses it for messages without a sender).
        assertEquals(22, cipher.blindIndex("", IndexDomain.SENDER).length)
    }

    @Test
    fun keyCheck_detectsDifferentKey() {
        val check = cipher.keyCheckBlob()
        assertTrue(cipher.verifyKeyCheck(check))
        assertFalse(FieldCipher.random().verifyKeyCheck(check))
    }

    @Test
    fun cipher_isThreadSafe() {
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..8).map { t ->
            Thread {
                repeat(500) { i ->
                    val s = "t$t-$i"
                    val o = FieldCipher.forMessage(t.toLong())
                    if (cipher.decryptOrNull(cipher.encrypt(s, Field.MESSAGE_TEXT, o), Field.MESSAGE_TEXT, o) != s) errors.incrementAndGet()
                    cipher.blindIndex(s, IndexDomain.TEXT)
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals(0, errors.get())
    }
}
