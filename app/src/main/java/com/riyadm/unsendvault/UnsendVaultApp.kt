package com.riyadm.unsendvault

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.riyadm.unsendvault.di.AppContainer
import com.riyadm.unsendvault.widget.WidgetModule

/**
 * Process start must stay cheap: the system cold-starts this process for the notification listener (after a reboot or
 * a kill), usually with no UI. onCreate therefore only builds the (lazy) container and starts a background check that
 * begins widget sync if — and only if — widgets are placed. It does not touch DataStore, the database or WorkManager:
 * - retention scheduling is synced when the UI starts ([MainActivity]); the periodic job itself persists in
 *   WorkManager/JobScheduler across process deaths and reboots;
 * - WorkManager is initialized on demand ([Configuration.Provider]; the startup initializer is removed in the
 *   manifest), i.e. only when a widget renders or the retention job runs.
 */
class UnsendVaultApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WidgetModule.startSyncIfPlaced(container)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as UnsendVaultApp).container
