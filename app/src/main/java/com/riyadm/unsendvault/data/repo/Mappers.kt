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
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.FieldCipher.Field

internal fun contentKindOf(name: String?): ContentKind =
    ContentKind.entries.firstOrNull { it.name == name } ?: ContentKind.TEXT

internal fun statusOf(name: String?): MessageStatus =
    MessageStatus.entries.firstOrNull { it.name == name } ?: MessageStatus.ACTIVE

internal fun removalReasonOf(name: String?): RemovalReason? =
    name?.let { n -> RemovalReason.entries.firstOrNull { it.name == n } ?: RemovalReason.UNKNOWN }

/** Placeholders produced by the system or by our own filter; they never replace real text. */
internal fun ContentKind.isWithheld(): Boolean =
    this == ContentKind.SYSTEM_REDACTED || this == ContentKind.FILTERED_SENSITIVE

internal fun ConversationEntity.binding(): String = FieldCipher.forConversation(packageName, conversationIndex)

internal fun ConversationEntity.toDomain(cipher: FieldCipher): Conversation {
    val owner = binding()
    return Conversation(
        id = id,
        packageName = packageName,
        appLabel = appLabel,
        conversationKey = cipher.decrypt(conversationKey, Field.CONVERSATION_KEY, owner),
        title = cipher.decrypt(title, Field.CONVERSATION_TITLE, owner),
        isGroup = isGroup,
        lastMessageAt = lastMessageAt,
        messageCount = messageCount,
        lastMessagePreview = cipher.decryptNullable(lastMessagePreview, Field.CONVERSATION_PREVIEW, owner),
    )
}

internal fun MessageRow.decryptTitle(cipher: FieldCipher): String =
    cipher.decrypt(conversationTitle, Field.CONVERSATION_TITLE, FieldCipher.forConversation(message.packageName, conversationIndex))

/**
 * Decrypts a joined row. Values the caller already decrypted (search) are passed in so no field is decrypted twice.
 */
internal fun MessageRow.toDomain(
    cipher: FieldCipher,
    text: String? = null,
    sender: String? = null,
    senderKnown: Boolean = false,
    title: String? = null,
): ArchivedMessage {
    val m = message
    val owner = FieldCipher.forMessage(m.conversationId)
    val marker = m.deletionMarkerText
    val evidence = if (m.deletionAdapterId != null && marker != null && m.deletionObservedAt != null) {
        DeletionEvidence(m.deletionAdapterId, cipher.decrypt(marker, Field.MESSAGE_MARKER, owner), m.deletionObservedAt)
    } else {
        null
    }
    return ArchivedMessage(
        id = m.id,
        conversationId = m.conversationId,
        packageName = m.packageName,
        appLabel = appLabel,
        conversationTitle = title ?: decryptTitle(cipher),
        sender = if (senderKnown) sender else cipher.decryptNullable(m.sender, Field.MESSAGE_SENDER, owner),
        text = text ?: cipher.decrypt(m.text, Field.MESSAGE_TEXT, owner),
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

internal fun MessageRevisionEntity.toDomain(cipher: FieldCipher) = MessageRevision(
    id = id,
    messageId = messageId,
    text = cipher.decrypt(text, Field.REVISION_TEXT, FieldCipher.forRevision(messageId)),
    capturedAt = capturedAt,
)

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
