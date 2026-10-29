package com.riyadm.unsendvault.data.repo

import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.DeletionEvidence
import com.riyadm.unsendvault.core.model.MessageRevision
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.core.model.VaultStats
import com.riyadm.unsendvault.data.db.AppSummaryRow
import com.riyadm.unsendvault.data.db.ConversationEntity
import com.riyadm.unsendvault.data.db.MessageRevisionEntity
import com.riyadm.unsendvault.data.db.MessageRow
import com.riyadm.unsendvault.data.db.StatsRow

internal fun contentKindOf(name: String?): ContentKind =
    ContentKind.entries.firstOrNull { it.name == name } ?: ContentKind.TEXT

internal fun statusOf(name: String?): MessageStatus =
    MessageStatus.entries.firstOrNull { it.name == name } ?: MessageStatus.ACTIVE

internal fun removalReasonOf(name: String?): RemovalReason? =
    name?.let { n -> RemovalReason.entries.firstOrNull { it.name == n } ?: RemovalReason.UNKNOWN }

/** Placeholders produced by the system or by our own filter; they never replace real text. */
internal fun ContentKind.isWithheld(): Boolean =
    this == ContentKind.SYSTEM_REDACTED || this == ContentKind.FILTERED_SENSITIVE

internal fun ConversationEntity.toDomain() = Conversation(
    id = id,
    packageName = packageName,
    appLabel = appLabel,
    conversationKey = conversationKey,
    title = title,
    isGroup = isGroup,
    lastMessageAt = lastMessageAt,
    messageCount = messageCount,
    lastMessagePreview = lastMessagePreview,
)

internal fun MessageRow.toDomain(): ArchivedMessage {
    val m = message
    val evidence = if (m.deletionAdapterId != null && m.deletionMarkerText != null && m.deletionObservedAt != null) {
        DeletionEvidence(m.deletionAdapterId, m.deletionMarkerText, m.deletionObservedAt)
    } else {
        null
    }
    return ArchivedMessage(
        id = m.id,
        conversationId = m.conversationId,
        packageName = m.packageName,
        appLabel = appLabel,
        conversationTitle = conversationTitle,
        sender = m.sender,
        text = m.text,
        contentKind = contentKindOf(m.contentKind),
        messageTimestamp = m.messageTimestamp,
        firstCapturedAt = m.firstCapturedAt,
        lastUpdatedAt = m.lastUpdatedAt,
        isFavorite = m.isFavorite,
        status = statusOf(m.status),
        deletionEvidence = evidence,
        revisionCount = m.revisionCount,
        lastRemovalReason = removalReasonOf(m.lastRemovalReason),
        lastRemovedAt = m.lastRemovedAt,
    )
}

internal fun MessageRevisionEntity.toDomain() = MessageRevision(id = id, messageId = messageId, text = text, capturedAt = capturedAt)

internal fun AppSummaryRow.toDomain() = AppSummary(
    packageName = packageName,
    appLabel = appLabel ?: packageName,
    messageCount = messageCount,
    conversationCount = conversationCount,
    lastMessageAt = lastMessageAt,
)

internal fun StatsRow.toDomain() = VaultStats(
    totalMessages = totalMessages,
    totalConversations = totalConversations,
    favorites = favorites,
    possiblyDeleted = possiblyDeleted,
    apps = apps,
    oldestCapturedAt = oldestCapturedAt,
    newestCapturedAt = newestCapturedAt,
)
