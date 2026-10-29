package com.riyadm.unsendvault.data.backup

import com.riyadm.unsendvault.data.BackupResult
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption of backup files. Pure JVM (javax.crypto only) so it is unit-testable.
 *
 * File layout, format version 1 (all integers big-endian):
 * ```
 * offset size
 *   0     3   magic "UVB"
 *   3     1   format version (1)
 *   4     1   KDF id (1 = PBKDF2WithHmacSHA256)
 *   5     4   KDF iterations
 *   9     8   createdAt (epoch millis, informational)
 *  17    16   salt
 *  33    12   GCM nonce
 *  45     …   AES-256-GCM ciphertext of the payload, 128-bit tag appended
 * ```
 * The whole 45-byte header is the GCM additional authenticated data, so any header change (iterations, timestamp,
 * salt, …) fails authentication. The iteration count is stored so it can be raised later without a format change.
 *
 * Errors are reported as [BackupCryptoException] with a [BackupResult.Reason]: bad magic/short header → CORRUPT_FILE,
 * newer format version → UNSUPPORTED_VERSION, tag mismatch (wrong password *or* tampered/truncated ciphertext — GCM
 * cannot tell them apart) → WRONG_PASSWORD.
 */
class BackupCrypto(
    val iterations: Int = DEFAULT_ITERATIONS,
    private val minIterations: Int = MIN_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
    private val maxCiphertextBytes: Long = MAX_CIPHERTEXT_BYTES,
) {
    init {
        require(iterations >= minIterations) { "iterations below minimum" }
    }

    /**
     * Writes header + ciphertext to [output]. [writePlaintext] streams the plaintext into the provided sink, which
     * encrypts chunk by chunk; plaintext never touches disk. Does not close [output].
     */
    fun encrypt(password: CharArray, output: OutputStream, createdAt: Long, writePlaintext: (OutputStream) -> Unit) {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = Header(FORMAT_VERSION, KDF_PBKDF2_SHA256, iterations, createdAt, salt, nonce).encode()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        output.write(header)
        val sink = CipherSink(cipher, output)
        writePlaintext(sink)
        sink.finish()
        output.flush()
    }

    fun encrypt(password: CharArray, plaintext: ByteArray, createdAt: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        encrypt(password, out, createdAt) { it.write(plaintext) }
        return out.toByteArray()
    }

    /** Reads and authenticates a backup; returns the plaintext. The caller should zero it after use. */
    @Throws(BackupCryptoException::class, IOException::class)
    fun decrypt(password: CharArray, input: InputStream): ByteArray {
        val headerBytes = readHeader(input)
        val header = Header.decode(headerBytes)
        if (header.kdf != KDF_PBKDF2_SHA256) throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, "Unknown KDF")
        if (header.iterations !in minIterations..MAX_ITERATIONS) {
            throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, "Implausible KDF iterations")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, header.salt, header.iterations),
            GCMParameterSpec(TAG_BITS, header.nonce),
        )
        cipher.updateAAD(headerBytes)
        val plain = WipeableBuffer()
        try {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxCiphertextBytes) throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, "File too large")
                cipher.update(buf, 0, n)?.let { plain.write(it); it.fill(0) }
            }
            if (total < TAG_BITS / 8) throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, "Truncated file")
            val last = try {
                cipher.doFinal()
            } catch (e: AEADBadTagException) {
                throw BackupCryptoException(BackupResult.Reason.WRONG_PASSWORD, "Wrong password or damaged file", e)
            }
            plain.write(last)
            last.fill(0)
            return plain.toByteArray()
        } catch (e: GeneralSecurityException) {
            throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, e.javaClass.simpleName, e)
        } finally {
            plain.wipe()
        }
    }

    private fun readHeader(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val prefix = ByteArray(4)
        try {
            data.readFully(prefix)
        } catch (e: EOFException) {
            throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, "Not an UnsendVault backup", e)
        }
        if (prefix[0] != MAGIC[0] || prefix[1] != MAGIC[1] || prefix[2] != MAGIC[2]) {
            throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, "Not an UnsendVault backup")
        }
        val version = prefix[3].toInt() and 0xFF
        if (version > FORMAT_VERSION) {
            throw BackupCryptoException(BackupResult.Reason.UNSUPPORTED_VERSION, "Backup format $version is newer than this app")
        }
        if (version < 1) throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, "Invalid format version")
        val header = ByteArray(HEADER_BYTES)
        prefix.copyInto(header)
        try {
            data.readFully(header, 4, HEADER_BYTES - 4)
        } catch (e: EOFException) {
            throw BackupCryptoException(BackupResult.Reason.CORRUPT_FILE, "Truncated header", e)
        }
        return header
    }

    internal class Header(
        val version: Int,
        val kdf: Int,
        val iterations: Int,
        val createdAt: Long,
        val salt: ByteArray,
        val nonce: ByteArray,
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(version.toByte()).put(kdf.toByte()).putInt(iterations).putLong(createdAt)
            .put(salt).put(nonce).array()

        companion object {
            fun decode(bytes: ByteArray): Header {
                val b = ByteBuffer.wrap(bytes)
                b.position(3)
                val version = b.get().toInt() and 0xFF
                val kdf = b.get().toInt() and 0xFF
                val iterations = b.getInt()
                val createdAt = b.getLong()
                val salt = ByteArray(SALT_BYTES).also { b.get(it) }
                val nonce = ByteArray(NONCE_BYTES).also { b.get(it) }
                return Header(version, kdf, iterations, createdAt, salt, nonce)
            }
        }
    }

    /** Encrypting OutputStream that, unlike CipherOutputStream, never swallows doFinal errors. */
    private class CipherSink(private val cipher: Cipher, private val out: OutputStream) : OutputStream() {
        private var finished = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished)
            cipher.update(b, off, len)?.let { if (it.isNotEmpty()) out.write(it) }
        }
        fun finish() {
            if (finished) return
            finished = true
            out.write(cipher.doFinal())
        }
        override fun flush() = out.flush()
        override fun close() = finish()
    }

    /** ByteArrayOutputStream whose backing array can be zeroed. */
    private class WipeableBuffer : ByteArrayOutputStream(64 * 1024) {
        fun wipe() {
            buf.fill(0)
            count = 0
        }
    }

    companion object {
        val MAGIC = byteArrayOf('U'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte())
        const val FORMAT_VERSION = 1
        const val KDF_PBKDF2_SHA256 = 1
        /** ≥ 210k required; 310k (OWASP 2021 for PBKDF2-HMAC-SHA256) keeps export/import ~1–3 s on low-end devices. */
        const val DEFAULT_ITERATIONS = 310_000
        const val MIN_ITERATIONS = 210_000
        const val MAX_ITERATIONS = 10_000_000
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12
        const val HEADER_BYTES = 3 + 1 + 1 + 4 + 8 + SALT_BYTES + NONCE_BYTES
        const val MAX_CIPHERTEXT_BYTES = 512L * 1024 * 1024
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
            val spec = PBEKeySpec(password, salt, iterations, 256)
            try {
                val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                try {
                    return SecretKeySpec(raw, "AES")
                } finally {
                    raw.fill(0)
                }
            } finally {
                spec.clearPassword()
            }
        }
    }
}

class BackupCryptoException(val reason: BackupResult.Reason, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Payload decoded but semantically invalid (e.g. dangling references). */
class BackupFormatException(message: String, val reason: BackupResult.Reason = BackupResult.Reason.CORRUPT_FILE) :
    Exception(message)
