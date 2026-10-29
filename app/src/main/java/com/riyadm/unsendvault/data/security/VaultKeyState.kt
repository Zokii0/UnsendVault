package com.riyadm.unsendvault.data.security

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the encrypted archive can be opened. The UI observes this through [VaultHealth] and, when [Unavailable],
 * explains the problem and offers "Retry" and "Reset vault" (which permanently deletes the unreadable archive).
 */
sealed interface VaultKeyState {
    /** Not opened yet (opening is lazy: first query or write). */
    data object Initializing : VaultKeyState

    data object Ready : VaultKeyState

    data class Unavailable(val reason: Reason, val detail: String? = null) : VaultKeyState {
        /** True when the cause may be transient (e.g. Keystore busy right after boot); the vault retries on its own. */
        val retryable: Boolean get() = reason == Reason.KEYSTORE_ERROR
    }

    enum class Reason {
        /**
         * The Keystore key that wraps the database key is gone (Keystore wiped, app data partially cleared, OEM bug) or
         * the wrapped key file is missing while the database still exists. The archive cannot be recovered.
         */
        KEY_LOST,

        /** The wrapped key file is malformed or does not decrypt with the Keystore key. Not recoverable. */
        KEY_FILE_CORRUPT,

        /** The Keystore failed in a way that may be temporary. Retried automatically after a cooldown. */
        KEYSTORE_ERROR,

        /**
         * The key unwrapped fine but the database could not be opened: the file is corrupt, it was encrypted with a
         * different key (key-check mismatch), or a schema migration failed.
         */
        DATABASE_UNREADABLE,
    }
}

/** Thrown internally while opening the vault; surfaced to callers only as [VaultKeyState.Unavailable]. */
class VaultKeyException(val reason: VaultKeyState.Reason, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Health of the encrypted vault, implemented by the Room repository. Obtain it with
 * `container.vaultRepository.vaultHealth` (null for fake repositories, which are always healthy).
 *
 * While [keyState] is [VaultKeyState.Unavailable] every VaultRepository read emits empty results and every write is a
 * no-op (ingest returns IngestResult.EMPTY); nothing crashes and nothing is deleted automatically.
 */
interface VaultHealth {
    val keyState: StateFlow<VaultKeyState>

    /** Tries to open the vault again (e.g. after a transient Keystore error). Returns the resulting state. */
    suspend fun retryOpen(): VaultKeyState

    /**
     * Destructive recovery: closes the database, deletes the database files, the wrapped key file and the Keystore key,
     * then creates a fresh empty vault. Only call after explicit user confirmation.
     */
    suspend fun resetVault(): VaultKeyState

    /**
     * True once the vault found an archive written by version 1.0.15 (a SQLCipher database, which this version can no
     * longer read) and removed it while creating the new vault. Stays true across restarts until
     * [dismissLegacyArchiveNotice] so the user sees the notice at least once.
     */
    val legacyArchiveDiscarded: StateFlow<Boolean>

    /** The user has read the legacy-archive notice; hide it for good. */
    suspend fun dismissLegacyArchiveNotice()
}
