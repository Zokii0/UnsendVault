package com.riyadm.unsendvault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val MESSAGE_ROW_SELECT =
    "SELECT m.*, c.app_label AS app_label, c.title AS conversation_title " +
        "FROM messages m INNER JOIN conversations c ON c.id = m.conversation_id"

@Dao
interface VaultDao {
    // ---- Conversations --------------------------------------------------------------------------------------------

    @Query("SELECT * FROM conversations WHERE package_name = :packageName AND conversation_key = :conversationKey LIMIT 1")
    suspend fun findConversation(packageName: String, conversationKey: String): ConversationEntity?

    @Insert
    suspend fun insertConversation(entity: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, is_group = :isGroup, app_label = :appLabel WHERE id = :id")
    suspend fun updateConversationMeta(id: Long, title: String, isGroup: Boolean, appLabel: String)

    @Query("SELECT * FROM conversations ORDER BY last_message_at DESC, id DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE package_name IN (:packages) ORDER BY last_message_at DESC, id DESC")
    fun observeConversationsIn(packages: Set<String>): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: Long): Flow<ConversationEntity?>

    /** Recomputes message_count / last_message_at / last_message_preview from the messages table. */
    @Query(
        """
        UPDATE conversations SET
            message_count = (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = conversations.id),
            last_message_at = COALESCE(
                (SELECT MAX(m.message_timestamp) FROM messages m WHERE m.conversation_id = conversations.id),
                last_message_at),
            last_message_preview = (SELECT m.text FROM messages m WHERE m.conversation_id = conversations.id
                ORDER BY m.message_timestamp DESC, m.id DESC LIMIT 1)
        WHERE id IN (:ids)
        """,
    )
    suspend fun recomputeAggregates(ids: List<Long>)

    @Query(
        """
        UPDATE conversations SET
            message_count = (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = conversations.id),
            last_message_at = COALESCE(
                (SELECT MAX(m.message_timestamp) FROM messages m WHERE m.conversation_id = conversations.id),
                last_message_at),
            last_message_preview = (SELECT m.text FROM messages m WHERE m.conversation_id = conversations.id
                ORDER BY m.message_timestamp DESC, m.id DESC LIMIT 1)
        """,
    )
    suspend fun recomputeAllAggregates()

