package com.riyadm.unsendvault.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key in AndroidKeyStore that wraps the database passphrase.
 *
 * - No user-authentication requirement and no unlocked-device requirement: the notification listener must be able to
 *   open the vault while the screen is locked (after the first unlock since boot, when credential-encrypted storage is
 *   available anyway).
 * - StrongBox is tried on API 28+; any failure (StrongBoxUnavailableException, ProviderException from OEM keymaster
 *   bugs, …) falls back to the TEE-backed key. [VaultKeyManager] additionally verifies a wrap/unwrap round trip and
 *   retries without StrongBox if a StrongBox key fails at use time.
 *
 * Blob format: [ivLength:u8][iv][ciphertext+tag].
 */
class AndroidKeystoreKeyWrapper(private val alias: String = DEFAULT_ALIAS) : KeyWrapper {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun keyExists(): Boolean = keyStore().containsAlias(alias)

    override fun createKey(preferStrongBox: Boolean) {
        deleteKey()
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generate(strongBox = true)
                return
            } catch (e: ProviderException) {
                // Includes StrongBoxUnavailableException (API 28 subclass of ProviderException; not referenced directly
                // so the catch clause verifies cleanly on API 26-27) and OEM keymaster failures.
                Log.w(TAG, "StrongBox key generation failed, using TEE key", e)
            } catch (e: java.security.GeneralSecurityException) {
                Log.w(TAG, "StrongBox key generation failed, using TEE key", e)
            }
            runCatching { deleteKey() }
        }
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(builder.build())
            generateKey()
        }
    }

    private fun key(): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey ?: throw WrappingKeyMissingException("Keystore alias $alias missing")

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > 1) { "blob too short" }
        val ivLen = blob[0].toInt() and 0xFF
        require(ivLen in 12..16 && blob.size > 1 + ivLen + TAG_BYTES) { "malformed blob" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, blob, 1, ivLen))
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    override fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    companion object {
        const val DEFAULT_ALIAS = "unsendvault.vault.kek.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BYTES = 16
        private const val TAG = "UvKeystore"
    }
}
