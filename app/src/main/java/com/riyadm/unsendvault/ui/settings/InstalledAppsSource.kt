package com.riyadm.unsendvault.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.compose.runtime.Immutable
import com.riyadm.unsendvault.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class InstalledApp(val packageName: String, val label: String, val otherProfile: Boolean = false)

/** Lists launchable apps (current profile and its work/other profiles) for the app pickers. */
fun interface InstalledAppsSource {
    suspend fun launcherApps(): List<InstalledApp>
}

/**
 * Uses the launcher-intent `<queries>` entry in the manifest (no QUERY_ALL_PACKAGES). Runs on the IO dispatcher.
 *
 * PackageManager only reports the current profile, so apps in a work profile, Dual Messenger or Secure Folder would be
 * missing even though their notifications still reach the listener. The other profiles of the same user are therefore
 * read through [LauncherApps], which needs no extra permission. An app present in both profiles is listed once, under
 * the current profile; other-profile entries are marked in their label. Failure to read another profile is ignored.
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
        val found = LinkedHashMap<String, InstalledApp>()
        activities.asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != app.packageName }
            .forEach { info -> found.putIfAbsent(info.packageName, InstalledApp(info.packageName, pm.getApplicationLabel(info).toString())) }

        runCatching {
            val launcher = app.getSystemService(LauncherApps::class.java)
            val me = Process.myUserHandle()
            for (profile in launcher.profiles) {
                if (profile == me) continue
                val inProfile = runCatching { launcher.getActivityList(null, profile) }.getOrDefault(emptyList())
                for (activity in inProfile) {
                    val pkg = activity.applicationInfo.packageName
                    if (pkg == app.packageName || pkg in found) continue
                    found[pkg] = InstalledApp(pkg, app.getString(R.string.installed_app_other_profile, activity.label.toString()), otherProfile = true)
                }
            }
        }
        found.values.sortedBy { it.label.lowercase() }
    }
}
