package com.riyadm.unsendvault.data.repo

import android.database.SQLException
import android.util.Log
import androidx.room.withTransaction
import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.DeletionMarker
import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.MessageDetail
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.core.model.VaultStats
import com.riyadm.unsendvault.data.ImportMode
import com.riyadm.unsendvault.data.VaultRepository
import com.riyadm.unsendvault.data.backup.BackupConversation
import com.riyadm.unsendvault.data.backup.BackupEvidence
import com.riyadm.unsendvault.data.backup.BackupFormatException
import com.riyadm.unsendvault.data.backup.BackupMessage
import com.riyadm.unsendvault.data.backup.BackupPayload
import com.riyadm.unsendvault.data.backup.BackupRevision
import com.riyadm.unsendvault.data.backup.BackupStore
import com.riyadm.unsendvault.data.backup.ImportCounts
import com.riyadm.unsendvault.data.db.ConversationEntity
import com.riyadm.unsendvault.data.db.MessageEntity
import com.riyadm.unsendvault.data.db.MessageRevisionEntity
import com.riyadm.unsendvault.data.db.VaultDao
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.security.VaultDatabaseHolder
import com.riyadm.unsendvault.data.security.VaultHealth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Room (+ SQLCipher in production) implementation of [VaultRepository].
 *
 * Reads are Flows that re-query on table invalidation. While the vault cannot be opened (see [VaultHealth.keyState])
 * reads emit empty values and writes are no-ops, so a Keystore problem never crashes the listener or the UI.
 *
 * Message identity (shared by [ingest] and backup MERGE):
 *  - precise timestamp (MessagingStyle): (conversation, sender, message_timestamp).
 *  - otherwise: (conversation, sender, normalized text) with message_timestamp within ±[IMPRECISE_WINDOW_MS].
 */
