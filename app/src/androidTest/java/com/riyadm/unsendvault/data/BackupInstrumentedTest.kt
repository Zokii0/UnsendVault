package com.riyadm.unsendvault.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.backup.BackupManagerImpl
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.security.FieldCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Full export → import through ContentResolver with the production KDF cost, between two vaults with different keys. */
@RunWith(AndroidJUnit4::class)
class BackupInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf("uv-backup-src-test.db", "uv-backup-dst-test.db")
    private val file = File(context.filesDir, "uv-backup-test.uvb")

    @After
    fun cleanup() {
        names.forEach(context::deleteDatabase)
        file.delete()
    }

    private fun vault(name: String) = VaultDatabase.build(context, name, FieldCipher.random())

    @Test
    fun exportImport_throughContentResolver() = runBlocking {
        val srcDb = vault(names[0])
        val dstDb = vault(names[1])
        try {
            val src = RoomVaultRepository(srcDb)
            src.ingest(DeviceFx.parsed(DeviceFx.msg("first version"), DeviceFx.msg("other", ts = DeviceFx.T0 + 1)), DeviceFx.T0)
            src.ingest(DeviceFx.parsed(DeviceFx.msg("second version")), DeviceFx.T0 + 5)
            val settings = InMemorySettingsRepository()
            val uri = Uri.fromFile(file)

            val started = System.nanoTime()
            val export = BackupManagerImpl(context.contentResolver, src, settings).export(uri, "device pw".toCharArray())
            Log.i("UvBackupTest", "export took ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(BackupResult.Success(2, 1), export)
            assertTrue(settings.current.lastExportAt != null)
            val raw = file.readBytes()
            assertEquals("UVB", String(raw, 0, 3))
            assertFalse(String(raw, Charsets.ISO_8859_1).contains("version"))

            val dst = RoomVaultRepository(dstDb)
            val manager = BackupManagerImpl(context.contentResolver, dst, settings)
            assertEquals(
                BackupResult.Reason.WRONG_PASSWORD,
                (manager.import(uri, "nope".toCharArray()) as BackupResult.Failure).reason,
            )
            assertEquals(BackupResult.Success(2, 1), manager.import(uri, "device pw".toCharArray(), ImportMode.MERGE))
            assertEquals(BackupResult.Success(2, 1), manager.import(uri, "device pw".toCharArray(), ImportMode.MERGE))
            val msgs = dst.observeMessages(MessageQuery()).first()
            assertEquals(setOf("second version", "other"), msgs.map { it.text }.toSet())
            val edited = msgs.first { it.text == "second version" }
            assertEquals(listOf("first version"), dst.observeMessage(edited.id).first()!!.revisions.map { it.text })
        } finally {
            srcDb.close()
            dstDb.close()
        }
    }
}
