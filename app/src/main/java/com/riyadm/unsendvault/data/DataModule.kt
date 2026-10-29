package com.riyadm.unsendvault.data

import android.content.Context
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.di.AppContainer

/** SKELETON STUB — replaced by the data layer (Room + SQLCipher, DataStore, WorkManager, SAF backup). */
object DataModule {
    fun createVaultRepository(context: Context): VaultRepository = InMemoryVaultRepository()
    fun createSettingsRepository(context: Context, container: AppContainer): SettingsRepository = InMemorySettingsRepository()
    fun createBackupManager(context: Context, container: AppContainer): BackupManager = object : BackupManager {
        override suspend fun export(destination: android.net.Uri, password: CharArray) =
            BackupResult.Failure(BackupResult.Reason.IO_ERROR, "not implemented")
        override suspend fun import(source: android.net.Uri, password: CharArray, mode: ImportMode) =
            BackupResult.Failure(BackupResult.Reason.IO_ERROR, "not implemented")
    }
    fun createRetentionScheduler(context: Context, container: AppContainer): RetentionScheduler = object : RetentionScheduler {
        override fun schedule(retentionDays: Int) = Unit
        override suspend fun runNow(): Int = 0
    }
}
