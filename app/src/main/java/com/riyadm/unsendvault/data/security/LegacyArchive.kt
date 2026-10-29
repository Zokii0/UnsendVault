package com.riyadm.unsendvault.data.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Handles the archive file left by release 1.0.15, which stored the whole database with SQLCipher. Without the SQLCipher
 * library that file cannot be read, so on open:
 *  - a file whose header is not `SQLite format 3\0` (a SQLCipher file, or anything unreadable as SQLite) is renamed
 *    aside together with its -wal/-shm/-journal files and then deleted; the caller raises [LegacyArchiveNotice];
 *  - a plain SQLite file (never shipped, but possible from development builds) is adopted: renamed to the new file name
 *    so Room migrates it to the current schema;
 *  - an empty file is deleted silently.
 * The wrapped key file is left alone: the new vault derives its keys from the same root secret.
 *
 * Pure file logic so it is unit-testable on the JVM.
 */
object LegacyArchive {
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val SIDECARS = listOf("-wal", "-shm", "-journal")
    private const val DISCARDED_SUFFIX = ".discarded"

    enum class Kind { MISSING, EMPTY, PLAIN_SQLITE, NOT_SQLITE }

    enum class Outcome {
        /** No legacy file (or an empty one, removed). */
        NONE,

        /** A plain SQLite legacy file was renamed to the current file name. */
        ADOPTED,

        /** A legacy file this version cannot read was deleted. */
        DISCARDED,

        /** A plain SQLite legacy file exists next to a current vault; left untouched. */
        KEPT,
    }

    @Throws(IOException::class)
    fun classify(file: File): Kind {
        if (!file.exists()) return Kind.MISSING
        if (file.length() == 0L) return Kind.EMPTY
        val header = ByteArray(SQLITE_MAGIC.size)
        val read = FileInputStream(file).use { input ->
            var total = 0
            while (total < header.size) {
                val n = input.read(header, total, header.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        return if (read == header.size && header.contentEquals(SQLITE_MAGIC)) Kind.PLAIN_SQLITE else Kind.NOT_SQLITE
    }

    /**
     * Resolves [legacy] (1.0.15's database file) against [current] (this version's database file). Must run before the
     * current database is opened.
     */
    @Throws(IOException::class)
    fun resolve(legacy: File, current: File): Outcome {
        // Finish a deletion interrupted by a crash or a kill.
        filesOf(File(legacy.path + DISCARDED_SUFFIX)).forEach { it.delete() }
        return when (classify(legacy)) {
            Kind.MISSING -> {
                SIDECARS.forEach { File(legacy.path + it).delete() }
                Outcome.NONE
            }
            Kind.EMPTY -> {
                filesOf(legacy).forEach { it.delete() }
                Outcome.NONE
            }
            Kind.PLAIN_SQLITE -> if (current.exists()) {
                Outcome.KEPT
            } else {
                current.parentFile?.mkdirs()
                // Sidecars first, main file last: an interrupted adoption is completed by the next attempt.
                for (suffix in SIDECARS) {
                    val from = File(legacy.path + suffix)
                    if (from.exists() && !from.renameTo(File(current.path + suffix))) throw IOException("Cannot move $suffix")
                }
                if (!legacy.renameTo(current)) throw IOException("Cannot move legacy database")
                Outcome.ADOPTED
            }
            Kind.NOT_SQLITE -> {
                val aside = File(legacy.path + DISCARDED_SUFFIX)
                // Main file first: once it is renamed the archive is gone for good, and a crash before the deletes
                // below is cleaned up by the next call.
                if (!legacy.renameTo(aside)) throw IOException("Cannot move legacy database aside")
                SIDECARS.forEach { File(legacy.path + it).renameTo(File(aside.path + it)) }
                filesOf(aside).forEach { it.delete() }
                SIDECARS.forEach { File(legacy.path + it).delete() }
                Outcome.DISCARDED
            }
        }
    }

    /** Deletes the legacy file set unconditionally (vault reset). */
    fun deleteAll(legacy: File) {
        filesOf(legacy).forEach { it.delete() }
        filesOf(File(legacy.path + DISCARDED_SUFFIX)).forEach { it.delete() }
    }

    private fun filesOf(main: File): List<File> = listOf(main) + SIDECARS.map { File(main.path + it) }
}

/**
 * One-time "your 1.0.15 archive could not be kept" notice, persisted as a marker file so it survives a restart until
 * the user dismisses it. [markerFile] null disables persistence (tests, in-memory vaults).
 */
class LegacyArchiveNotice(private val markerFile: File?) {
    private val state = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = state.asStateFlow()

    /** Loads the persisted state. Call off the main thread. */
    fun refresh() {
        state.value = markerFile?.exists() == true
    }

    /** Records that a legacy archive was discarded. Call off the main thread. */
    fun raise() {
        markerFile?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                f.createNewFile()
            }
        }
        state.value = true
    }

    suspend fun dismiss() {
        state.value = false
        markerFile?.let { f -> withContext(Dispatchers.IO) { f.delete() } }
    }
}
