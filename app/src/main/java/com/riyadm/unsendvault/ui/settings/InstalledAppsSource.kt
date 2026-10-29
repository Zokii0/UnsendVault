package com.riyadm.unsendvault.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class InstalledApp(val packageName: String, val label: String)

/** Lists launchable apps for the exclusion picker. */
fun interface InstalledAppsSource {
    suspend fun launcherApps(): List<InstalledApp>
}

/**
 * Uses the launcher-intent `<queries>` entry in the manifest (no QUERY_ALL_PACKAGES). Runs on the IO dispatcher.
 */
class PackageManagerAppsSource(context: Context) : InstalledAppsSource {
    private val app = context.applicationContext

    override suspend fun launcherApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        activities.asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != app.packageName }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
