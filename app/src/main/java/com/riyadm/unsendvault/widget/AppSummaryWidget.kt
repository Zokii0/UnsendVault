package com.riyadm.unsendvault.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import com.riyadm.unsendvault.R

/** Per-app message counts for the most recently active apps. */
class AppSummaryWidget : VaultGlanceWidget<AppSummaryDisplay>(WidgetKind.APP_SUMMARY, LIST_SIZES) {
    override suspend fun load(context: Context): WidgetLoad<AppSummaryDisplay> =
        WidgetLoaders.containerOrNull(context)?.let { WidgetLoaders.appSummary(it.vaultRepository, it.settingsRepository) }
            ?: WidgetLoad.Failed

    override fun previewData(): WidgetLoad<AppSummaryDisplay> = WidgetSamples.apps

    @Composable
    override fun Content(load: WidgetLoad<AppSummaryDisplay>) = AppSummaryContent(load)
}

@Composable
fun AppSummaryContent(load: WidgetLoad<AppSummaryDisplay>) {
    if (load !is WidgetLoad.Ready) {
        FailedContent()
        return
    }
    val context = LocalContext.current
    val wide = LocalSize.current.width >= 180.dp
    val display = load.value
    val sep = str(R.string.widget_separator)

    WidgetRoot(onClick = WidgetIntents.openDestination(WidgetIntents.DESTINATION_APPS)) {
        WidgetHeader(
            icon = R.drawable.widget_ic_apps,
            title = str(R.string.widget_apps_title),
            trailingIcon = if (display is AppSummaryDisplay.Locked) R.drawable.widget_ic_lock else null,
            trailingIconDescription = str(R.string.widget_icon_lock),
        )
        when (display) {
            is AppSummaryDisplay.Locked -> StatusMessage(
                title = countText(R.plurals.widget_messages_count, display.totalMessages) + sep +
                    countText(R.plurals.widget_apps_count, display.apps),
                subtitle = str(R.string.widget_locked_hint),
            )

            AppSummaryDisplay.Empty ->
                StatusMessage(title = str(R.string.widget_empty_title), subtitle = str(R.string.widget_empty_hint))

            is AppSummaryDisplay.Apps -> display.rows.take(visibleRows(24.dp)).forEach { app ->
                val count = countText(R.plurals.widget_messages_count, app.messageCount)
                val time = app.lastMessageAt?.let { relativeTime(context, it, load.now) }
                ListRow(
                    label = app.appLabel,
                    trailing = if (wide) listOfNotNull(count, time).joinToString(sep) else formatCount(app.messageCount),
                    onClick = WidgetIntents.openApp(app.packageName),
                    contentDescription = listOfNotNull(app.appLabel, count, time).joinToString(", "),
                )
            }
        }
    }
}
