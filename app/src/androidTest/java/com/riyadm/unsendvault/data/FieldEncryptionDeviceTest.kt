package com.riyadm.unsendvault.data

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.security.AndroidKeystoreKeyWrapper
import com.riyadm.unsendvault.data.security.VaultDatabaseHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the device's framework SQLite with the real Keystore: after ingest, neither the database file nor its WAL/SHM
 * contain message text, sender names, titles or conversation keys; WAL is active; a new holder reads everything back.
 */
@RunWith(AndroidJUnit4::class)
class FieldEncryptionDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "uv-field-encryption-test.db"
    private val legacyName = "uv-field-encryption-legacy-test.db"
    private val alias = "unsendvault.test.fieldenc.kek"
    private val keyFile = File(context.noBackupFilesDir, "uv-fieldenc-test.key")
    private val noticeFile = File(context.noBackupFilesDir, "uv-fieldenc-test-notice")
    private val marker = "PLAINTEXT_MARKER_7f3a9c_should_never_hit_disk"
    private val holders = mutableListOf<VaultDatabaseHolder>()

    private fun repo(): RoomVaultRepository {
        val holder = DataModule.encryptedHolder(context, name, keyFile, AndroidKeystoreKeyWrapper(alias), legacyName, noticeFile)
        holders += holder
        return RoomVaultRepository(holder)
    }

    private fun dbFiles(): List<File> {
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

    private fun assertNoPlaintext() {
        val files = dbFiles()
        assertTrue(files.isNotEmpty())
        files.forEach { f ->
            val bytes = f.readBytes()
            listOf(marker, "ZebraSender", "ZebraTitle", "zebra-conversation-key").forEach { secret ->
                assertEquals("'$secret' found in ${f.name}", -1, bytes.indexOf(secret.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    @Before
    @After
    fun clean() {
        runBlocking { holders.forEach { h -> runCatching { h.database()?.close() } } }
        holders.clear()
        runCatching { AndroidKeystoreKeyWrapper(alias).deleteKey() }
        keyFile.delete()
        noticeFile.delete()
        context.deleteDatabase(name)
        context.deleteDatabase(legacyName)
    }

    @Test
    fun databaseFiles_containNoPlaintext_walActive_reopenReadsBack() = runBlocking {
        val repo = repo()
        val msg = DeviceFx.msg("hello $marker", sender = "ZebraSender", key = "zebra-conversation-key")
        repo.ingest(DeviceFx.parsed(msg.copy(conversationTitle = "ZebraTitle")), DeviceFx.T0)
        repo.ingest(DeviceFx.parsed(msg.copy(text = "edited $marker", conversationTitle = "ZebraTitle")), DeviceFx.T0 + 5)
        assertEquals(1, repo.observeMessages(MessageQuery(text = "edited")).first().size)
        assertEquals(0, repo.observeMessages(MessageQuery(text = "hello")).first().size) // only in the revision
        // While open (rows may still sit in the WAL).
        assertNoPlaintext()
        val db = holders.single().database()!!
        db.openHelper.writableDatabase.query("PRAGMA journal_mode").use { c ->
            c.moveToFirst()
            assertEquals("wal", c.getString(0).lowercase())
        }
        // After close (WAL checkpointed into the main file).
        db.close()
        holders.clear()
        assertNoPlaintext()

        val started = System.nanoTime()
        val again = repo()
        val hits = again.observeMessages(MessageQuery(text = "edited")).first()
        Log.i("UvFieldEncTest", "reopen (Keystore unwrap + HKDF + open) and first search took ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals("ZebraSender", hits.single().sender)
        assertEquals("ZebraTitle", hits.single().conversationTitle)
        assertEquals(listOf("hello $marker"), again.observeMessage(hits.single().id).first()!!.revisions.map { it.text })
    }
}
