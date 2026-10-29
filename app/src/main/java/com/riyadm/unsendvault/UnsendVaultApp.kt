package com.riyadm.unsendvault

import android.app.Application
import android.content.Context
import com.riyadm.unsendvault.di.AppContainer
import com.riyadm.unsendvault.widget.WidgetModule

class UnsendVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WidgetModule.startSync(container)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as UnsendVaultApp).container
