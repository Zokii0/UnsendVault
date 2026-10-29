package com.riyadm.unsendvault.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** One chat/thread inside a source app. (package_name, conversation_key) is unique. */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["package_name", "conversation_key"], unique = true),
        Index(value = ["last_message_at"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    @ColumnInfo(name = "conversation_key") val conversationKey: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "is_group") val isGroup: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String?,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "message_timestamp"]),
        Index(value = ["package_name", "message_timestamp"]),
        Index(value = ["is_favorite"]),
        Index(value = ["status"]),
        // Identity lookups used by ingest/import dedupe.
        Index(value = ["conversation_id", "sender_key", "message_timestamp"]),
        Index(value = ["conversation_id", "sender_key", "normalized_text"]),
        // v2: removal-event lookups.
        Index(value = ["last_notification_key"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    /** Denormalized from the conversation so per-app filters can use an index. */
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "sender") val sender: String?,
    /** `sender ?: ""`, trimmed; NOT NULL so identity lookups can use plain equality and an index. */
    @ColumnInfo(name = "sender_key") val senderKey: String,
    @ColumnInfo(name = "text") val text: String,
    /** [com.riyadm.unsendvault.data.repo.TextNormalizer] output, used for imprecise identity. */
    @ColumnInfo(name = "normalized_text") val normalizedText: String,
    /** [com.riyadm.unsendvault.core.model.ContentKind] name. */
    @ColumnInfo(name = "content_kind") val contentKind: String,
    @ColumnInfo(name = "message_timestamp") val messageTimestamp: Long,
    @ColumnInfo(name = "has_precise_timestamp") val hasPreciseTimestamp: Boolean,
    @ColumnInfo(name = "first_captured_at") val firstCapturedAt: Long,
    @ColumnInfo(name = "last_updated_at") val lastUpdatedAt: Long,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    /** [com.riyadm.unsendvault.core.model.MessageStatus] name. */
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "deletion_adapter_id") val deletionAdapterId: String?,
    @ColumnInfo(name = "deletion_marker_text") val deletionMarkerText: String?,
    @ColumnInfo(name = "deletion_observed_at") val deletionObservedAt: Long?,
    @ColumnInfo(name = "revision_count") val revisionCount: Int,
    /** v2: StatusBarNotification key of the notification that last carried this message. */
    @ColumnInfo(name = "last_notification_key") val lastNotificationKey: String? = null,
    /** v2: [com.riyadm.unsendvault.core.model.RemovalReason] name of the last removal of that notification. */
    @ColumnInfo(name = "last_removal_reason") val lastRemovalReason: String? = null,
    /** v2 */
    @ColumnInfo(name = "last_removed_at") val lastRemovedAt: Long? = null,
)

/**
 * v2: external-content FTS4 index over messages(text, sender). Room generates the triggers that keep it in sync with
 * `messages`. unicode61 folds case/diacritics for non-ASCII scripts; CJK (no word breaks) and 1–2 character queries
 * use a LIKE fallback instead (see [com.riyadm.unsendvault.data.repo.SearchSql]).
 */
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "sender") val sender: String?,
)

/** A previous text version of a message (update history). */
@Entity(
    tableName = "message_revisions",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["message_id"])],
)
data class MessageRevisionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "content_kind") val contentKind: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
)
