package com.riyadm.unsendvault.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.marker
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.security.VaultDatabaseHolder
import com.riyadm.unsendvault.data.security.VaultKeyManager
import com.riyadm.unsendvault.data.security.VaultKeyState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom

/**
 * The production holder ([DataModule.encryptedHolder]) on a real database file, with the fake Keystore wrapper:
 * nothing sensitive reaches the db/-wal files, the vault reopens with the same key, key loss is reported, and a 1.0.15
 * SQLCipher archive is replaced by a new vault with a one-time notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedVaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "uv-encrypted-test.db"
    private val legacyName = "uv-legacy-test.db"
    private val keyFile get() = File(context.noBackupFilesDir, "uv-test-vault.key")
    private val noticeFile get() = File(context.noBackupFilesDir, "uv-test-notice")
    private val wrapper = FakeKeyWrapper()
    private val holders = mutableListOf<VaultDatabaseHolder>()

    private val secrets = listOf(
        "PLAINTEXT_MARKER_7f3a9c_should_never_hit_disk",
        "ZebraSenderName",
        "SecretConversationTitle",
        "conversation-key-zebra-42",
        "MarkerDeletedEvidence",
        "notif|com.example.chat|tag-zebra",
    )

    private fun repo(): RoomVaultRepository {
        val holder = DataModule.encryptedHolder(context, dbName, keyFile, wrapper, legacyName, noticeFile)
        holders += holder
        return RoomVaultRepository(holder)
    }

    private fun dbFiles(name: String = dbName): List<File> {
        val main = context.getDatabasePath(name)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm"), File(main.path + "-journal")).filter { it.exists() }
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun assertNoPlaintextOnDisk() {
        val files = dbFiles()
        assertTrue(files.isNotEmpty())
        val all = files.associateWith { it.readBytes() }
        for (secret in secrets) {
            for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                val needle = secret.toByteArray(encoding)
                all.forEach { (f, bytes) -> assertEquals("'$secret' ($encoding) found in ${f.name}", -1, bytes.indexOf(needle)) }
            }
        }
    }

    @Before
    @After
    fun clean() {
        runBlocking { holders.forEach { h -> runCatching { h.database()?.close() } } }
        holders.clear()
        context.deleteDatabase(dbName)
        context.deleteDatabase(legacyName)
        keyFile.delete()
        noticeFile.delete()
    }

    @Test
    fun databaseFiles_containNoPlaintext_andVaultReopensWithSameKey() = runBlocking {
        val repo = repo()
        repo.ingest(
            parsed(
                msg("hello ${secrets[0]}", sender = secrets[1], title = secrets[2], key = secrets[3]),
                msg("second message", sender = secrets[1], ts = T0 + 1, title = secrets[2], key = secrets[3]),
                notificationKey = secrets[5],
            ),
            T0,
        )
        repo.ingest(parsed(msg("edited hello ${secrets[0]}", sender = secrets[1], title = secrets[2], key = secrets[3])), T0 + 5)
        repo.ingest(parsed(markers = listOf(marker(secrets[1], T0 + 1, text = secrets[4], key = secrets[3]))), T0 + 10)
        repo.recordRemoval(RemovalEvent(Fx.PKG, secrets[5], RemovalReason.USER_DISMISSED, T0 + 20))
        assertEquals(VaultKeyState.Ready, repo.keyState.value)
        val hits = repo.observeMessages(MessageQuery(text = "plaintext_marker")).first()
        assertEquals(secrets[1], hits.single().sender)
        assertEquals(secrets[2], hits.single().conversationTitle)
        assertEquals(1, hits.single().revisionCount - 1)
        assertEquals(secrets[4], repo.observeMessages(MessageQuery(possiblyDeletedOnly = true)).first().single().deletionEvidence?.markerText)

        // While open (rows may still sit in the WAL) the files contain no plaintext.
        assertNoPlaintextOnDisk()
        // Plain SQLite now: the header is readable, the content is not.
        val header = context.getDatabasePath(dbName).readBytes().copyOf(16)
        assertTrue(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))

        // A second holder (new process) unwraps the same root secret and reads everything back.
        val again = repo()
        assertEquals(2, again.observeStats().first().totalMessages)
        assertEquals(secrets[3], again.observeConversations().first().single().conversationKey)
        assertEquals(VaultKeyState.Ready, again.keyState.value)
        assertFalse(again.legacyArchiveDiscarded.value)
    }

    @Test
    fun keystoreWiped_isKeyLost_thenResetRecovers() = runBlocking {
        val repo = repo()
        assertEquals(1, repo.ingest(parsed(msg("persisted words")), T0).inserted)
        wrapper.key = null // Keystore wiped
        val repo2 = repo()
        assertTrue(repo2.observeMessages(MessageQuery()).first().isEmpty())
        assertEquals(VaultKeyState.Reason.KEY_LOST, (repo2.keyState.value as VaultKeyState.Unavailable).reason)
        assertTrue("database is kept until the user resets", context.getDatabasePath(dbName).exists())
        assertEquals(VaultKeyState.Ready, repo2.resetVault())
        assertEquals(0, repo2.observeStats().first().totalMessages)
        assertEquals(1, repo2.ingest(parsed(msg("fresh start")), T0).inserted)
    }

    @Test
    fun differentRootSecret_failsKeyCheck_isKeyLost_neverShowsGarbage() = runBlocking {
        repo().ingest(parsed(msg("original")), T0)
        // Replace the key file with a different secret (e.g. restored from elsewhere): unwrap succeeds, key check fails.
        keyFile.delete()
        VaultKeyManager(keyFile, wrapper).loadOrCreateRootSecret(databaseExists = false)
        val repo2 = repo()
        assertTrue(repo2.observeMessages(MessageQuery()).first().isEmpty())
        assertEquals(VaultKeyState.Reason.KEY_LOST, (repo2.keyState.value as VaultKeyState.Unavailable).reason)
    }

    @Test
    fun legacySqlCipherArchive_isDiscarded_keyKept_noticeShownOnceUntilDismissed() = runBlocking {
        // 1.0.15 left an encrypted SQLCipher file (no SQLite header) and its key file.
        VaultKeyManager(keyFile, wrapper).loadOrCreateRootSecret(databaseExists = false)
        val keyBytes = keyFile.readBytes()
        val legacy = context.getDatabasePath(legacyName).apply { parentFile!!.mkdirs() }
        legacy.writeBytes(ByteArray(8192).also(SecureRandom()::nextBytes))
        File(legacy.path + "-wal").writeBytes(ByteArray(4096).also(SecureRandom()::nextBytes))

        val repo = repo()
        assertEquals(1, repo.ingest(parsed(msg("first message in the new vault")), T0).inserted)
        assertEquals(VaultKeyState.Ready, repo.keyState.value)
        assertTrue(repo.legacyArchiveDiscarded.value)
        assertFalse(legacy.exists())
        assertFalse(File(legacy.path + "-wal").exists())
        assertTrue("key file is kept", keyBytes.contentEquals(keyFile.readBytes()))

        // Still shown after a restart, until dismissed.
        val restarted = repo()
        restarted.observeStats().first()
        assertTrue(restarted.legacyArchiveDiscarded.value)
        restarted.dismissLegacyArchiveNotice()
        assertFalse(restarted.legacyArchiveDiscarded.value)
        val third = repo()
        assertEquals(1, third.observeStats().first().totalMessages)
        assertFalse(third.legacyArchiveDiscarded.value)
    }
}
