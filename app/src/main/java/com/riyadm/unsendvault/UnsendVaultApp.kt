package com.riyadm.unsendvault

import android.app.Application
import android.content.Context
import com.riyadm.unsendvault.di.AppContainer
import com.riyadm.unsendvault.widget.WidgetModule
import kotlinx.coroutines.launch

class UnsendVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WidgetModule.startSync(container)
        // Creating the scheduler starts its watcher, which keeps the daily retention job in sync with the setting.
        container.appScope.launch { container.retentionScheduler }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as UnsendVaultApp).container
