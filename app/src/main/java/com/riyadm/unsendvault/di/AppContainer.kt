package com.riyadm.unsendvault.di

import android.content.Context
import com.riyadm.unsendvault.capture.CaptureEngine
import com.riyadm.unsendvault.capture.CaptureModule
import com.riyadm.unsendvault.capture.CapturePauseController
import com.riyadm.unsendvault.capture.CaptureRuntime
import com.riyadm.unsendvault.capture.CaptureSettingsCache
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
 * Everything is lazy so a cold start triggered by the notification listener only builds what capture needs:
 * [capture] (adapters, engine, pre-filter), [settingsRepository] (DataStore, read once into [captureSettings]) and —
 * only when something is actually archived — [vaultRepository]. Retention, backup, widgets and UI stay untouched.
 */
class AppContainer(context: Context) {
    val context: Context = context.applicationContext

    /** Application-lifetime scope for background work. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Creating the repository also starts the single capture-side settings collector, so every path that can change
     * settings (UI, widget toggle, backup import) keeps [captureSettings] and the pause/unbind logic in sync.
     */
    val settingsRepository: SettingsRepository by lazy {
        DataModule.createSettingsRepository(this.context, this).also { captureSettings.attach(it.settings, appScope) }
    }

    /** In-memory settings for the listener hot path (null until DataStore's first read). */
    val captureSettings: CaptureSettingsCache by lazy {
        CaptureSettingsCache(onCaptureEnabledChanged = { enabled -> pauseController.onCaptureEnabledChanged(enabled) })
    }
    val pauseController: CapturePauseController by lazy { CaptureModule.createPauseController(this.context) }
    val vaultRepository: VaultRepository by lazy { DataModule.createVaultRepository(this.context) }
    val backupManager: BackupManager by lazy { DataModule.createBackupManager(this.context, this) }
    val retentionScheduler: RetentionScheduler by lazy { DataModule.createRetentionScheduler(this.context, this) }
    val listenerStatus: ListenerStatus by lazy { CaptureModule.createListenerStatus(this.context) }
    val capture: CaptureRuntime by lazy { CaptureModule.createRuntime(this) }
    val captureEngine: CaptureEngine get() = capture.engine
    val widgetRefresher: WidgetRefresher by lazy { WidgetModule.createRefresher(this.context) }
}
