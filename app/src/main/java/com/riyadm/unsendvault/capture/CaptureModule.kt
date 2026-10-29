package com.riyadm.unsendvault.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.riyadm.unsendvault.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Capture engine used by [VaultNotificationListener]. SKELETON STUB — replaced by the capture layer. */
interface CaptureEngine {
    fun onPosted(snapshot: NotificationSnapshot)
    fun onRemoved(event: RemovalEvent)
}

object CaptureModule {
    fun createEngine(container: AppContainer): CaptureEngine = object : CaptureEngine {
        override fun onPosted(snapshot: NotificationSnapshot) = Unit
        override fun onRemoved(event: RemovalEvent) = Unit
    }

    fun createListenerStatus(context: Context): ListenerStatus = object : ListenerStatus {
        private val app = context.applicationContext
        override fun isAccessGranted(): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
        override val connected: StateFlow<Boolean> = MutableStateFlow(false)
        override fun requestRebind() = Unit
        override fun accessSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                ComponentName(app, VaultNotificationListener::class.java).flattenToString())
    }
}
