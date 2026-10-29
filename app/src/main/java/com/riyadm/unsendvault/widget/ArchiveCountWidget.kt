package com.riyadm.unsendvault.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.riyadm.unsendvault.R

/** Archive count: total messages, conversations and apps. Numbers only, in every privacy mode. */
class ArchiveCountWidget : VaultGlanceWidget<CountDisplay>(WidgetKind.COUNT, SIZES) {
    override suspend fun load(context: Context): WidgetLoad<CountDisplay> =
        WidgetLoaders.containerOrNull(context)?.let { WidgetLoaders.count(it.vaultRepository, it.settingsRepository) }
            ?: WidgetLoad.Failed

    override fun previewData(): WidgetLoad<CountDisplay> = WidgetSamples.count

    @Composable
    override fun Content(load: WidgetLoad<CountDisplay>) = ArchiveCountContent(load)

    companion object {
        val SIZES = setOf(DpSize(110.dp, 40.dp), DpSize(180.dp, 40.dp), DpSize(110.dp, 100.dp), DpSize(180.dp, 100.dp))
    }
}

@Composable
fun ArchiveCountContent(load: WidgetLoad<CountDisplay>) {
    val size = LocalSize.current
    val compact = size.height < 100.dp
    val wide = size.width >= 180.dp
    if (load !is WidgetLoad.Ready) {
        FailedContent(compact)
        return
    }
    val d = load.value
    val open = WidgetIntents.openDestination(WidgetIntents.DESTINATION_HOME)
    if (d.totalMessages == 0 && !d.locked) {
        WidgetRoot(onClick = open) {
            if (compact) {
                StatusMessage(title = str(R.string.widget_empty_title))
            } else {
                StatusMessage(str(R.string.widget_empty_title), str(R.string.widget_empty_hint), R.drawable.widget_ic_vault)
            }
        }
        return
    }

    val context = LocalContext.current
    val palette = widgetPalette()
    val total = formatCount(d.totalMessages)
    val label = context.resources.getQuantityString(R.plurals.widget_messages_label, d.totalMessages)
    val conversations = countText(R.plurals.widget_conversations_count, d.conversations)
    val apps = countText(R.plurals.widget_apps_count, d.apps)
    val lockDescription = str(R.string.widget_icon_lock)
    val description = str(R.string.widget_count_a11y, "$total $label", conversations, apps) +
        if (d.locked) " $lockDescription." else ""
    val sep = str(R.string.widget_separator)
    val numberStyle = TextStyle(color = palette.accent, fontSize = if (compact) 22.sp else NumberSize, fontWeight = FontWeight.Bold)
    val secondary = TextStyle(color = palette.onBackgroundVariant, fontSize = 12.sp)

    WidgetRoot(onClick = open, contentDescription = description) {
        if (compact) {
            Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(total, style = numberStyle, maxLines = 1)
                Spacer(GlanceModifier.width(8.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(label, style = secondary, maxLines = 1)
                    if (wide) Text(conversations + sep + apps, style = secondary, maxLines = 1)
                }
                if (d.locked) WidgetIcon(R.drawable.widget_ic_lock, lockDescription, palette.onBackgroundVariant, 16.dp)
            }
        } else {
            WidgetHeader(
                icon = R.drawable.widget_ic_vault,
                title = str(R.string.widget_app_name),
                trailingIcon = if (d.locked) R.drawable.widget_ic_lock else null,
                trailingIconDescription = lockDescription,
            )
            Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Text(total, style = numberStyle, maxLines = 1)
                Text(label, style = secondary, maxLines = 1)
                if (wide) {
                    Text(conversations + sep + apps, style = secondary, maxLines = 1)
                } else {
                    Text(conversations, style = secondary, maxLines = 1)
                    Text(apps, style = secondary, maxLines = 1)
                }
            }
        }
    }
}
