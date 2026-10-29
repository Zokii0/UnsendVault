package com.riyadm.unsendvault.data.security

import android.util.Log
import com.riyadm.unsendvault.data.db.VaultDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lazily opens the vault database exactly once, tracks [VaultKeyState] and implements retry/reset.
 *
 * @param opener builds and force-opens the database (throws [VaultKeyException] on key/open problems).
 * @param destroyer deletes database files and key material (used only by [resetVault]).
 * @param legacyNotice the one-time "1.0.15 archive discarded" notice; the opener raises it.
 */
class VaultDatabaseHolder(
    private val opener: suspend () -> VaultDatabase,
    private val destroyer: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryCooldownMs: Long = 30_000,
    private val closeOnReset: Boolean = true,
    private val legacyNotice: LegacyArchiveNotice = LegacyArchiveNotice(null),
) : VaultHealth {
    private val mutex = Mutex()
    private val current = MutableStateFlow<VaultDatabase?>(null)
    private val state = MutableStateFlow<VaultKeyState>(VaultKeyState.Initializing)
    private var lastAttemptAt = 0L

    override val keyState: StateFlow<VaultKeyState> = state.asStateFlow()

    override val legacyArchiveDiscarded: StateFlow<Boolean> = legacyNotice.pending

    override suspend fun dismissLegacyArchiveNotice() = legacyNotice.dismiss()

    /** The open database, or null while the vault is unavailable (see [keyState]). */
    suspend fun database(): VaultDatabase? {
        current.value?.let { return it }
        return mutex.withLock {
            current.value ?: run {
                val s = state.value
                val mayAttempt = s !is VaultKeyState.Unavailable ||
                    (s.retryable && clock() - lastAttemptAt >= retryCooldownMs)
                if (mayAttempt) openLocked() else null
            }
        }
    }

    /** Emits the current database (opening it lazily) and re-emits after a reset/retry; null while unavailable. */
    val databases: Flow<VaultDatabase?> = flow {
        database()
        emitAll(current)
    }

    override suspend fun retryOpen(): VaultKeyState = mutex.withLock {
        if (current.value == null) openLocked()
        state.value
    }

    override suspend fun resetVault(): VaultKeyState = mutex.withLock {
        val old = current.value
        current.value = null
        state.value = VaultKeyState.Initializing
        if (closeOnReset) runCatching { old?.close() }
        destroyer()
        openLocked()
        state.value
    }

    private suspend fun openLocked(): VaultDatabase? {
        lastAttemptAt = clock()
        return try {
            opener().also {
                current.value = it
                state.value = VaultKeyState.Ready
            }
        } catch (e: VaultKeyException) {
            Log.w(TAG, "Vault unavailable: ${e.reason}", e)
            state.value = VaultKeyState.Unavailable(e.reason, e.message)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Vault open failed", e)
            state.value = VaultKeyState.Unavailable(VaultKeyState.Reason.DATABASE_UNREADABLE, e.javaClass.simpleName)
            null
        }
    }

    companion object {
        private const val TAG = "UvVault"

        /** Holder around an already-open database (tests, in-memory). Reset just clears all tables. */
        fun of(db: VaultDatabase): VaultDatabaseHolder = VaultDatabaseHolder(
            opener = { db },
            destroyer = { db.clearAllTables() },
            closeOnReset = false,
        )
    }
}
