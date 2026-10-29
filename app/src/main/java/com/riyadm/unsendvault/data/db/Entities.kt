package com.riyadm.unsendvault.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v3: field-level encryption (see com.riyadm.unsendvault.data.security.FieldCipher).
 *  - ByteArray columns are AES-256-GCM ciphertexts; the AAD binds the column and the row's owner.
 *  - *_index columns are blind indexes (truncated HMAC-SHA256, base64url) used only for equality lookups.
 *  - Everything else (package names, app labels, timestamps, flags, status, content kind, counts, removal reason)
 *    is stored in plaintext by design.
 * The data classes hold arrays, so their equals() compares array references: only use it to detect "unchanged copy".
 */

/** One chat/thread inside a source app. (package_name, conversation_index) is unique. */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["package_name", "conversation_index"], unique = true),
        Index(value = ["last_message_at"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    /** Blind index of the capture layer's conversation key. */
    @ColumnInfo(name = "conversation_index") val conversationIndex: String,
    /** Encrypted conversation key (needed for backups). */
    @ColumnInfo(name = "conversation_key") val conversationKey: ByteArray,
    /** Encrypted. */
    @ColumnInfo(name = "title") val title: ByteArray,
    @ColumnInfo(name = "is_group") val isGroup: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    /** Encrypted copy of the newest message's text. */
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: ByteArray?,
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
        // v3: unfiltered newest-first scans (search pages, recent list, retention).
        Index(value = ["message_timestamp"]),
        Index(value = ["is_favorite"]),
        Index(value = ["status"]),
        // Identity lookups used by ingest/import dedupe.
        Index(value = ["conversation_id", "sender_index", "message_timestamp"]),
        Index(value = ["conversation_id", "sender_index", "text_index"]),
        // Removal-event lookups.
        Index(value = ["last_notification_index"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    /** Denormalized from the conversation so per-app filters can use an index. */
    @ColumnInfo(name = "package_name") val packageName: String,
    /** Encrypted; null when the notification named no sender. */
    @ColumnInfo(name = "sender") val sender: ByteArray?,
    /** Blind index of `TextNormalizer.senderKey(sender)`; never null so identity lookups can use plain equality. */
    @ColumnInfo(name = "sender_index") val senderIndex: String,
    /** Encrypted. */
    @ColumnInfo(name = "text") val text: ByteArray,
    /** Blind index of `TextNormalizer.normalize(text)`, used for "same text" identity. */
    @ColumnInfo(name = "text_index") val textIndex: String,
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
    /** Encrypted. */
    @ColumnInfo(name = "deletion_marker_text") val deletionMarkerText: ByteArray?,
    @ColumnInfo(name = "deletion_observed_at") val deletionObservedAt: Long?,
    @ColumnInfo(name = "revision_count") val revisionCount: Int,
    /** Blind index of the StatusBarNotification key that last carried this message. */
    @ColumnInfo(name = "last_notification_index") val lastNotificationIndex: String? = null,
    /** [com.riyadm.unsendvault.core.model.RemovalReason] name of the last removal of that notification. */
    @ColumnInfo(name = "last_removal_reason") val lastRemovalReason: String? = null,
    @ColumnInfo(name = "last_removed_at") val lastRemovedAt: Long? = null,
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
    /** Encrypted. */
    @ColumnInfo(name = "text") val text: ByteArray,
    @ColumnInfo(name = "content_kind") val contentKind: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
)

/** v3: small key/value table; holds the key-check value that detects a database encrypted under a different key. */
@Entity(tableName = "vault_meta")
data class VaultMetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: ByteArray,
)
