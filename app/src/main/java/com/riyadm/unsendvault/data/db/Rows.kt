package com.riyadm.unsendvault.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** A message joined with the conversation fields the list UI needs. */
data class MessageRow(
    @Embedded val message: MessageEntity,
    @ColumnInfo(name = "app_label") val appLabel: String,
    @ColumnInfo(name = "conversation_title") val conversationTitle: String,
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