class RoomVaultRepository(
    private val holder: VaultDatabaseHolder,
) : VaultRepository, VaultHealth by holder, BackupStore {

    constructor(db: VaultDatabase) : this(VaultDatabaseHolder.of(db))

    // ---- Reads -----------------------------------------------------------------------------------------------------

    private fun <T> observe(empty: T, query: (VaultDao) -> Flow<T>): Flow<T> =
        holder.databases.flatMapLatest { db ->
            if (db == null) {
                flowOf(empty)
            } else {
                query(db.dao()).catch { e ->
                    // A reset closes the old database while a query may still be in flight.
                    if (e is IllegalStateException || e is SQLException) {
                        Log.w(TAG, "Query failed", e)
                        emit(empty)
                    } else {
                        throw e
                    }
                }
            }
        }

    override fun observeMessages(query: MessageQuery): Flow<List<ArchivedMessage>> =
        observe(emptyList()) { dao ->
            dao.observeMessageRows(SearchSql.build(query).toRoomRawQuery()).map { rows -> rows.map { it.toDomain() } }
        }

    override fun observeConversations(packages: Set<String>): Flow<List<Conversation>> =
        observe(emptyList()) { dao ->
            val source = if (packages.isEmpty()) dao.observeConversations() else dao.observeConversationsIn(packages)
            source.map { list -> list.map { it.toDomain() } }
        }

    override fun observeConversation(id: Long): Flow<Conversation?> =
        observe(null) { dao -> dao.observeConversation(id).map { it?.toDomain() } }

    override fun observeMessage(id: Long): Flow<MessageDetail?> =
        observe(null) { dao ->
            combine(dao.observeMessageRow(id), dao.observeRevisions(id)) { row, revisions ->
                row?.let { MessageDetail(it.toDomain(), revisions.map { r -> r.toDomain() }) }
            }
        }

    override fun observeAppSummaries(): Flow<List<AppSummary>> =
        observe(emptyList()) { dao -> dao.observeAppSummaries().map { list -> list.map { it.toDomain() } } }

    override fun observeStats(): Flow<VaultStats> =
        observe(EMPTY_STATS) { dao -> dao.observeStats().map { it.toDomain() } }

    override fun observeRecent(limit: Int): Flow<List<ArchivedMessage>> =
        observe(emptyList()) { dao -> dao.observeRecent(limit.coerceAtLeast(0)).map { rows -> rows.map { it.toDomain() } } }

    override fun observeFavorites(limit: Int): Flow<List<ArchivedMessage>> =
        observe(emptyList()) { dao -> dao.observeFavorites(limit.coerceAtLeast(0)).map { rows -> rows.map { it.toDomain() } } }

    // ---- Ingest ----------------------------------------------------------------------------------------------------

    override suspend fun ingest(parsed: ParsedNotification, capturedAt: Long): IngestResult {
        if (parsed.messages.isEmpty() && parsed.deletionMarkers.isEmpty()) return IngestResult.EMPTY
        val db = holder.database() ?: return IngestResult.EMPTY
        return db.withTransaction {
            val dao = db.dao()
            val counter = Counter()
            val touched = mutableSetOf<Long>()

            // Text of every marker in this notification, per conversation: a captured "message" carrying exactly the
            // marker text is the marker itself and must never be archived as / written over a message.
            val markerTexts = parsed.deletionMarkers
                .groupBy({ it.packageName to it.conversationKey }, { TextNormalizer.normalize(it.markerText) })

            val batch = BatchContext(parsed.messages)
            for (c in parsed.messages) {
                val normalized = TextNormalizer.normalize(c.text)
                if (normalized.isEmpty()) continue
                if (markerTexts[c.packageName to c.conversationKey]?.contains(normalized) == true) continue
                val conversation = upsertConversation(dao, c, parsed.appLabel, capturedAt)
                when (ingestMessage(dao, conversation.id, c, normalized, parsed.notificationKey, capturedAt, batch)) {
                    Outcome.INSERTED -> { counter.inserted++; touched += conversation.id }
                    Outcome.UPDATED -> { counter.updated++; touched += conversation.id }
                    Outcome.UNCHANGED -> counter.unchanged++
                }
            }
            for (marker in parsed.deletionMarkers) {
                val conversation = dao.findConversation(marker.packageName, marker.conversationKey) ?: continue
                if (applyMarker(dao, conversation.id, marker, capturedAt)) counter.marked++
            }
            if (touched.isNotEmpty()) dao.recomputeAggregates(touched.toList())
            IngestResult(counter.inserted, counter.updated, counter.unchanged, counter.marked)
        }
    }

    private class Counter {
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var marked = 0
    }

    private enum class Outcome { INSERTED, UPDATED, UNCHANGED }

    /**
     * What the rest of the notification says, used to keep identity matching honest:
     * - rows already matched by an earlier message of this notification are never matched twice, so a sender who
     *   really sent "ok" twice keeps two rows;
     * - several messages sharing one (sender, timestamp) slot are distinct messages, never an edit of each other.
     */
    private class BatchContext(messages: List<CapturedMessage>) {
        val matchedIds = mutableSetOf<Long>()
        private val slotCounts = messages.filter { it.hasPreciseTimestamp }
            .groupingBy { Triple(it.conversationKey, TextNormalizer.senderKey(it.sender), it.messageTimestamp) }.eachCount()
        private val textsBySender = messages
            .groupBy({ it.conversationKey to TextNormalizer.senderKey(it.sender) }, { TextNormalizer.normalize(it.text) })
            .mapValues { it.value.toSet() }

        fun slotCount(c: CapturedMessage, senderKey: String) = slotCounts[Triple(c.conversationKey, senderKey, c.messageTimestamp)] ?: 0

        fun senderStillShows(c: CapturedMessage, senderKey: String, normalized: String) =
            textsBySender[c.conversationKey to senderKey]?.contains(normalized) == true
    }

    private suspend fun upsertConversation(
        dao: VaultDao,
        c: CapturedMessage,
        appLabel: String,
        now: Long,
    ): ConversationEntity {
        val existing = dao.findConversation(c.packageName, c.conversationKey)
        if (existing == null) {
            val entity = ConversationEntity(
                packageName = c.packageName,
                appLabel = appLabel.ifBlank { c.packageName },
                conversationKey = c.conversationKey,
                title = c.conversationTitle.ifBlank { c.sender ?: c.conversationKey },
                isGroup = c.isGroup,
                createdAt = now,
                lastMessageAt = c.messageTimestamp,
                lastMessagePreview = null,
                messageCount = 0,
            )
            return entity.copy(id = dao.insertConversation(entity))
        }
        val title = c.conversationTitle.ifBlank { existing.title }
        val label = appLabel.ifBlank { existing.appLabel }
        if (title != existing.title || c.isGroup != existing.isGroup || label != existing.appLabel) {
            dao.updateConversationMeta(existing.id, title, c.isGroup, label)
            return existing.copy(title = title, isGroup = c.isGroup, appLabel = label)
        }
        return existing
    }

    private suspend fun ingestMessage(
        dao: VaultDao,
        conversationId: Long,
        c: CapturedMessage,
        normalized: String,
        notificationKey: String,
        capturedAt: Long,
        batch: BatchContext,
    ): Outcome {
        val senderKey = TextNormalizer.senderKey(c.sender)
        val existing = findIdentity(dao, conversationId, senderKey, normalized, c, batch)
        existing?.let { batch.matchedIds += it.id }
        if (existing == null) {
            batch.matchedIds += dao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    packageName = c.packageName,
                    sender = c.sender,
                    senderKey = senderKey,
                    text = c.text,
                    normalizedText = normalized,
                    contentKind = c.contentKind.name,
                    messageTimestamp = c.messageTimestamp,
                    hasPreciseTimestamp = c.hasPreciseTimestamp,
                    firstCapturedAt = capturedAt,
                    lastUpdatedAt = capturedAt,
                    isFavorite = false,
                    status = MessageStatus.ACTIVE.name,
                    deletionAdapterId = null,
                    deletionMarkerText = null,
                    deletionObservedAt = null,
                    revisionCount = 1,
                    lastNotificationKey = notificationKey,
                ),
            )
            return Outcome.INSERTED
        }

        val existingKind = contentKindOf(existing.contentKind)
        val upgradeToPrecise = c.hasPreciseTimestamp && !existing.hasPreciseTimestamp
        val sameText = existing.normalizedText == normalized
        val updated: MessageEntity? = when {
            // Placeholders never overwrite anything (real text or another placeholder).
            c.contentKind.isWithheld() -> null
            sameText -> null
            // After a deletion marker the app may re-post the slot with its "deleted" text; keep the original.
            existing.status == MessageStatus.POSSIBLY_DELETED.name -> null
            // A placeholder is replaced by the real text; the placeholder is not a "version", so no revision.
            existingKind.isWithheld() -> existing.copy(
                text = c.text, normalizedText = normalized, contentKind = c.contentKind.name, lastUpdatedAt = capturedAt,
            )
            // Same identity, different text: the app updated/edited the message. Keep history.
            else -> {
                dao.insertRevision(
                    MessageRevisionEntity(
                        messageId = existing.id,
                        text = existing.text,
                        contentKind = existing.contentKind,
                        capturedAt = existing.lastUpdatedAt,
                    ),
                )
                existing.copy(
                    text = c.text,
                    normalizedText = normalized,
                    contentKind = c.contentKind.name,
                    lastUpdatedAt = capturedAt,
                    revisionCount = existing.revisionCount + 1,
                )
            }
        }
        if (updated != null) {
            dao.updateMessage(
                updated.copy(
                    lastNotificationKey = notificationKey,
                    messageTimestamp = if (upgradeToPrecise) c.messageTimestamp else updated.messageTimestamp,
                    hasPreciseTimestamp = updated.hasPreciseTimestamp || upgradeToPrecise,
                ),
            )
            return Outcome.UPDATED
        }
        if (upgradeToPrecise) {
            dao.updateMessage(
                existing.copy(
                    messageTimestamp = c.messageTimestamp,
                    hasPreciseTimestamp = true,
                    lastNotificationKey = notificationKey,
                ),
            )
        } else if (existing.lastNotificationKey != notificationKey) {
            dao.updateNotificationKey(existing.id, notificationKey)
        }
        return Outcome.UNCHANGED
    }

    /**
     * Finds the stored row this captured message is a copy of, or null for a new message.
     *
     * 1. Same sender and normalized text within ±[IMPRECISE_WINDOW_MS], closest timestamp first, skipping rows already
     *    claimed by this notification. This covers re-posts of the same history (exact timestamp), apps that re-stamp
     *    old messages on every update, and upgrading a record first seen without a MessagingStyle timestamp.
     * 2. Precise only: the same (sender, timestamp) slot with different text is an edit, but only when that slot holds
     *    exactly one message both in this notification and in the archive, and the stored text is no longer shown for
     *    that sender. Otherwise two different messages that share a timestamp would be merged into a false "edit".
     */
    private suspend fun findIdentity(
        dao: VaultDao,
        conversationId: Long,
        senderKey: String,
        normalized: String,
        c: CapturedMessage,
        batch: BatchContext,
    ): MessageEntity? {
        val timestamp = c.messageTimestamp
        dao.findSameTextNear(
            conversationId, senderKey, normalized, timestamp, timestamp - IMPRECISE_WINDOW_MS, timestamp + IMPRECISE_WINDOW_MS,
        ).firstOrNull { it.id !in batch.matchedIds }?.let { return it }

        if (!c.hasPreciseTimestamp || batch.slotCount(c, senderKey) != 1) return null
        if (dao.countAtPreciseIdentity(conversationId, senderKey, timestamp) != 1) return null
        return dao.findByPreciseIdentity(conversationId, senderKey, timestamp)
            ?.takeIf { it.id !in batch.matchedIds && !batch.senderStillShows(c, senderKey, it.normalizedText) }
    }

    /**
     * High-confidence deletion marker handling. Marks at most one message and only when:
     *  (a) a message has the exact identity (conversation, marker sender, marker timestamp), or
     *  (b) the marker names a sender and exactly one ACTIVE message from that sender exists in the 15 minutes up to
     *      the marker.
     * Anything ambiguous is ignored. The original text is kept; the marker is stored only as evidence.
     */
    private suspend fun applyMarker(dao: VaultDao, conversationId: Long, marker: DeletionMarker, observedAt: Long): Boolean {
        val senderKey = TextNormalizer.senderKey(marker.sender)
        val markerNormalized = TextNormalizer.normalize(marker.markerText)
        val evidenceText = TextNormalizer.truncate(marker.markerText.trim(), MARKER_TEXT_MAX)

        val exact = dao.findByPreciseIdentity(conversationId, senderKey, marker.markerTimestamp)
            ?.takeIf { it.normalizedText != markerNormalized }
        val target = exact ?: if (senderKey.isNotEmpty()) {
            dao.activeFromSenderBetween(
                conversationId, senderKey, marker.markerTimestamp - MARKER_WINDOW_MS, marker.markerTimestamp, limit = 3,
            ).filter { it.normalizedText != markerNormalized }.singleOrNull()
        } else {
            null
        }
        target ?: return false
        return dao.markPossiblyDeleted(target.id, marker.adapterId, evidenceText, observedAt) > 0
    }

    override suspend fun recordRemoval(event: RemovalEvent) {
        val db = holder.database() ?: return
        db.dao().recordRemoval(event.notificationKey, event.reason.name, event.removedAt)
    }

    // ---- User actions ----------------------------------------------------------------------------------------------

    override suspend fun setFavorite(messageId: Long, favorite: Boolean) {
        holder.database()?.dao()?.setFavorite(messageId, favorite)
    }

    override suspend fun deleteMessages(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = holder.database() ?: return
        db.withTransaction {
            val dao = db.dao()
            val conversations = mutableSetOf<Long>()
            ids.distinct().chunked(SQL_CHUNK).forEach { chunk ->
                conversations += dao.conversationIdsOf(chunk)
                dao.deleteMessages(chunk)
            }
            conversations.toList().chunked(SQL_CHUNK).forEach { dao.recomputeAggregates(it) }
            dao.deleteEmptyConversations()
        }
    }

    override suspend fun deleteConversation(conversationId: Long) {
        holder.database()?.dao()?.deleteConversation(conversationId)
    }

    override suspend fun deleteApp(packageName: String) {
        holder.database()?.dao()?.deleteConversationsForPackage(packageName)
    }

    override suspend fun deleteAll() {
        holder.database()?.dao()?.deleteAllConversations()
    }

    override suspend fun deleteOlderThan(cutoffMillis: Long, keepFavorites: Boolean): Int {
        val db = holder.database() ?: return 0
        return db.withTransaction {
            val dao = db.dao()
            val deleted = dao.deleteOlderThan(cutoffMillis, keepFavorites)
            if (deleted > 0) {
                dao.deleteEmptyConversations()
                dao.recomputeAllAggregates()
            }
            deleted
        }
    }

    // ---- Backup ----------------------------------------------------------------------------------------------------

    override suspend fun exportSnapshot(exportedAt: Long): BackupPayload? {
        val db = holder.database() ?: return null
        return db.withTransaction {
            val dao = db.dao()
            val conversations = dao.allConversations()
            val revisions = dao.allRevisions().groupBy { it.messageId }
            val messages = dao.allMessages().map { m ->
                BackupMessage(
                    conversationRef = m.conversationId,
                    sender = m.sender,
                    text = m.text,
                    contentKind = m.contentKind,
                    messageTimestamp = m.messageTimestamp,
                    hasPreciseTimestamp = m.hasPreciseTimestamp,
                    firstCapturedAt = m.firstCapturedAt,
                    lastUpdatedAt = m.lastUpdatedAt,
                    isFavorite = m.isFavorite,
                    status = m.status,
                    deletionEvidence = if (m.deletionAdapterId != null && m.deletionMarkerText != null && m.deletionObservedAt != null) {
                        BackupEvidence(m.deletionAdapterId, m.deletionMarkerText, m.deletionObservedAt)
                    } else {
                        null
                    },
                    revisionCount = m.revisionCount,
                    lastRemovalReason = m.lastRemovalReason,
                    lastRemovedAt = m.lastRemovedAt,
                    revisions = revisions[m.id].orEmpty().map { BackupRevision(it.text, it.contentKind, it.capturedAt) },
                )
            }
            BackupPayload(
                exportedAt = exportedAt,
                conversations = conversations.map {
                    BackupConversation(it.id, it.packageName, it.appLabel, it.conversationKey, it.title, it.isGroup, it.createdAt)
                },
                messages = messages,
            )
        }
    }

    override suspend fun importPayload(payload: BackupPayload, mode: ImportMode): ImportCounts? {
        val refs = payload.conversations.map { it.ref }
        if (refs.toSet().size != refs.size) throw BackupFormatException("Duplicate conversation reference")
        val known = refs.toSet()
        payload.messages.firstOrNull { it.conversationRef !in known }?.let {
            throw BackupFormatException("Message references unknown conversation ${it.conversationRef}")
        }
        val db = holder.database() ?: return null
        return db.withTransaction {
            val dao = db.dao()
            if (mode == ImportMode.REPLACE) dao.deleteAllConversations()
            val idByRef = HashMap<Long, Long>(payload.conversations.size * 2)
            for (c in payload.conversations) {
                val existing = dao.findConversation(c.packageName, c.conversationKey)
                idByRef[c.ref] = existing?.id ?: dao.insertConversation(
                    ConversationEntity(
                        packageName = c.packageName,
                        appLabel = c.appLabel.ifBlank { c.packageName },
                        conversationKey = c.conversationKey,
                        title = c.title,
                        isGroup = c.isGroup,
                        createdAt = c.createdAt,
                        lastMessageAt = 0,
                        lastMessagePreview = null,
                        messageCount = 0,
                    ),
                )
            }
            val packageByRef = payload.conversations.associate { it.ref to it.packageName }
            val claimed = mutableSetOf<Long>()
            for (m in payload.messages) {
                mergeMessage(dao, idByRef.getValue(m.conversationRef), packageByRef.getValue(m.conversationRef), m, claimed)
            }
            dao.recomputeAllAggregates()
            dao.deleteEmptyConversations()
            ImportCounts(messages = payload.messages.size, conversations = payload.conversations.size)
        }
    }

    /**
     * Backup rows are finished records: an imported row matches an exact twin (same sender, text and timestamp) not
     * already claimed by this import, else the single archived row in the same precise (sender, timestamp) slot, whose
     * versions are then merged. Re-importing a file is idempotent and distinct rows always stay distinct.
     */
    private suspend fun mergeMessage(
        dao: VaultDao,
        conversationId: Long,
        packageName: String,
        m: BackupMessage,
        claimed: MutableSet<Long>,
    ) {
        val normalized = TextNormalizer.normalize(m.text)
        val senderKey = TextNormalizer.senderKey(m.sender)
        val ts = m.messageTimestamp
        val existing = dao.findSameTextNear(conversationId, senderKey, normalized, ts, ts, ts).firstOrNull { it.id !in claimed }
            ?: if (m.hasPreciseTimestamp && dao.countAtPreciseIdentity(conversationId, senderKey, ts) == 1) {
                // The archive holds one version of this exact message slot (e.g. edited later): merge versions.
                dao.findByPreciseIdentity(conversationId, senderKey, ts)?.takeIf { it.id !in claimed }
            } else {
                null
            }
        existing?.let { claimed += it.id }
        val evidence = m.deletionEvidence?.takeIf { m.status == MessageStatus.POSSIBLY_DELETED.name }
        val kind = contentKindOf(m.contentKind)
        if (existing == null) {
            val id = dao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    packageName = packageName,
                    sender = m.sender,
                    senderKey = senderKey,
                    text = m.text,
                    normalizedText = normalized,
                    contentKind = kind.name,
                    messageTimestamp = m.messageTimestamp,
                    hasPreciseTimestamp = m.hasPreciseTimestamp,
                    firstCapturedAt = m.firstCapturedAt,
                    lastUpdatedAt = m.lastUpdatedAt,
                    isFavorite = m.isFavorite,
                    status = if (evidence != null) MessageStatus.POSSIBLY_DELETED.name else MessageStatus.ACTIVE.name,
                    deletionAdapterId = evidence?.adapterId,
                    deletionMarkerText = evidence?.let { TextNormalizer.truncate(it.markerText, MARKER_TEXT_MAX) },
                    deletionObservedAt = evidence?.observedAt,
                    revisionCount = 1 + m.revisions.size,
                    lastRemovalReason = m.lastRemovalReason,
                    lastRemovedAt = m.lastRemovedAt,
                ),
            )
            claimed += id
            val seen = HashSet<Pair<String, Long>>()
            m.revisions.forEach { r ->
                if (seen.add(r.text to r.capturedAt)) {
                    dao.insertRevision(MessageRevisionEntity(messageId = id, text = r.text, contentKind = contentKindOf(r.contentKind).name, capturedAt = r.capturedAt))
                }
            }
            return
        }

        // Merge into the existing message. Revisions are keyed by (text, capturedAt), which makes re-imports no-ops.
        var merged = existing
        val existingRevisions = dao.revisionsFor(existing.id)
        val knownVersions = existingRevisions.mapTo(HashSet()) { it.text to it.capturedAt }
        val candidates = mutableListOf<BackupRevision>()
        if (normalized != existing.normalizedText) {
            if (m.lastUpdatedAt > existing.lastUpdatedAt && !kind.isWithheld()) {
                candidates += BackupRevision(existing.text, existing.contentKind, existing.lastUpdatedAt)
                merged = merged.copy(text = m.text, normalizedText = normalized, contentKind = kind.name, lastUpdatedAt = m.lastUpdatedAt)
            } else if (!kind.isWithheld()) {
                candidates += BackupRevision(m.text, kind.name, m.lastUpdatedAt)
            }
        }
        candidates += m.revisions
        var added = 0
        for (r in candidates) {
            val key = r.text to r.capturedAt
            if (r.text == merged.text || !knownVersions.add(key)) continue
            dao.insertRevision(MessageRevisionEntity(messageId = existing.id, text = r.text, contentKind = contentKindOf(r.contentKind).name, capturedAt = r.capturedAt))
            added++
        }
        merged = merged.copy(
            isFavorite = existing.isFavorite || m.isFavorite,
            firstCapturedAt = minOf(existing.firstCapturedAt, m.firstCapturedAt),
            revisionCount = if (added > 0) 1 + existingRevisions.size + added else existing.revisionCount,
        )
        if (evidence != null && existing.status == MessageStatus.ACTIVE.name) {
            merged = merged.copy(
                status = MessageStatus.POSSIBLY_DELETED.name,
                deletionAdapterId = evidence.adapterId,
                deletionMarkerText = TextNormalizer.truncate(evidence.markerText, MARKER_TEXT_MAX),
                deletionObservedAt = evidence.observedAt,
            )
        }
        if (existing.lastRemovedAt == null && m.lastRemovedAt != null) {
            merged = merged.copy(lastRemovalReason = m.lastRemovalReason, lastRemovedAt = m.lastRemovedAt)
        }
        if (merged != existing) dao.updateMessage(merged)
    }

    companion object {
        private const val TAG = "UvVaultRepo"
        const val IMPRECISE_WINDOW_MS = 10 * 60 * 1000L
        const val MARKER_WINDOW_MS = 15 * 60 * 1000L
        const val MARKER_TEXT_MAX = 120
        private const val SQL_CHUNK = 500
        private val EMPTY_STATS = VaultStats(0, 0, 0, 0, 0, null, null)
    }
}

/** The vault's health/recovery API, or null for implementations without encryption (fakes). */
val VaultRepository.vaultHealth: VaultHealth?
    get() = this as? VaultHealth
