package com.riyadm.unsendvault.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.security.SqlCipherSupport
import com.riyadm.unsendvault.data.security.VaultKeyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class SqlCipherEncryptionTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "uv-sqlcipher-encryption-test.db"
    private val marker = "PLAINTEXT_MARKER_7f3a9c_should_never_hit_disk"

    private fun newKey() = VaultKeyManager.toSqlCipherRawKey(ByteArray(32).also(SecureRandom()::nextBytes))

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
            assertEquals("marker found in ${f.name}", -1, bytes.indexOf(marker.toByteArray(Charsets.UTF_8)))
            assertEquals("sender found in ${f.name}", -1, bytes.indexOf("ZebraSender".toByteArray(Charsets.UTF_8)))
        }
        val header = context.getDatabasePath(name).readBytes().copyOf(16)
        assertFalse("plain SQLite header", header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
    }

    @Before
    @After
    fun clean() {
        context.deleteDatabase(name)
    }

    @Test
    fun databaseFileIsEncrypted_wrongKeyFails_rightKeyReopens() = runBlocking {
        val key = newKey()
        val db = VaultDatabase.build(context, name, SqlCipherSupport.factory(key))
        val repo = RoomVaultRepository(db)
        repo.ingest(DeviceFx.parsed(DeviceFx.msg("hello $marker", sender = "ZebraSender")), DeviceFx.T0)
        assertEquals(1, repo.observeMessages(MessageQuery(text = "hello")).first().size)
        // While open (data may still sit in the WAL) and after close (checkpointed into the main file).
        assertNoPlaintext()
        db.close()
        assertNoPlaintext()

        val wrong = VaultDatabase.build(context, name, SqlCipherSupport.factory(newKey()))
        try {
            wrong.openHelper.writableDatabase
            fail("opened with the wrong key")
        } catch (expected: Exception) {
            // net.zetetic SQLiteException: file is not a database
        } finally {
            wrong.close()
        }

        val again = VaultDatabase.build(context, name, SqlCipherSupport.factory(key))
        try {
            val hits = RoomVaultRepository(again).observeMessages(MessageQuery(text = "hello")).first()
            assertEquals("ZebraSender", hits.single().sender)
            // WAL is active on the encrypted database.
            again.openHelper.writableDatabase.query("PRAGMA journal_mode").use { c ->
                c.moveToFirst()
                assertEquals("wal", c.getString(0).lowercase())
            }
        } finally {
            again.close()
        }
    }
}
