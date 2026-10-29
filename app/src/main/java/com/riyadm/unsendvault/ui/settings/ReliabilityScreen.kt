package com.riyadm.unsendvault.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.ui.common.LocalExternalActivityGuard
import com.riyadm.unsendvault.ui.common.appDetailsIntent
import com.riyadm.unsendvault.ui.common.startFirstAvailable
import com.riyadm.unsendvault.ui.components.AppScaffold

/** Device families with known aggressive background restrictions. */
enum class Vendor { XIAOMI, SAMSUNG, HUAWEI, OPPO, VIVO, ONEPLUS, ASUS, OTHER }

fun vendorOf(manufacturer: String, brand: String = ""): Vendor {
    val m = "${manufacturer.lowercase()} ${brand.lowercase()}"
    return when {
        listOf("xiaomi", "redmi", "poco").any { it in m } -> Vendor.XIAOMI
        "samsung" in m -> Vendor.SAMSUNG
        "huawei" in m || "honor" in m -> Vendor.HUAWEI
        "oneplus" in m -> Vendor.ONEPLUS
        "oppo" in m || "realme" in m -> Vendor.OPPO
        "vivo" in m || "iqoo" in m -> Vendor.VIVO
        "asus" in m -> Vendor.ASUS
        else -> Vendor.OTHER
    }
}

/**
 * Known OEM autostart / background-management screens. These are private activities that change between OS versions,
 * so each is attempted in turn and failures fall back to the app details page.
 */
fun oemAutostartIntents(vendor: Vendor): List<Intent> {
    fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))
    return when (vendor) {
        Vendor.XIAOMI -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
        )
        Vendor.SAMSUNG -> listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        )
        Vendor.HUAWEI -> listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        Vendor.OPPO -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        Vendor.VIVO -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        Vendor.ONEPLUS -> listOf(
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        Vendor.ASUS -> listOf(
            component("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
        )
        Vendor.OTHER -> emptyList()
    }
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) == true

@Composable
fun ReliabilityRoute(accessSettingsIntent: () -> Intent, onBack: () -> Unit) {
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    val vendor = remember { vendorOf(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()) }
    var unrestricted by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = context.isIgnoringBatteryOptimizations() }

    fun open(vararg intents: Intent) {
        guard()
        context.startFirstAvailable(*intents, context.appDetailsIntent())
    }

    AppScaffold(title = stringResource(R.string.reliability_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.reliability_intro), style = MaterialTheme.typography.bodyLarge)
                HelpCard(
                    icon = Icons.Outlined.BatteryAlert,
                    title = stringResource(R.string.reliability_battery_title),
                    body = stringResource(R.string.reliability_battery_body),
                    status = stringResource(if (unrestricted) R.string.reliability_battery_unrestricted else R.string.reliability_battery_optimized),
                    action = stringResource(R.string.reliability_battery_action),
                    onAction = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )
                if (vendor != Vendor.OTHER) {
                    HelpCard(
                        icon = Icons.Outlined.RestartAlt,
                        title = stringResource(R.string.reliability_oem_title, Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }),
                        body = stringResource(
                            when (vendor) {
                                Vendor.XIAOMI -> R.string.reliability_oem_xiaomi
                                Vendor.SAMSUNG -> R.string.reliability_oem_samsung
                                Vendor.HUAWEI -> R.string.reliability_oem_huawei
                                Vendor.OPPO -> R.string.reliability_oem_oppo
                                Vendor.VIVO -> R.string.reliability_oem_vivo
                                Vendor.ONEPLUS -> R.string.reliability_oem_oneplus
                                Vendor.ASUS -> R.string.reliability_oem_asus
                                Vendor.OTHER -> R.string.reliability_oem_generic
                            },
                        ),
                        action = stringResource(R.string.reliability_oem_action),
                        onAction = { open(*oemAutostartIntents(vendor).toTypedArray()) },
                    )
                }
                HelpCard(
                    icon = Icons.Outlined.NotificationsActive,
                    title = stringResource(R.string.reliability_rebind_title),
                    body = stringResource(R.string.reliability_rebind_body),
                    action = stringResource(R.string.reliability_rebind_action),
                    onAction = { open(accessSettingsIntent(), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                )
                Text(stringResource(R.string.reliability_recents_tip), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.reliability_no_guarantee),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HelpCard(
    icon: ImageVector,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    status: String? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(body, style = MaterialTheme.typography.bodyMedium)
                status?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
            OutlinedButton(onClick = onAction) {
                Text(action)
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
