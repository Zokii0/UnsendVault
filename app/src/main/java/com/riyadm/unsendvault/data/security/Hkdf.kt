package com.riyadm.unsendvault.data.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF with HMAC-SHA256 (RFC 5869), on javax.crypto only. Used to derive the vault's subkeys from the
 * Keystore-wrapped root secret. Every intermediate array is zeroed; the caller owns (and should zero) the output.
 */
object Hkdf {
    private const val ALGORITHM = "HmacSHA256"
    const val HASH_LEN = 32

    /** HKDF-Extract. An empty or null [salt] means HashLen zero bytes, as the RFC specifies. */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val saltBytes = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(saltBytes, ALGORITHM))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand to [length] bytes (at most 255 * HashLen). */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "invalid length" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(prk, ALGORITHM))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            val block = mac.doFinal()
            previous.fill(0)
            val n = minOf(block.size, length - written)
            System.arraycopy(block, 0, out, written, n)
            written += n
            counter++
            previous = block
        }
        previous.fill(0)
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }
}
