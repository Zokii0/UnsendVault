package com.riyadm.unsendvault.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.riyadm.unsendvault.data.BackupManager
import com.riyadm.unsendvault.data.BackupResult
import com.riyadm.unsendvault.data.ImportMode
import com.riyadm.unsendvault.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Encrypted export/import through SAF Uris. Streams go straight between ContentResolver and [BackupCrypto]; plaintext
 * exists only in memory (never in files or cache).
 *
 * The [CharArray] password passed to [export]/[import] is zeroed before returning — callers must not reuse it.
 */
class BackupManagerImpl(
    private val contentResolver: ContentResolver,
    private val store: BackupStore,
    private val settings: SettingsRepository,
    private val crypto: BackupCrypto = BackupCrypto(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupManager {

    override suspend fun export(destination: Uri, password: CharArray): BackupResult = try {
        if (password.isEmpty()) {
            BackupResult.Failure(BackupResult.Reason.EMPTY_PASSWORD)
        } else {
            withContext(io) { exportInternal(destination, password) }
        }
    } finally {
        password.fill('\u0000')
    }

    private suspend fun exportInternal(destination: Uri, password: CharArray): BackupResult {
        val now = clock()
        return try {
            val payload = store.exportSnapshot(now)
                ?: return BackupResult.Failure(BackupResult.Reason.IO_ERROR, "Vault unavailable")
            openOutput(destination).use { out ->
                crypto.encrypt(password, out, now) { sink -> BackupCodec.write(payload, sink) }
            }
            settings.update { it.copy(lastExportAt = now) }
            BackupResult.Success(payload.messages.size, payload.conversations.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Export failed", e)
            BackupResult.Failure(BackupResult.Reason.IO_ERROR, e.javaClass.simpleName)
        }
    }

    override suspend fun import(source: Uri, password: CharArray, mode: ImportMode): BackupResult = try {
        if (password.isEmpty()) {
            BackupResult.Failure(BackupResult.Reason.EMPTY_PASSWORD)
        } else {
            withContext(io) { importInternal(source, password, mode) }
        }
    } finally {
        password.fill('\u0000')
    }

    private suspend fun importInternal(source: Uri, password: CharArray, mode: ImportMode): BackupResult {
        var plain: ByteArray? = null
        return try {
            val input = contentResolver.openInputStream(source) ?: throw FileNotFoundException("No input stream")
            plain = input.use { crypto.decrypt(password, it) }
            val payload = BackupCodec.read(plain)
            plain.fill(0)
            val counts = store.importPayload(payload, mode)
                ?: return BackupResult.Failure(BackupResult.Reason.IO_ERROR, "Vault unavailable")
            BackupResult.Success(counts.messages, counts.conversations)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupCryptoException) {
            BackupResult.Failure(e.reason, e.message)
        } catch (e: BackupFormatException) {
            BackupResult.Failure(e.reason, e.message)
        } catch (e: IOException) {
            Log.w(TAG, "Import I/O failed", e)
            BackupResult.Failure(BackupResult.Reason.IO_ERROR, e.javaClass.simpleName)
        } catch (e: Exception) {
            Log.w(TAG, "Import failed", e)
            BackupResult.Failure(BackupResult.Reason.IO_ERROR, e.javaClass.simpleName)
        } finally {
            plain?.fill(0)
        }
    }

    /** "wt" truncates; some providers only support "w" (where a few Android versions don't truncate — GCM then fails loudly on import). */
    private fun openOutput(uri: Uri): OutputStream =
        try {
            contentResolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: FileNotFoundException) {
            null
        } ?: contentResolver.openOutputStream(uri, "w")
        ?: throw FileNotFoundException("Cannot open $uri for writing")

    private companion object {
        const val TAG = "UvBackup"
    }
}
