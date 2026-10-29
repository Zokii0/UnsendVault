package com.riyadm.unsendvault.di

import android.content.Context
import com.riyadm.unsendvault.capture.CaptureEngine
import com.riyadm.unsendvault.capture.CaptureModule
import com.riyadm.unsendvault.capture.ListenerStatus
import com.riyadm.unsendvault.data.BackupManager
import com.riyadm.unsendvault.data.DataModule
import com.riyadm.unsendvault.data.RetentionScheduler
import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import com.riyadm.unsendvault.data.WidgetRefresher
import com.riyadm.unsendvault.widget.WidgetModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container (no reflection-based DI, keeps startup and the listener process cheap).
 * Everything is lazy so a cold start triggered by the notification listener only builds what capture needs.
 */
class AppContainer(context: Context) {
    val context: Context = context.applicationContext

    /** Application-lifetime scope for background work. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository: SettingsRepository by lazy { DataModule.createSettingsRepository(this.context, this) }
    val vaultRepository: VaultRepository by lazy { DataModule.createVaultRepository(this.context) }
    val backupManager: BackupManager by lazy { DataModule.createBackupManager(this.context, this) }
    val retentionScheduler: RetentionScheduler by lazy { DataModule.createRetentionScheduler(this.context, this) }
    val listenerStatus: ListenerStatus by lazy { CaptureModule.createListenerStatus(this.context) }
    val captureEngine: CaptureEngine by lazy { CaptureModule.createEngine(this) }
    val widgetRefresher: WidgetRefresher by lazy { WidgetModule.createRefresher(this.context) }
}
