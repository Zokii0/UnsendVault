package com.riyadm.unsendvault.widget

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import com.riyadm.unsendvault.R

/** Static configuration shared by the Recent and Favorites widgets. */
enum class MessageListType(
    val kind: WidgetKind,
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    @StringRes val emptyTitle: Int,
    @PluralsRes val lockedCount: Int,
    @PluralsRes val groupCount: Int,
    val destination: String,
) {
    RECENT(
        WidgetKind.RECENT, R.string.widget_recent_title, R.drawable.widget_ic_history, R.string.widget_empty_title,
        R.plurals.widget_messages_count, R.plurals.widget_recent_count, WidgetIntents.DESTINATION_RECENT,
    ),
    FAVORITES(
        WidgetKind.FAVORITES, R.string.widget_favorites_title, R.drawable.widget_ic_star, R.string.widget_empty_favorites,
        R.plurals.widget_favorites_count, R.plurals.widget_favorites_count, WidgetIntents.DESTINATION_FAVORITES,
    ),
}

internal val LIST_SIZES = setOf(
    DpSize(110.dp, 110.dp), DpSize(180.dp, 110.dp), DpSize(250.dp, 110.dp),
    DpSize(110.dp, 180.dp), DpSize(250.dp, 180.dp), DpSize(250.dp, 260.dp),
)

/** Latest archived messages. Metadata mode groups them per app; content mode lists sender + preview. */
class RecentWidget : VaultGlanceWidget<MessageListDisplay>(WidgetKind.RECENT, LIST_SIZES) {
    override suspend fun load(context: Context): WidgetLoad<MessageListDisplay> =
        WidgetLoaders.containerOrNull(context)?.let { WidgetLoaders.recent(it.vaultRepository, it.settingsRepository) }
            ?: WidgetLoad.Failed

    override fun previewData(): WidgetLoad<MessageListDisplay> = WidgetSamples.recent

    @Composable
    override fun Content(load: WidgetLoad<MessageListDisplay>) = MessageListContent(MessageListType.RECENT, load)
}

/** Latest favorite messages, with the same privacy rules as [RecentWidget]. */
class FavoritesWidget : VaultGlanceWidget<MessageListDisplay>(WidgetKind.FAVORITES, LIST_SIZES) {
    override suspend fun load(context: Context): WidgetLoad<MessageListDisplay> =
        WidgetLoaders.containerOrNull(context)?.let { WidgetLoaders.favorites(it.vaultRepository, it.settingsRepository) }
            ?: WidgetLoad.Failed

    override fun previewData(): WidgetLoad<MessageListDisplay> = WidgetSamples.favorites

    @Composable
    override fun Content(load: WidgetLoad<MessageListDisplay>) = MessageListContent(MessageListType.FAVORITES, load)
}

/** Rows that fit below the header at the current size and font scale. */
@Composable
internal fun visibleRows(rowHeight: Dp, max: Int = 8): Int {
    val available = LocalSize.current.height - WidgetPadding * 2 - 26.dp
    return (available.value / (rowHeight.value * fontScale())).toInt().coerceIn(1, max)
}

@Composable
fun MessageListContent(type: MessageListType, load: WidgetLoad<MessageListDisplay>) {
    if (load !is WidgetLoad.Ready) {
        FailedContent()
        return
    }
    val context = LocalContext.current
    val wide = LocalSize.current.width >= 180.dp
    val display = load.value
    val sep = str(R.string.widget_separator)
    val locked = display is MessageListDisplay.Locked

    WidgetRoot(onClick = WidgetIntents.openDestination(type.destination)) {
        WidgetHeader(
            icon = type.icon,
            title = str(type.title),
            trailingIcon = if (locked) R.drawable.widget_ic_lock else null,
            trailingIconDescription = str(R.string.widget_icon_lock),
        )
        when (display) {
            is MessageListDisplay.Locked ->
                StatusMessage(title = countText(type.lockedCount, display.count), subtitle = str(R.string.widget_locked_hint))

            MessageListDisplay.Empty ->
                StatusMessage(title = str(type.emptyTitle), subtitle = str(R.string.widget_empty_hint))

            is MessageListDisplay.ByApp -> display.groups.take(visibleRows(24.dp)).forEach { group ->
                val time = relativeTime(context, group.latestAt, load.now)
                val count = countText(type.groupCount, group.messageCount)
                ListRow(
                    label = group.appLabel,
                    trailing = if (wide) count + sep + time else time,
                    onClick = WidgetIntents.openApp(group.packageName),
                    contentDescription = listOf(group.appLabel, count, time).joinToString(", "),
                )
            }

            is MessageListDisplay.Messages -> display.rows.take(visibleRows(40.dp)).forEach { row ->
                val time = relativeTime(context, row.timestamp, load.now)
                val label = row.title ?: row.appLabel
                val body = if (row.contentHidden) str(R.string.widget_content_hidden) else row.preview
                val secondLine = listOfNotNull(row.appLabel.takeIf { row.title != null }, body).joinToString(sep).ifEmpty { null }
                ListRow(
                    label = label,
                    trailing = time,
                    onClick = WidgetIntents.openMessage(row.messageId),
                    contentDescription = listOfNotNull(label, secondLine, time).joinToString(", "),
                    secondLine = secondLine,
                    secondLineMuted = row.contentHidden,
                )
            }
        }
    }
}
