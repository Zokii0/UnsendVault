package com.riyadm.unsendvault.data.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * Owns the database passphrase: 32 bytes from [SecureRandom], generated once, stored only in wrapped form in
 * [keyFile] (inside noBackupFilesDir, so it never leaves the device; the manifest also disables Auto Backup because a
 * Keystore-wrapped key cannot be restored elsewhere).
 *
 * File format: [FILE_VERSION:u8][wrapper blob].
 *
 * Failure policy — never destroys data on its own:
 *  - first run (no key file, no database) → create.
 *  - key file missing but database present, Keystore alias missing, or Keystore says the key is unrecoverable → KEY_LOST.
 *  - key file malformed or GCM tag mismatch → KEY_FILE_CORRUPT.
 *  - anything else from the Keystore (KeyStoreException, ProviderException, …) → retried a few times, then
 *    KEYSTORE_ERROR (transient).
 * Recovery is the explicit [destroy] + create path driven by the "reset vault" UI.
 */
class VaultKeyManager(
    private val keyFile: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
    private val retryDelaysMs: List<Long> = listOf(150, 600),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /**
     * Key placements tried in order (true = StrongBox). Default is the hardware-backed TEE only: on a Pixel 6 running
     * Android 17 a StrongBox AES-GCM key generated fine but every decrypt failed with Keymaster TOO_MANY_OPERATIONS
     * (reproduced 5/5 runs, see docs/TEST_REPORT.md), and StrongBox's few operation slots and high latency are a poor
     * fit for a key the notification listener needs to be available at all times.
     */
    private val keyPlacements: List<Boolean> = listOf(false),
) {
    fun hasStoredKey(): Boolean = keyFile.exists()

    /**
     * Returns the SQLCipher passphrase in raw-key form (`x'<64 hex>'`, ASCII), which makes SQLCipher use the 256-bit key
     * directly instead of running its PBKDF2 on every connection open (the key is already full-entropy). The caller owns
     * the returned array and should zero it when the database is closed.
     *
     * @param databaseExists whether the encrypted database file already exists (decides "first run" vs "key lost").
     */
    @Throws(VaultKeyException::class)
    fun loadOrCreatePassphrase(databaseExists: Boolean): ByteArray {
        if (!keyFile.exists()) {
            if (databaseExists) {
                throw VaultKeyException(VaultKeyState.Reason.KEY_LOST, "Wrapped key file is missing but the database exists")
            }
            return create()
        }
        val stored = try {
            keyFile.readBytes()
        } catch (e: IOException) {
            throw VaultKeyException(VaultKeyState.Reason.KEYSTORE_ERROR, "Cannot read key file", e)
        }
        if (stored.size < 2 || stored[0] != FILE_VERSION) {
            throw VaultKeyException(VaultKeyState.Reason.KEY_FILE_CORRUPT, "Unknown key file format")
        }
        val blob = stored.copyOfRange(1, stored.size)
        val raw = withRetries { unwrapClassified(blob) }
        try {
            if (raw.size != RAW_KEY_BYTES) {
                throw VaultKeyException(VaultKeyState.Reason.KEY_FILE_CORRUPT, "Unexpected key length ${raw.size}")
            }
            return toSqlCipherRawKey(raw)
        } finally {
            raw.fill(0)
        }
    }

    /** Deletes the wrapped key file and the Keystore key. Irreversible: the existing database becomes unreadable. */
    fun destroy() {
        keyFile.delete()
        File(keyFile.path + TMP_SUFFIX).delete()
        runCatching { wrapper.deleteKey() }
    }

    private fun create(): ByteArray {
        val raw = ByteArray(RAW_KEY_BYTES).also(random::nextBytes)
        try {
            var lastError: Exception? = null
            // Some Keystore implementations generate keys but fail at use time, so the wrap is verified by an immediate
            // unwrap before anything is committed to disk; the next placement is tried on failure.
            for (preferStrongBox in keyPlacements) {
                try {
                    wrapper.createKey(preferStrongBox)
                    val blob = wrapper.wrap(raw)
                    val check = wrapper.unwrap(blob)
                    val ok = check.contentEquals(raw)
                    check.fill(0)
                    if (!ok) throw GeneralSecurityException("Wrap round-trip mismatch")
                    writeAtomically(byteArrayOf(FILE_VERSION) + blob)
                    return toSqlCipherRawKey(raw)
                } catch (e: IOException) {
                    throw VaultKeyException(VaultKeyState.Reason.KEYSTORE_ERROR, "Cannot write key file", e)
                } catch (e: Exception) {
                    lastError = e
                    runCatching { wrapper.deleteKey() }
                }
            }
            throw VaultKeyException(VaultKeyState.Reason.KEYSTORE_ERROR, "Keystore key creation failed", lastError)
        } finally {
            raw.fill(0)
        }
    }

    private fun unwrapClassified(blob: ByteArray): ByteArray = try {
        wrapper.unwrap(blob)
    } catch (e: WrappingKeyMissingException) {
        throw VaultKeyException(VaultKeyState.Reason.KEY_LOST, "Keystore key is missing", e)
    } catch (e: UnrecoverableKeyException) {
        throw VaultKeyException(VaultKeyState.Reason.KEY_LOST, "Keystore key is unrecoverable", e)
    } catch (e: InvalidKeyException) {
        // Includes KeyPermanentlyInvalidatedException.
        throw VaultKeyException(VaultKeyState.Reason.KEY_LOST, "Keystore key is invalid", e)
    } catch (e: AEADBadTagException) {
        throw VaultKeyException(VaultKeyState.Reason.KEY_FILE_CORRUPT, "Wrapped key does not authenticate", e)
    } catch (e: IllegalArgumentException) {
        throw VaultKeyException(VaultKeyState.Reason.KEY_FILE_CORRUPT, "Wrapped key is malformed", e)
    } catch (e: Exception) {
        // KeyStoreException, ProviderException, other GeneralSecurityException, IllegalStateException: possibly transient.
        throw VaultKeyException(VaultKeyState.Reason.KEYSTORE_ERROR, "Keystore error: ${e.javaClass.simpleName}", e)
    }

    private fun <T> withRetries(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: VaultKeyException) {
                if (e.reason != VaultKeyState.Reason.KEYSTORE_ERROR || attempt >= retryDelaysMs.size) throw e
                sleeper(retryDelaysMs[attempt++])
            }
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        keyFile.parentFile?.mkdirs()
        val tmp = File(keyFile.path + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(keyFile)) {
            tmp.delete()
            throw IOException("Cannot rename key file")
        }
    }

    companion object {
        const val FILE_VERSION: Byte = 1
        const val RAW_KEY_BYTES = 32
        private const val TMP_SUFFIX = ".tmp"
        private val HEX = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

        /** `x'` + 64 lowercase hex digits + `'` as ASCII bytes, built without an intermediate String. */
        fun toSqlCipherRawKey(raw: ByteArray): ByteArray {
            val out = ByteArray(raw.size * 2 + 3)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            raw.forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                out[2 + i * 2] = HEX[v ushr 4]
                out[3 + i * 2] = HEX[v and 0x0F]
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }
    }
}
