package com.riyadm.unsendvault.data.security

/**
 * Wraps (encrypts) small secrets with a non-exportable key. Production: [AndroidKeystoreKeyWrapper]. Abstracted so the
 * key-file logic in [VaultKeyManager] is unit-testable on the JVM.
 */
interface KeyWrapper {
    fun keyExists(): Boolean

    /** (Re)creates the wrapping key. [preferStrongBox] is a hint; implementations fall back when unavailable. */
    fun createKey(preferStrongBox: Boolean)

    /** Returns an opaque blob (IV + ciphertext + tag). */
    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(blob: ByteArray): ByteArray

    fun deleteKey()
}

/** The wrapping key itself is missing. */
class WrappingKeyMissingException(message: String) : java.security.GeneralSecurityException(message)
