package com.riyadm.unsendvault.data

import android.content.Context
import com.riyadm.unsendvault.data.backup.BackupManagerImpl
import com.riyadm.unsendvault.data.backup.BackupStore
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.retention.RetentionRunner
import com.riyadm.unsendvault.data.retention.WorkManagerRetentionScheduler
import com.riyadm.unsendvault.data.security.AndroidKeystoreKeyWrapper
import com.riyadm.unsendvault.data.security.KeyWrapper
import com.riyadm.unsendvault.data.security.SqlCipherSupport
import com.riyadm.unsendvault.data.security.VaultDatabaseHolder
import com.riyadm.unsendvault.data.security.VaultKeyException
import com.riyadm.unsendvault.data.security.VaultKeyManager
import com.riyadm.unsendvault.data.security.VaultKeyState
import com.riyadm.unsendvault.data.settings.DataStoreSettingsRepository
import com.riyadm.unsendvault.data.settings.vaultSettingsDataStore
import com.riyadm.unsendvault.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Production wiring of the data layer:
 *  - VaultRepository: Room over SQLCipher; key from [VaultKeyManager] (Keystore-wrapped, noBackupFilesDir). Opened
 *    lazily on Dispatchers.IO at the first query/write. Health/recovery: `container.vaultRepository.vaultHealth`.
 *  - SettingsRepository: Preferences DataStore "settings".
 *  - BackupManager: password-encrypted SAF export/import.
 *  - RetentionScheduler: daily WorkManager job; self-syncs with settings.retentionDays once created.
 */
object DataModule {
    const val KEY_FILE_NAME = "vault.key"

    fun createVaultRepository(context: Context): VaultRepository =
        RoomVaultRepository(encryptedHolder(context.applicationContext))

    fun createSettingsRepository(context: Context, container: AppContainer): SettingsRepository =
        DataStoreSettingsRepository(context.applicationContext.vaultSettingsDataStore, container.appScope)

    fun createBackupManager(context: Context, container: AppContainer): BackupManager {
        val store = container.vaultRepository as? BackupStore
            ?: return object : BackupManager {
                override suspend fun export(destination: android.net.Uri, password: CharArray) =
                    BackupResult.Failure(BackupResult.Reason.IO_ERROR, "Backup requires the Room vault")
                override suspend fun import(source: android.net.Uri, password: CharArray, mode: ImportMode) =
                    BackupResult.Failure(BackupResult.Reason.IO_ERROR, "Backup requires the Room vault")
            }
        return BackupManagerImpl(context.applicationContext.contentResolver, store, container.settingsRepository)
    }

    fun createRetentionScheduler(context: Context, container: AppContainer): RetentionScheduler {
        val scheduler = WorkManagerRetentionScheduler(
            context.applicationContext,
            RetentionRunner(container.settingsRepository, container.vaultRepository),
        )
        scheduler.startSync(container.settingsRepository, container.appScope)
        return scheduler
    }

    /**
     * Builds the SQLCipher-backed holder. Database and key file live in credential-encrypted, non-backed-up storage.
     * Parameters exist so instrumented tests can use their own files/alias and never touch the real vault.
     */
    internal fun encryptedHolder(
        app: Context,
        dbName: String = VaultDatabase.FILE_NAME,
        keyFile: File = File(app.noBackupFilesDir, KEY_FILE_NAME),
        wrapper: KeyWrapper = AndroidKeystoreKeyWrapper(),
    ): VaultDatabaseHolder {
        val keyManager = VaultKeyManager(keyFile, wrapper)
        var passphrase: ByteArray? = null
        return VaultDatabaseHolder(
            opener = {
                withContext(Dispatchers.IO) {
                    try {
                        SqlCipherSupport.ensureLoaded()
                    } catch (e: UnsatisfiedLinkError) {
                        throw VaultKeyException(VaultKeyState.Reason.DATABASE_UNREADABLE, "SQLCipher native library unavailable", e)
                    }
                    val dbFile = app.getDatabasePath(dbName)
                    val pass = keyManager.loadOrCreatePassphrase(databaseExists = dbFile.exists())
                    val db = VaultDatabase.build(app, dbName, SqlCipherSupport.factory(pass))
                    try {
                        // Force the open now so a wrong key / corrupt file surfaces as a state, not as a query crash.
                        db.openHelper.writableDatabase
                    } catch (e: Exception) {
                        runCatching { db.close() }
                        pass.fill(0)
                        throw VaultKeyException(VaultKeyState.Reason.DATABASE_UNREADABLE, "Database cannot be opened", e)
                    }
                    passphrase?.fill(0)
                    passphrase = pass
                    db
                }
            },
            destroyer = {
                withContext(Dispatchers.IO) {
                    passphrase?.fill(0) // database already closed by the holder
                    passphrase = null
                    app.deleteDatabase(dbName)
                    keyManager.destroy()
                }
            },
        )
    }
}
