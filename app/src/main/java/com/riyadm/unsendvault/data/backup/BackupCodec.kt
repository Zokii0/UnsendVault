package com.riyadm.unsendvault.data.backup

import com.riyadm.unsendvault.data.BackupResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/** gzip + JSON encoding of [BackupPayload]. */
@OptIn(ExperimentalSerializationApi::class)
object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private const val MAX_DECOMPRESSED_BYTES = 1L * 1024 * 1024 * 1024
    private val VERSION_PEEK = Regex("\"schemaVersion\"\\s*:\\s*(\\d+)")

    fun write(payload: BackupPayload, out: OutputStream) {
        val gz = GZIPOutputStream(NonClosing(out), 64 * 1024)
        json.encodeToStream(BackupPayload.serializer(), payload, gz)
        gz.finish()
    }

    /** @throws BackupFormatException CORRUPT_FILE or UNSUPPORTED_VERSION. */
    fun read(gzipped: ByteArray): BackupPayload {
        val payload = try {
            Limited(GZIPInputStream(ByteArrayInputStream(gzipped))).use { json.decodeFromStream(BackupPayload.serializer(), it) }
        } catch (e: SerializationException) {
            peekVersion(gzipped)?.takeIf { it > BackupPayload.SCHEMA_VERSION }?.let {
                throw BackupFormatException("Backup schema $it is newer", BackupResult.Reason.UNSUPPORTED_VERSION)
            }
            throw BackupFormatException("Malformed backup content: ${e.message}")
        } catch (e: ZipException) {
            throw BackupFormatException("Malformed backup content")
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Malformed backup content: ${e.message}")
        } catch (e: IOException) {
            throw BackupFormatException("Malformed backup content: ${e.message}")
        }
        if (payload.schemaVersion > BackupPayload.SCHEMA_VERSION) {
            throw BackupFormatException("Backup schema ${payload.schemaVersion} is newer", BackupResult.Reason.UNSUPPORTED_VERSION)
        }
        if (payload.schemaVersion < 1) throw BackupFormatException("Invalid schema version")
        return payload
    }

    private fun peekVersion(gzipped: ByteArray): Int? = runCatching {
        val head = ByteArray(256)
        val n = GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.read(head) }
        VERSION_PEEK.find(String(head, 0, n.coerceAtLeast(0), Charsets.UTF_8))?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()

    private class NonClosing(out: OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    /** Guards against decompression bombs. */
    private class Limited(input: InputStream) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }
        private fun count(n: Int) {
            total += n
            if (total > MAX_DECOMPRESSED_BYTES) throw IOException("Backup content too large")
        }
    }
}
