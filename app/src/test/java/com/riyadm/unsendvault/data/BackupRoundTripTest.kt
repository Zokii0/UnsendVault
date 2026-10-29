package com.riyadm.unsendvault.data

import android.net.Uri
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.data.Fx.MIN
import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.marker
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import com.riyadm.unsendvault.data.backup.BackupCrypto
import com.riyadm.unsendvault.data.backup.BackupManagerImpl
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.security.FieldCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest : RoomTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = InMemorySettingsRepository()
    private val crypto = BackupCrypto(iterations = 1_000, minIterations = 1_000)

    private fun manager(r: RoomVaultRepository) =
        BackupManagerImpl(context.contentResolver, r, settings, crypto, Dispatchers.IO, clock = { 777L })

    private suspend fun seed() {
        repo.ingest(parsed(msg("v1"), msg("bye", sender = "Bob", ts = T0 + MIN), notificationKey = "k1"), T0)
        repo.ingest(parsed(msg("v2 edited")), T0 + 10)
        repo.ingest(parsed(msg("group hi", key = "g", title = "Team", isGroup = true, sender = "Eve", precise = false)), T0)
        repo.ingest(parsed(markers = listOf(marker("Bob", T0 + MIN))), T0 + 20)
        repo.recordRemoval(RemovalEvent(Fx.PKG, "k1", RemovalReason.USER_DISMISSED, T0 + 30))
        val fav = repo.observeMessages(MessageQuery(text = "group")).first().single()
        repo.setFavorite(fav.id, true)
    }

    private suspend fun snapshot(r: RoomVaultRepository) = r.observeMessages(MessageQuery()).first()
        .map { listOf(it.text, it.sender, it.messageTimestamp, it.isFavorite, it.status, it.deletionEvidence, it.revisionCount, it.contentKind, it.lastRemovalReason, it.conversationTitle) }
        .sortedBy { it.toString() }

    @Test
    fun export_thenMergeIntoEmpty_thenIdempotentReimport_thenReplace() = runBlocking {
        seed()
        val file = File(tmp.root, "backup.uvb")
        val uri = Uri.fromFile(file)
        val password = "pw-1234".toCharArray()
        val exported = manager(repo).export(uri, password)
        assertEquals(BackupResult.Success(messageCount = 3, conversationCount = 2), exported)
        assertTrue("password zeroed", password.all { it == '\u0000' })
        assertEquals(777L, settings.current.lastExportAt)
        val bytes = file.readBytes()
        assertEquals("UVB", String(bytes, 0, 3))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("edited"))

        // Import into a second, empty vault.
        val db2 = VaultDatabase.build(context, null, FieldCipher.random(), allowMainThreadQueries = true)
        try {
            val repo2 = RoomVaultRepository(db2)
            assertEquals(BackupResult.Success(3, 2), manager(repo2).import(uri, "pw-1234".toCharArray(), ImportMode.MERGE))
            assertEquals(snapshot(repo), snapshot(repo2))
            val edited = repo2.observeMessages(MessageQuery(text = "edited")).first().single()
            assertEquals(listOf("v1"), repo2.observeMessage(edited.id).first()!!.revisions.map { it.text })
            val deleted = repo2.observeMessages(MessageQuery(possiblyDeletedOnly = true)).first().single()
            assertEquals("bye", deleted.text)
            assertEquals(MessageStatus.POSSIBLY_DELETED, deleted.status)

            // Re-importing the same file (twice) changes nothing.
            manager(repo2).import(uri, "pw-1234".toCharArray(), ImportMode.MERGE)
            manager(repo2).import(uri, "pw-1234".toCharArray(), ImportMode.MERGE)
            assertEquals(snapshot(repo), snapshot(repo2))
            assertEquals(1, db2.dao().allRevisions().size)
            assertEquals(2, repo2.observeStats().first().totalConversations)

            // REPLACE discards data that is not in the backup.
            repo2.ingest(parsed(msg("only in vault 2", key = "zzz")), T0)
            assertEquals(4, repo2.observeStats().first().totalMessages)
            assertEquals(BackupResult.Success(3, 2), manager(repo2).import(uri, "pw-1234".toCharArray(), ImportMode.REPLACE))
            assertEquals(snapshot(repo), snapshot(repo2))
        } finally {
            db2.close()
        }

        // Merging back into the source vault is also a no-op.
        manager(repo).import(uri, "pw-1234".toCharArray(), ImportMode.MERGE)
        assertEquals(3, repo.observeStats().first().totalMessages)
        assertEquals(1, db.dao().allRevisions().size)
    }

    @Test
    fun merge_combinesNewerEditFavoriteAndRevisions() = runBlocking {
        repo.ingest(parsed(msg("original")), T0)
        val uri = Uri.fromFile(File(tmp.root, "b.uvb"))
        manager(repo).export(uri, "pw".toCharArray())

        val db2 = VaultDatabase.build(context, null, FieldCipher.random(), allowMainThreadQueries = true)
        try {
            val repo2 = RoomVaultRepository(db2)
            repo2.ingest(parsed(msg("original")), T0)
            repo2.ingest(parsed(msg("edited later")), T0 + 5_000)
            repo2.ingest(parsed(msg("hidden", kind = ContentKind.SYSTEM_REDACTED, ts = T0 + 1)), T0)
            manager(repo2).import(uri, "pw".toCharArray(), ImportMode.MERGE)
            val m = repo2.observeMessages(MessageQuery(text = "edited")).first().single()
            assertEquals(2, m.revisionCount)
            assertEquals(listOf("original"), repo2.observeMessage(m.id).first()!!.revisions.map { it.text })
            assertEquals(2, repo2.observeStats().first().totalMessages)
        } finally {
            db2.close()
        }
    }

    @Test
    fun failures_mapToReasons() = runBlocking {
        seed()
        val uri = Uri.fromFile(File(tmp.root, "c.uvb"))
        manager(repo).export(uri, "right".toCharArray())
        val empty = CharArray(0)
        assertEquals(BackupResult.Reason.EMPTY_PASSWORD, (manager(repo).export(uri, empty) as BackupResult.Failure).reason)
        assertEquals(BackupResult.Reason.EMPTY_PASSWORD, (manager(repo).import(uri, empty) as BackupResult.Failure).reason)
        assertEquals(BackupResult.Reason.WRONG_PASSWORD, (manager(repo).import(uri, "wrong".toCharArray()) as BackupResult.Failure).reason)

        val corrupt = File(tmp.root, "garbage.uvb").apply { writeText("this is not a backup") }
        assertEquals(BackupResult.Reason.CORRUPT_FILE, (manager(repo).import(Uri.fromFile(corrupt), "right".toCharArray()) as BackupResult.Failure).reason)

        val newer = File(tmp.root, "newer.uvb").apply { writeBytes(File(tmp.root, "c.uvb").readBytes().also { it[3] = 5 }) }
        assertEquals(BackupResult.Reason.UNSUPPORTED_VERSION, (manager(repo).import(Uri.fromFile(newer), "right".toCharArray()) as BackupResult.Failure).reason)

        val missing = Uri.fromFile(File(tmp.root, "does-not-exist.uvb"))
        assertEquals(BackupResult.Reason.IO_ERROR, (manager(repo).import(missing, "right".toCharArray()) as BackupResult.Failure).reason)
        // Failed imports never touched the vault.
        assertEquals(3, repo.observeStats().first().totalMessages)
    }
}