    @Query("DELETE FROM conversations WHERE NOT EXISTS (SELECT 1 FROM messages m WHERE m.conversation_id = conversations.id)")
    suspend fun deleteEmptyConversations(): Int

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long): Int

    @Query("DELETE FROM conversations WHERE package_name = :packageName")
    suspend fun deleteConversationsForPackage(packageName: String): Int

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations(): Int

    @Query("SELECT * FROM conversations ORDER BY id")
    suspend fun allConversations(): List<ConversationEntity>

    // ---- Message identity / ingest --------------------------------------------------------------------------------

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId AND sender_key = :senderKey " +
            "AND message_timestamp = :timestamp ORDER BY id LIMIT 1",
    )
    suspend fun findByPreciseIdentity(conversationId: Long, senderKey: String, timestamp: Long): MessageEntity?

    /** Same text from the same sender close to [timestamp]; [preciseOnly] -1 = any, 0 = imprecise only, 1 = precise only. */
    @Query(
        """
        SELECT * FROM messages WHERE conversation_id = :conversationId AND sender_key = :senderKey
            AND normalized_text = :normalizedText AND message_timestamp BETWEEN :from AND :to
            AND (:preciseOnly < 0 OR has_precise_timestamp = :preciseOnly)
        ORDER BY ABS(message_timestamp - :timestamp), id LIMIT 1
        """,
    )
    suspend fun findByTextIdentity(
        conversationId: Long,
        senderKey: String,
        normalizedText: String,
        timestamp: Long,
        from: Long,
        to: Long,
        preciseOnly: Int,
    ): MessageEntity?

    /** Same text from the same sender within [from]..[to], closest to [timestamp] first. */
    @Query(
        """
        SELECT * FROM messages WHERE conversation_id = :conversationId AND sender_key = :senderKey
            AND normalized_text = :normalizedText AND message_timestamp BETWEEN :from AND :to
        ORDER BY ABS(message_timestamp - :timestamp), id LIMIT 16
        """,
    )
    suspend fun findSameTextNear(
        conversationId: Long,
        senderKey: String,
        normalizedText: String,
        timestamp: Long,
        from: Long,
        to: Long,
    ): List<MessageEntity>

    @Query(
        "SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId AND sender_key = :senderKey " +
            "AND message_timestamp = :timestamp",
    )
    suspend fun countAtPreciseIdentity(conversationId: Long, senderKey: String, timestamp: Long): Int

    @Insert
    suspend fun insertMessage(entity: MessageEntity): Long

    @Update
    suspend fun updateMessage(entity: MessageEntity)

    @Query("UPDATE messages SET last_notification_key = :key WHERE id = :id")
    suspend fun updateNotificationKey(id: Long, key: String)

    /** Up to [limit] ids of ACTIVE messages from one sender in a time window (deletion-marker candidate search). */
    @Query(
        """
        SELECT * FROM messages WHERE conversation_id = :conversationId AND sender_key = :senderKey
            AND status = 'ACTIVE' AND message_timestamp BETWEEN :from AND :to
        ORDER BY message_timestamp DESC LIMIT :limit
        """,
    )
    suspend fun activeFromSenderBetween(conversationId: Long, senderKey: String, from: Long, to: Long, limit: Int): List<MessageEntity>

    @Query(
        """
        UPDATE messages SET status = 'POSSIBLY_DELETED', deletion_adapter_id = :adapterId,
            deletion_marker_text = :markerText, deletion_observed_at = :observedAt
        WHERE id = :id AND status = 'ACTIVE'
        """,
    )
    suspend fun markPossiblyDeleted(id: Long, adapterId: String, markerText: String, observedAt: Long): Int

    @Query(
        "UPDATE messages SET last_removal_reason = :reason, last_removed_at = :removedAt " +
            "WHERE last_notification_key = :notificationKey",
    )
    suspend fun recordRemoval(notificationKey: String, reason: String, removedAt: Long): Int

    // ---- Message reads ---------------------------------------------------------------------------------------------

    @RawQuery(observedEntities = [MessageEntity::class, ConversationEntity::class])
    fun observeMessageRows(query: RoomRawQuery): Flow<List<MessageRow>>

    @Query("$MESSAGE_ROW_SELECT WHERE m.id = :id")
    fun observeMessageRow(id: Long): Flow<MessageRow?>

    @Query("SELECT * FROM message_revisions WHERE message_id = :messageId ORDER BY captured_at, id")
    fun observeRevisions(messageId: Long): Flow<List<MessageRevisionEntity>>

    @Query("SELECT * FROM message_revisions WHERE message_id = :messageId ORDER BY captured_at, id")
    suspend fun revisionsFor(messageId: Long): List<MessageRevisionEntity>

    @Query("$MESSAGE_ROW_SELECT ORDER BY m.message_timestamp DESC, m.id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MessageRow>>

    @Query("$MESSAGE_ROW_SELECT WHERE m.is_favorite = 1 ORDER BY m.message_timestamp DESC, m.id DESC LIMIT :limit")
    fun observeFavorites(limit: Int): Flow<List<MessageRow>>

    @Query(
        """
        SELECT m.package_name AS package_name,
            (SELECT c.app_label FROM conversations c WHERE c.package_name = m.package_name
                ORDER BY c.last_message_at DESC LIMIT 1) AS app_label,
            COUNT(*) AS message_count,
            COUNT(DISTINCT m.conversation_id) AS conversation_count,
            MAX(m.message_timestamp) AS last_message_at
        FROM messages m GROUP BY m.package_name ORDER BY last_message_at DESC
        """,
    )
    fun observeAppSummaries(): Flow<List<AppSummaryRow>>

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM messages) AS total_messages,
            (SELECT COUNT(*) FROM conversations) AS total_conversations,
            (SELECT COUNT(*) FROM messages WHERE is_favorite = 1) AS favorites,
            (SELECT COUNT(*) FROM messages WHERE status = 'POSSIBLY_DELETED') AS possibly_deleted,
            (SELECT COUNT(DISTINCT package_name) FROM messages) AS apps,
            (SELECT MIN(first_captured_at) FROM messages) AS oldest_captured_at,
            (SELECT MAX(first_captured_at) FROM messages) AS newest_captured_at
        """,
    )
    fun observeStats(): Flow<StatsRow>

    @Query("SELECT * FROM messages ORDER BY id")
    suspend fun allMessages(): List<MessageEntity>

    @Query("SELECT * FROM message_revisions ORDER BY message_id, captured_at, id")
    suspend fun allRevisions(): List<MessageRevisionEntity>

    // ---- Writes ----------------------------------------------------------------------------------------------------

    @Insert
    suspend fun insertRevision(entity: MessageRevisionEntity): Long

    @Query("UPDATE messages SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean): Int

    @Query("SELECT DISTINCT conversation_id FROM messages WHERE id IN (:ids)")
    suspend fun conversationIdsOf(ids: List<Long>): List<Long>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<Long>): Int

    @Query("DELETE FROM messages WHERE message_timestamp < :cutoff AND (:keepFavorites = 0 OR is_favorite = 0)")
    suspend fun deleteOlderThan(cutoff: Long, keepFavorites: Boolean): Int
}
