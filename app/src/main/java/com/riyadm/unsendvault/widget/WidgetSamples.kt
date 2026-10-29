package com.riyadm.unsendvault.widget

import com.riyadm.unsendvault.core.model.AppSummary

/** Synthetic, metadata-only data for generated widget-picker previews. Never derived from the user's archive. */
internal object WidgetSamples {
    private const val NOW = 1_767_225_600_000L // fixed instant; previews only show relative times
    private const val MIN = 60_000L

    val count = WidgetLoad.Ready(CountDisplay(totalMessages = 1284, conversations = 96, apps = 7, locked = false), NOW)

    private val groups = listOf(
        WidgetAppGroupRow("sample.messages", "Messages", 4, NOW - 2 * MIN),
        WidgetAppGroupRow("sample.chat", "Chat", 2, NOW - 15 * MIN),
        WidgetAppGroupRow("sample.mail", "Mail", 1, NOW - 60 * MIN),
        WidgetAppGroupRow("sample.social", "Social", 3, NOW - 180 * MIN),
    )

    val recent = WidgetLoad.Ready<MessageListDisplay>(MessageListDisplay.ByApp(groups), NOW)

    val favorites = WidgetLoad.Ready<MessageListDisplay>(
        MessageListDisplay.ByApp(groups.map { it.copy(messageCount = 1, latestAt = it.latestAt - 1440 * MIN) }),
        NOW,
    )

    val apps = WidgetLoad.Ready<AppSummaryDisplay>(
        AppSummaryDisplay.Apps(groups.map { AppSummary(it.packageName, it.appLabel, it.messageCount * 37, it.messageCount * 3, it.latestAt) }),
        NOW,
    )

    val capture = WidgetLoad.Ready(CaptureState.ACTIVE, NOW)
}
