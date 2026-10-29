package com.riyadm.unsendvault.widget

import com.riyadm.unsendvault.core.model.AppSummary

/** The five widget types. Pure Kotlin so the sync engine and privacy rules can be tested on the JVM. */
enum class WidgetKind { COUNT, RECENT, FAVORITES, APP_SUMMARY, CAPTURE }

/** How much a widget may reveal. Computed only by [WidgetPrivacy.mode]. */
enum class WidgetPrivacyMode {
    /** Default: counts, app names and relative times. Never message text, senders or conversation titles. */
    METADATA,

    /** The user enabled "show content in widgets" and the app lock is off: sender + truncated preview. */
    CONTENT,

    /** The app lock is enabled: counts only plus a lock hint. */
    LOCKED,
}

/** Result of a one-shot widget data read. [now] is the wall-clock time relative times are computed against. */
sealed interface WidgetLoad<out T> {
    data class Ready<T>(val value: T, val now: Long) : WidgetLoad<T>

    /** Repository unavailable, timed out or threw. The widget shows "Open UnsendVault". */
    data object Failed : WidgetLoad<Nothing>
}

data class CountDisplay(
    val totalMessages: Int,
    val conversations: Int,
    val apps: Int,
    val locked: Boolean,
)

/** One app's slice of the loaded recent/favorite window (metadata mode). */
data class WidgetAppGroupRow(
    val packageName: String,
    val appLabel: String,
    val messageCount: Int,
    val latestAt: Long,
)

/** One message (content mode). [title]/[preview] are null whenever content must not be shown for this row. */
data class WidgetMessageRow(
    val messageId: Long,
    val packageName: String,
    val appLabel: String,
    val timestamp: Long,
    val title: String?,
    val preview: String?,
    /** System-redacted or filtered-sensitive content: rendered as "Content hidden", never as text. */
    val contentHidden: Boolean,
)

sealed interface MessageListDisplay {
    /** App lock on: a single count (total archived for Recent, favorites for Favorites). */
    data class Locked(val count: Int) : MessageListDisplay
    data class ByApp(val groups: List<WidgetAppGroupRow>) : MessageListDisplay
    data class Messages(val rows: List<WidgetMessageRow>) : MessageListDisplay
    data object Empty : MessageListDisplay
}

sealed interface AppSummaryDisplay {
    data class Locked(val totalMessages: Int, val apps: Int) : AppSummaryDisplay
    data class Apps(val rows: List<AppSummary>) : AppSummaryDisplay
    data object Empty : AppSummaryDisplay
}

enum class CaptureState { ACTIVE, PAUSED, ACCESS_NEEDED, DISCONNECTED }
