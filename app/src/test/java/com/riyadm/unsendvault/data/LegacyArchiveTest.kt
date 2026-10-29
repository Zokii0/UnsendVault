package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.data.security.LegacyArchive
import com.riyadm.unsendvault.data.security.LegacyArchive.Kind
import com.riyadm.unsendvault.data.security.LegacyArchive.Outcome
import com.riyadm.unsendvault.data.security.LegacyArchiveNotice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

/** Detection and removal of the 1.0.15 SQLCipher archive (pure file logic). */
class LegacyArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "databases").apply { mkdirs() }
    private val legacy get() = File(dir, "unsendvault.db")
    private val current get() = File(dir, "vault-v3.db")
    private val sidecars = listOf("-wal", "-shm", "-journal")

    /** What SQLCipher writes: the first page is fully encrypted (random-looking salt + ciphertext), no SQLite header. */
    private fun writeSqlCipherLike(file: File) = file.writeBytes(ByteArray(4096).also(SecureRandom()::nextBytes))

    private fun writePlainSqlite(file: File, tail: String = "rows") =
        file.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(84) + tail.toByteArray())

    private fun leftovers() = dir.listFiles()!!.map { it.name }.sorted()

    @Test
    fun classify() {
        assertEquals(Kind.MISSING, LegacyArchive.classify(legacy))
        legacy.writeBytes(ByteArray(0))
        assertEquals(Kind.EMPTY, LegacyArchive.classify(legacy))
        writePlainSqlite(legacy)
        assertEquals(Kind.PLAIN_SQLITE, LegacyArchive.classify(legacy))
        writeSqlCipherLike(legacy)
        assertEquals(Kind.NOT_SQLITE, LegacyArchive.classify(legacy))
        legacy.writeBytes("SQLite".toByteArray()) // shorter than the header
        assertEquals(Kind.NOT_SQLITE, LegacyArchive.classify(legacy))
    }

    @Test
    fun sqlcipherFile_isDiscardedWithSidecars_keyFileAndCurrentUntouched() {
        val keyFile = File(tmp.root, "no_backup/vault.key").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        writeSqlCipherLike(legacy)
        sidecars.forEach { writeSqlCipherLike(File(legacy.path + it)) }
        assertEquals(Outcome.DISCARDED, LegacyArchive.resolve(legacy, current))
        assertEquals(emptyList<String>(), leftovers())
        assertArrayEquals(byteArrayOf(1, 2, 3), keyFile.readBytes())
        // Idempotent.
        assertEquals(Outcome.NONE, LegacyArchive.resolve(legacy, current))
    }

    @Test
    fun interruptedDiscard_isFinishedOnNextRun() {
        writeSqlCipherLike(File(legacy.path + ".discarded"))
        writeSqlCipherLike(File(legacy.path + ".discarded-wal"))
        writeSqlCipherLike(File(legacy.path + "-wal")) // sidecar left behind without its main file
        assertEquals(Outcome.NONE, LegacyArchive.resolve(legacy, current))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun plainSqlite_isAdopted_whenNoCurrentVault() {
        writePlainSqlite(legacy, "adopt me")
        File(legacy.path + "-wal").writeText("wal frames")
        assertEquals(Outcome.ADOPTED, LegacyArchive.resolve(legacy, current))
        assertTrue(String(current.readBytes(), Charsets.ISO_8859_1).endsWith("adopt me"))
        assertEquals("wal frames", File(current.path + "-wal").readText())
        assertEquals(listOf("vault-v3.db", "vault-v3.db-wal"), leftovers())
    }

    @Test
    fun plainSqlite_nextToCurrentVault_isKept() {
        writePlainSqlite(legacy, "old")
        writePlainSqlite(current, "new")
        assertEquals(Outcome.KEPT, LegacyArchive.resolve(legacy, current))
        assertTrue(legacy.exists() && current.exists())
    }

    @Test
    fun emptyLegacyFile_isRemovedSilently() {
        legacy.writeBytes(ByteArray(0))
        assertEquals(Outcome.NONE, LegacyArchive.resolve(legacy, current))
        assertFalse(legacy.exists())
    }

    @Test
    fun notice_persistsUntilDismissed() = runBlocking {
        val marker = File(tmp.root, "no_backup/legacy-archive-discarded")
        val notice = LegacyArchiveNotice(marker)
        notice.refresh()
        assertFalse(notice.pending.value)
        notice.raise()
        assertTrue(notice.pending.value)
        assertTrue(marker.exists())
        // A new process sees it again.
        val restarted = LegacyArchiveNotice(marker).also { it.refresh() }
        assertTrue(restarted.pending.value)
        restarted.dismiss()
        assertFalse(restarted.pending.value)
        assertFalse(marker.exists())
        assertFalse(LegacyArchiveNotice(marker).also { it.refresh() }.pending.value)
    }
}
