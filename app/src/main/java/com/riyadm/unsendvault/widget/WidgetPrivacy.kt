package com.riyadm.unsendvault.widget

import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.core.model.VaultStats

/**
 * Every decision about what a widget may reveal lives here, so it can be tested without Android.
 *
 * Rules:
 * - App lock enabled -> [WidgetPrivacyMode.LOCKED] (counts + lock hint), regardless of any other setting.
 * - Otherwise content is shown only when the user opted in with [VaultSettings.widgetShowContent].
 * - [ContentKind.SYSTEM_REDACTED] and [ContentKind.FILTERED_SENSITIVE] messages are never shown as content.
 */
object WidgetPrivacy {
    const val TITLE_MAX_CHARS = 48
    const val PREVIEW_MAX_CHARS = 120
    const val MAX_APP_ROWS = 6

    fun mode(settings: VaultSettings): WidgetPrivacyMode = when {
        settings.appLockEnabled -> WidgetPrivacyMode.LOCKED
        settings.widgetShowContent -> WidgetPrivacyMode.CONTENT
        else -> WidgetPrivacyMode.METADATA
    }

    fun isContentDisplayable(kind: ContentKind): Boolean = when (kind) {
        ContentKind.TEXT, ContentKind.MEDIA_PLACEHOLDER -> true
        ContentKind.SYSTEM_REDACTED, ContentKind.FILTERED_SENSITIVE -> false
    }

    fun count(stats: VaultStats, mode: WidgetPrivacyMode) = CountDisplay(
        totalMessages = stats.totalMessages.coerceAtLeast(0),
        conversations = stats.totalConversations.coerceAtLeast(0),
        apps = stats.apps.coerceAtLeast(0),
        locked = mode == WidgetPrivacyMode.LOCKED,
    )

    /**
     * Builds what a Recent/Favorites widget shows. [messages] must be newest first. [lockedCount] is only used in
     * [WidgetPrivacyMode.LOCKED]; callers should not even load messages in that mode.
     */
    fun messageList(messages: List<ArchivedMessage>, mode: WidgetPrivacyMode, lockedCount: Int = 0): MessageListDisplay =
        when {
            mode == WidgetPrivacyMode.LOCKED -> MessageListDisplay.Locked(lockedCount.coerceAtLeast(0))
            messages.isEmpty() -> MessageListDisplay.Empty
            mode == WidgetPrivacyMode.CONTENT -> MessageListDisplay.Messages(messages.map(::contentRow))
            else -> MessageListDisplay.ByApp(groupByApp(messages))
        }

    fun appSummary(summaries: List<AppSummary>, mode: WidgetPrivacyMode, stats: VaultStats?): AppSummaryDisplay = when {
        mode == WidgetPrivacyMode.LOCKED -> AppSummaryDisplay.Locked(stats?.totalMessages ?: 0, stats?.apps ?: 0)
        summaries.isEmpty() -> AppSummaryDisplay.Empty
        else -> AppSummaryDisplay.Apps(
            summaries.asSequence()
                .filter { it.messageCount > 0 }
                .sortedWith(compareByDescending<AppSummary> { it.lastMessageAt ?: Long.MIN_VALUE }.thenByDescending { it.messageCount })
                .take(MAX_APP_ROWS)
                .map { it.copy(appLabel = appLabel(it.appLabel, it.packageName)) }
                .toList(),
        ).takeIf { it.rows.isNotEmpty() } ?: AppSummaryDisplay.Empty
    }

    fun captureState(accessGranted: Boolean, captureEnabled: Boolean, connected: Boolean): CaptureState = when {
        !accessGranted -> CaptureState.ACCESS_NEEDED
        !captureEnabled -> CaptureState.PAUSED
        !connected -> CaptureState.DISCONNECTED
        else -> CaptureState.ACTIVE
    }

    internal fun groupByApp(messages: List<ArchivedMessage>): List<WidgetAppGroupRow> {
        val groups = LinkedHashMap<String, WidgetAppGroupRow>()
        for (m in messages) {
            val existing = groups[m.packageName]
            groups[m.packageName] = if (existing == null) {
                WidgetAppGroupRow(m.packageName, appLabel(m.appLabel, m.packageName), 1, m.messageTimestamp)
            } else {
                existing.copy(messageCount = existing.messageCount + 1, latestAt = maxOf(existing.latestAt, m.messageTimestamp))
            }
        }
        return groups.values.toList()
    }

    private fun contentRow(m: ArchivedMessage): WidgetMessageRow {
        val allowed = isContentDisplayable(m.contentKind)
        return WidgetMessageRow(
            messageId = m.id,
            packageName = m.packageName,
            appLabel = appLabel(m.appLabel, m.packageName),
            timestamp = m.messageTimestamp,
            title = if (allowed) sanitize(m.sender, TITLE_MAX_CHARS) ?: sanitize(m.conversationTitle, TITLE_MAX_CHARS) else null,
            preview = if (allowed) sanitize(m.text, PREVIEW_MAX_CHARS) else null,
            contentHidden = !allowed,
        )
    }

    private fun appLabel(label: String, packageName: String): String =
        sanitize(label, TITLE_MAX_CHARS) ?: sanitize(packageName.substringAfterLast('.'), TITLE_MAX_CHARS) ?: packageName

    // Explicit bidi embeddings/overrides/isolates could reorder surrounding widget text; drop them.
    private val BIDI_CONTROLS = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")
    private val WHITESPACE_OR_CONTROL = Regex("[\\p{Cntrl}\\s\\u2028\\u2029]+")

    /** Single-line, bounded text safe to put in RemoteViews. Returns null for null/blank input. */
    fun sanitize(raw: String?, maxChars: Int): String? {
        if (raw == null) return null
        val cleaned = raw.replace(BIDI_CONTROLS, "").replace(WHITESPACE_OR_CONTROL, " ").trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= maxChars) return cleaned
        var end = (maxChars - 1).coerceAtLeast(1)
        if (Character.isHighSurrogate(cleaned[end - 1])) end--
        return cleaned.substring(0, end).trimEnd() + "…"
    }
}
