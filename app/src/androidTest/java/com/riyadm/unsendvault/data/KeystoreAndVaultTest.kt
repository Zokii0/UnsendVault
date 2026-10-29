package com.riyadm.unsendvault.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.security.AndroidKeystoreKeyWrapper
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.VaultKeyManager
import com.riyadm.unsendvault.data.security.VaultKeyState
import com.riyadm.unsendvault.data.security.WrappingKeyMissingException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class KeystoreAndVaultTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "unsendvault.test.kek"
    private val keyFile = File(context.noBackupFilesDir, "uv-test-vault.key")
    private val dbName = "uv-keystore-e2e-test.db"
    private val legacyName = "uv-keystore-e2e-legacy-test.db"
    private val noticeFile = File(context.noBackupFilesDir, "uv-test-legacy-notice")

    /** Always pass test file names: the defaults are the real vault's files. */
    private fun holder() = DataModule.encryptedHolder(context, dbName, keyFile, AndroidKeystoreKeyWrapper(alias), legacyName, noticeFile)

    @After
    fun cleanup() {
        runCatching { AndroidKeystoreKeyWrapper(alias).deleteKey() }
        keyFile.delete()
        noticeFile.delete()
        context.deleteDatabase(dbName)
        context.deleteDatabase(legacyName)
    }

    @Test
    fun keystoreWrapUnwrap_roundTrip_tamper_delete() {
        val wrapper = AndroidKeystoreKeyWrapper(alias)
        wrapper.createKey(preferStrongBox = false) // production placement (TEE); see VaultKeyManager.keyPlacements
        assertTrue(wrapper.keyExists())
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val blob = wrapper.wrap(secret)
        assertFalse(blob.contentEquals(secret))
        assertArrayEquals(secret, wrapper.unwrap(blob))
        // Randomized IVs.
        assertFalse(blob.contentEquals(wrapper.wrap(secret)))
        try {
            wrapper.unwrap(blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
            fail("tampered blob accepted")
        } catch (expected: AEADBadTagException) {
        }
        wrapper.deleteKey()
        assertFalse(wrapper.keyExists())
        try {
            wrapper.unwrap(blob)
            fail()
        } catch (expected: WrappingKeyMissingException) {
        }
    }

    @Test
    fun keyManager_withRealKeystore_isStable_andDerivesSameKeys() {
        val manager = VaultKeyManager(keyFile, AndroidKeystoreKeyWrapper(alias))
        val p1 = manager.loadOrCreateRootSecret(databaseExists = false)
        val p2 = VaultKeyManager(keyFile, AndroidKeystoreKeyWrapper(alias)).loadOrCreateRootSecret(databaseExists = true)
        assertArrayEquals(p1, p2)
        assertEquals(32, p1.size)
        val blob = FieldCipher.fromRootSecret(p1).encrypt("x", FieldCipher.Field.MESSAGE_TEXT, "c:1")
        assertEquals("x", FieldCipher.fromRootSecret(p2).decryptOrNull(blob, FieldCipher.Field.MESSAGE_TEXT, "c:1"))
    }

    @Test
    fun encryptedVault_endToEnd_keystoreWipe_isReported_thenResetRecovers() = runBlocking {
        val repo = RoomVaultRepository(holder())
        assertEquals(1, repo.ingest(DeviceFx.parsed(DeviceFx.msg("persisted words")), DeviceFx.T0).inserted)
        assertEquals(VaultKeyState.Ready, repo.keyState.value)
        assertEquals(1, repo.observeMessages(MessageQuery(text = "persisted")).first().size)

        // Simulate a wiped Keystore, then a new process opening the vault.
        AndroidKeystoreKeyWrapper(alias).deleteKey()
        val repo2 = RoomVaultRepository(holder())
        assertTrue(repo2.observeMessages(MessageQuery()).first().isEmpty())
        val state = repo2.keyState.value as VaultKeyState.Unavailable
        assertEquals(VaultKeyState.Reason.KEY_LOST, state.reason)
        assertTrue("database is kept until the user resets", context.getDatabasePath(dbName).exists())

        assertEquals(VaultKeyState.Ready, repo2.resetVault())
        assertEquals(0, repo2.observeStats().first().totalMessages)
        assertEquals(1, repo2.ingest(DeviceFx.parsed(DeviceFx.msg("fresh start")), DeviceFx.T0).inserted)
    }

    @Test
    fun legacySqlCipherArchive_isReplaced_keyKept_noticeRaised() = runBlocking {
        VaultKeyManager(keyFile, AndroidKeystoreKeyWrapper(alias)).loadOrCreateRootSecret(databaseExists = false)
        val keyBytes = keyFile.readBytes()
        val legacy = context.getDatabasePath(legacyName).apply { parentFile?.mkdirs() }
        legacy.writeBytes(ByteArray(8192).also(SecureRandom()::nextBytes)) // SQLCipher page: no SQLite header
        File(legacy.path + "-wal").writeBytes(ByteArray(1024).also(SecureRandom()::nextBytes))

        val repo = RoomVaultRepository(holder())
        assertEquals(1, repo.ingest(DeviceFx.parsed(DeviceFx.msg("new vault")), DeviceFx.T0).inserted)
        assertEquals(VaultKeyState.Ready, repo.keyState.value)
        assertTrue(repo.legacyArchiveDiscarded.value)
        assertFalse(legacy.exists())
        assertFalse(File(legacy.path + "-wal").exists())
        assertArrayEquals(keyBytes, keyFile.readBytes())
        repo.dismissLegacyArchiveNotice()
        assertFalse(noticeFile.exists())
    }
}
