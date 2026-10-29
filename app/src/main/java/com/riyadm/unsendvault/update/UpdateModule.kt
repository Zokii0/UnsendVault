package com.riyadm.unsendvault.update

import android.content.Context
import com.riyadm.unsendvault.BuildConfig
import kotlinx.coroutines.CoroutineScope

object UpdateModule {
    fun createController(context: Context, scope: CoroutineScope): UpdateController {
        val store = UpdatePreferences(context.applicationContext.updateCheckDataStore)
        val checker = UpdateChecker(
            source = GitHubReleaseClient(appVersion = BuildConfig.VERSION_NAME),
            store = store,
            currentVersion = BuildConfig.VERSION_NAME,
            isDebugBuild = BuildConfig.DEBUG,
        )
        return UpdateController(checker, store, scope, isDebugBuild = BuildConfig.DEBUG)
    }
}
