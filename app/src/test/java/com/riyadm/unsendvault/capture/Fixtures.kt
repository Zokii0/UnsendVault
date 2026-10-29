package com.riyadm.unsendvault.capture

/** Synthetic notification fixtures for JVM tests. All names are made up. */
object Fixtures {
    const val T0 = 1_700_000_000_000L
    const val MESSAGING_TEMPLATE = "android.app.Notification\$MessagingStyle"
    const val INBOX_TEMPLATE = "android.app.Notification\$InboxStyle"
    const val BIG_TEXT_TEMPLATE = "android.app.Notification\$BigTextStyle"

    fun snapshot(
        packageName: String = "com.example.chat",
        key: String = "0|$packageName|1|null|10001",
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        textLines: List<String> = emptyList(),
        conversationTitle: String? = null,
        isGroupConversation: Boolean = false,
        selfName: String? = null,
        messages: List<SnapshotMessage> = emptyList(),
        historicMessages: List<SnapshotMessage> = emptyList(),
        template: String? = if (messages.isNotEmpty()) MESSAGING_TEMPLATE else null,
        category: String? = null,
        channelId: String? = null,
        shortcutId: String? = null,
        isGroupSummary: Boolean = false,
        isOngoing: Boolean = false,
        postTime: Long = T0 + 60_000,
        whenTime: Long = 0L,
        tickerText: String? = null,
        looksSystemRedacted: Boolean = false,
    ) = NotificationSnapshot(
        packageName = packageName,
        key = key,
        id = 1,
        tag = null,
        postTime = postTime,
        whenTime = whenTime,
        category = category,
        channelId = channelId,
        groupKey = null,
        isGroupSummary = isGroupSummary,
        isOngoing = isOngoing,
        shortcutId = shortcutId,
        template = template,
        title = title,
        text = text,
        bigText = bigText,
        subText = null,
        summaryText = null,
        textLines = textLines,
        conversationTitle = conversationTitle,
        isGroupConversation = isGroupConversation,
        selfName = selfName,
        messages = messages,
        historicMessages = historicMessages,
        tickerText = tickerText,
        looksSystemRedacted = looksSystemRedacted,
    )

    fun msg(sender: String?, text: String?, timestamp: Long, mime: String? = null) =
        SnapshotMessage(sender = sender, text = text, timestamp = timestamp, dataMimeType = mime)
}
