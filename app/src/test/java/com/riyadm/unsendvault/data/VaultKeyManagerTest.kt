package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.data.security.KeyWrapper
import com.riyadm.unsendvault.data.security.VaultKeyException
import com.riyadm.unsendvault.data.security.VaultKeyManager
import com.riyadm.unsendvault.data.security.VaultKeyState
import com.riyadm.unsendvault.data.security.WrappingKeyMissingException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStoreException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Software AES-GCM stand-in for AndroidKeyStore with failure injection. */
class FakeKeyWrapper : KeyWrapper {
    var key: SecretKey? = null
    var strongBoxFails = false
    var createdWithStrongBox: Boolean? = null
    var transientFailures = 0
    var unwrapCalls = 0

    override fun keyExists() = key != null
    override fun createKey(preferStrongBox: Boolean) {
        if (preferStrongBox && strongBoxFails) throw java.security.ProviderException("StrongBox broken")
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        createdWithStrongBox = preferStrongBox
    }
    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key ?: throw WrappingKeyMissingException("none"))
        return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
    }
    override fun unwrap(blob: ByteArray): ByteArray {
        unwrapCalls++
        if (transientFailures > 0) {
            transientFailures--
            throw KeyStoreException("keystore busy")
        }
        val k = key ?: throw WrappingKeyMissingException("none")
        val ivLen = blob[0].toInt()
        require(blob.size > 1 + ivLen + 16)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 1, ivLen))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }
    override fun deleteKey() {
        key = null
    }
}

class VaultKeyManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val wrapper = FakeKeyWrapper()
    private val file get() = File(tmp.root, "nobackup/vault.key")
    private fun manager() = VaultKeyManager(file, wrapper, SecureRandom(), retryDelaysMs = listOf(1, 1), sleeper = {})

    private fun expect(reason: VaultKeyState.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: VaultKeyException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun firstRun_createsKey_thenReturnsSamePassphrase() {
        val p1 = manager().loadOrCreatePassphrase(databaseExists = false)
        assertTrue(file.exists())
        assertEquals(67, p1.size)
        val s = String(p1, Charsets.US_ASCII)
        assertTrue(s.matches(Regex("x'[0-9a-f]{64}'")))
        assertEquals(false, wrapper.createdWithStrongBox) // TEE by default
        // The raw key never appears in the key file.
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(s.substring(2, 20)))
        assertArrayEquals(p1, manager().loadOrCreatePassphrase(databaseExists = true))
    }

    @Test
    fun strongBoxFailure_fallsBackToTee() {
        wrapper.strongBoxFails = true
        VaultKeyManager(file, wrapper, SecureRandom(), retryDelaysMs = listOf(1, 1), sleeper = {}, keyPlacements = listOf(true, false))
            .loadOrCreatePassphrase(false)
        assertEquals(false, wrapper.createdWithStrongBox)
    }

    @Test
    fun keyFileMissingButDatabaseExists_isKeyLost_andDoesNotCreate() {
        expect(VaultKeyState.Reason.KEY_LOST) { manager().loadOrCreatePassphrase(databaseExists = true) }
        assertFalse(file.exists())
    }

    @Test
    fun keystoreWiped_isKeyLost() {
        manager().loadOrCreatePassphrase(false)
        wrapper.key = null
        expect(VaultKeyState.Reason.KEY_LOST) { manager().loadOrCreatePassphrase(true) }
        assertTrue("never deletes the key file on its own", file.exists())
    }

    @Test
    fun differentKeystoreKey_isCorrupt() {
        manager().loadOrCreatePassphrase(false)
        wrapper.createKey(false) // key replaced → tag mismatch
        expect(VaultKeyState.Reason.KEY_FILE_CORRUPT) { manager().loadOrCreatePassphrase(true) }
    }

    @Test
    fun malformedFile_isCorrupt() {
        manager().loadOrCreatePassphrase(false)
        file.writeBytes(byteArrayOf(9, 1, 2, 3))
        expect(VaultKeyState.Reason.KEY_FILE_CORRUPT) { manager().loadOrCreatePassphrase(true) }
        file.writeBytes(byteArrayOf(1, 12, 0))
        expect(VaultKeyState.Reason.KEY_FILE_CORRUPT) { manager().loadOrCreatePassphrase(true) }
    }

    @Test
    fun transientKeystoreErrors_areRetried_thenReported() {
        val p = manager().loadOrCreatePassphrase(false)
        wrapper.transientFailures = 2
        assertArrayEquals(p, manager().loadOrCreatePassphrase(true))
        wrapper.transientFailures = 10
        expect(VaultKeyState.Reason.KEYSTORE_ERROR) { manager().loadOrCreatePassphrase(true) }
    }

    @Test
    fun destroy_removesFileAndKey_thenFreshKeyIsDifferent() {
        val p1 = manager().loadOrCreatePassphrase(false)
        manager().destroy()
        assertFalse(file.exists())
        assertFalse(wrapper.keyExists())
        val p2 = manager().loadOrCreatePassphrase(false)
        assertFalse(p1.contentEquals(p2))
    }

    @Test
    fun rawKeyFormat() {
        val raw = ByteArray(32) { it.toByte() }
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", String(VaultKeyManager.toSqlCipherRawKey(raw)))
    }
}
