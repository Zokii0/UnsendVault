package com.riyadm.unsendvault.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** A message joined with the conversation fields the list UI needs (title still encrypted). */
data class MessageRow(
    @Embedded val message: MessageEntity,
    @ColumnInfo(name = "app_label") val appLabel: String,
    @ColumnInfo(name = "conversation_title") val conversationTitle: ByteArray,
    /** AAD owner of [conversationTitle], with the message's package name. */
    @ColumnInfo(name = "conversation_index") val conversationIndex: String,
)

/** Newest message text of a conversation, for re-encrypting conversations.last_message_preview. */
data class LatestTextRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "conversation_index") val conversationIndex: String,
    @ColumnInfo(name = "text") val text: ByteArray?,
)

data class AppSummaryRow(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String?,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "conversation_count") val conversationCount: Int,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long?,
)

data class StatsRow(
    @ColumnInfo(name = "total_messages") val totalMessages: Int,
    @ColumnInfo(name = "total_conversations") val totalConversations: Int,
    @ColumnInfo(name = "favorites") val favorites: Int,
    @ColumnInfo(name = "possibly_deleted") val possiblyDeleted: Int,
    @ColumnInfo(name = "apps") val apps: Int,
    @ColumnInfo(name = "oldest_captured_at") val oldestCapturedAt: Long?,
    @ColumnInfo(name = "newest_captured_at") val newestCapturedAt: Long?,
)

/** The columns the search scan needs per row (content still encrypted). */
data class ScanRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "message_timestamp") val messageTimestamp: Long,
    @ColumnInfo(name = "sender_index") val senderIndex: String,
    @ColumnInfo(name = "text") val text: ByteArray,
    @ColumnInfo(name = "sender") val sender: ByteArray?,
)
