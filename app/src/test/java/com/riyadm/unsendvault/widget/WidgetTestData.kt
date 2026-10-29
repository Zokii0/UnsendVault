package com.riyadm.unsendvault.widget

import com.riyadm.unsendvault.capture.ListenerStatus
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.MessageStatus
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object WidgetTestData {
    const val NOW = 1_767_225_600_000L
    const val MIN = 60_000L

    const val SECRET_TEXT = "the vault code is 4417"
    const val SENDER = "Alice Example"
    const val GROUP_TITLE = "Family Group"
    const val REDACTED_TEXT = "redacted payload"
    const val FILTERED_TEXT = "filtered payload"

    fun message(
        id: Long,
        packageName: String = "com.whatsapp",
        appLabel: String = "WhatsApp",
        sender: String? = SENDER,
        text: String = SECRET_TEXT,
        kind: ContentKind = ContentKind.TEXT,
        timestamp: Long = NOW - id * MIN,
        favorite: Boolean = false,
    ) = ArchivedMessage(
        id = id,
        conversationId = id,
        packageName = packageName,
        appLabel = appLabel,
        conversationTitle = GROUP_TITLE,
        sender = sender,
        text = text,
        contentKind = kind,
        messageTimestamp = timestamp,
        firstCapturedAt = timestamp,
        lastUpdatedAt = timestamp,
        isFavorite = favorite,
        status = MessageStatus.ACTIVE,
        deletionEvidence = null,
        revisionCount = 1,
        lastRemovalReason = null,
        lastRemovedAt = null,
    )

    /** Newest first: two WhatsApp, one Signal, one system-redacted and one filtered message. */
    val messages = listOf(
        message(1),
        message(2, packageName = "org.thoughtcrime.securesms", appLabel = "Signal", sender = "Bob Sample", text = "meet at 6"),
        message(3, sender = "Carol Redacted", text = REDACTED_TEXT, kind = ContentKind.SYSTEM_REDACTED),
        message(4, sender = "Dave Filtered", text = FILTERED_TEXT, kind = ContentKind.FILTERED_SENSITIVE),
        message(5, text = "older text"),
    )
}

internal class FakeListenerStatus(
    var granted: Boolean = true,
    connected: Boolean = true,
) : ListenerStatus {
    val connectedState = MutableStateFlow(connected)
    var rebindRequests = 0
    override fun isAccessGranted(): Boolean = granted
    override val connected: StateFlow<Boolean> = connectedState
    override fun requestRebind() {
        rebindRequests++
    }
    override fun accessSettingsIntent(): Intent = Intent()
}
