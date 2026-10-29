package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.data.security.FieldCipher
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
    fun firstRun_createsKey_thenReturnsSameRootSecret() {
        val p1 = manager().loadOrCreateRootSecret(databaseExists = false)
        assertTrue(file.exists())
        assertEquals(32, p1.size)
        assertFalse(p1.all { it == 0.toByte() })
        assertEquals(false, wrapper.createdWithStrongBox) // TEE by default
        // The raw key never appears in the key file.
        val stored = file.readBytes()
        assertFalse((0..stored.size - 16).any { i -> stored.copyOfRange(i, i + 16).contentEquals(p1.copyOf(16)) })
        assertArrayEquals(p1, manager().loadOrCreateRootSecret(databaseExists = true))
    }

    @Test
    fun strongBoxFailure_fallsBackToTee() {
        wrapper.strongBoxFails = true
        VaultKeyManager(file, wrapper, SecureRandom(), retryDelaysMs = listOf(1, 1), sleeper = {}, keyPlacements = listOf(true, false))
            .loadOrCreateRootSecret(false)
        assertEquals(false, wrapper.createdWithStrongBox)
    }

    @Test
    fun keyFileMissingButDatabaseExists_isKeyLost_andDoesNotCreate() {
        expect(VaultKeyState.Reason.KEY_LOST) { manager().loadOrCreateRootSecret(databaseExists = true) }
        assertFalse(file.exists())
    }

    @Test
    fun keystoreWiped_isKeyLost() {
        manager().loadOrCreateRootSecret(false)
        wrapper.key = null
        expect(VaultKeyState.Reason.KEY_LOST) { manager().loadOrCreateRootSecret(true) }
        assertTrue("never deletes the key file on its own", file.exists())
    }

    @Test
    fun differentKeystoreKey_isCorrupt() {
        manager().loadOrCreateRootSecret(false)
        wrapper.createKey(false) // key replaced → tag mismatch
        expect(VaultKeyState.Reason.KEY_FILE_CORRUPT) { manager().loadOrCreateRootSecret(true) }
    }

    @Test
    fun malformedFile_isCorrupt() {
        manager().loadOrCreateRootSecret(false)
        file.writeBytes(byteArrayOf(9, 1, 2, 3))
        expect(VaultKeyState.Reason.KEY_FILE_CORRUPT) { manager().loadOrCreateRootSecret(true) }
        file.writeBytes(byteArrayOf(1, 12, 0))
        expect(VaultKeyState.Reason.KEY_FILE_CORRUPT) { manager().loadOrCreateRootSecret(true) }
    }

    @Test
    fun transientKeystoreErrors_areRetried_thenReported() {
        val p = manager().loadOrCreateRootSecret(false)
        wrapper.transientFailures = 2
        assertArrayEquals(p, manager().loadOrCreateRootSecret(true))
        wrapper.transientFailures = 10
        expect(VaultKeyState.Reason.KEYSTORE_ERROR) { manager().loadOrCreateRootSecret(true) }
    }

    @Test
    fun destroy_removesFileAndKey_thenFreshKeyIsDifferent() {
        val p1 = manager().loadOrCreateRootSecret(false)
        manager().destroy()
        assertFalse(file.exists())
        assertFalse(wrapper.keyExists())
        val p2 = manager().loadOrCreateRootSecret(false)
        assertFalse(p1.contentEquals(p2))
    }

    @Test
    fun sameRootSecret_derivesSameKeys_acrossInstances() {
        val cipher1 = FieldCipher.fromRootSecret(manager().loadOrCreateRootSecret(false))
        val cipher2 = FieldCipher.fromRootSecret(manager().loadOrCreateRootSecret(true))
        val blob = cipher1.encrypt("persisted", FieldCipher.Field.MESSAGE_TEXT, "c:1")
        assertEquals("persisted", cipher2.decryptOrNull(blob, FieldCipher.Field.MESSAGE_TEXT, "c:1"))
        assertEquals(cipher1.blindIndex("x", FieldCipher.IndexDomain.TEXT), cipher2.blindIndex("x", FieldCipher.IndexDomain.TEXT))
    }
}
