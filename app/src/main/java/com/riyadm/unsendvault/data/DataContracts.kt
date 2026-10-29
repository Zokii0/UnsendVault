package com.riyadm.unsendvault.data

import android.net.Uri
import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.MessageDetail
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.core.model.VaultStats
import kotlinx.coroutines.flow.Flow

/** Single source of truth for archived messages. Implemented on Room + SQLCipher. */
interface VaultRepository {
    fun observeMessages(query: MessageQuery): Flow<List<ArchivedMessage>>
    fun observeConversations(packages: Set<String> = emptySet()): Flow<List<Conversation>>
    fun observeConversation(id: Long): Flow<Conversation?>
    fun observeMessage(id: Long): Flow<MessageDetail?>
    fun observeAppSummaries(): Flow<List<AppSummary>>
    fun observeStats(): Flow<VaultStats>

    /** Recent messages for widgets (newest first). */
    fun observeRecent(limit: Int): Flow<List<ArchivedMessage>>
    fun observeFavorites(limit: Int): Flow<List<ArchivedMessage>>

    /** Deduplicates, records update history and applies high-confidence deletion markers in one transaction. */
    suspend fun ingest(parsed: ParsedNotification, capturedAt: Long): IngestResult

    /** Records a notification removal for transparency. Never changes [com.riyadm.unsendvault.core.model.MessageStatus]. */
    suspend fun recordRemoval(event: RemovalEvent)

    suspend fun setFavorite(messageId: Long, favorite: Boolean)
    suspend fun deleteMessages(ids: Collection<Long>)
    suspend fun deleteConversation(conversationId: Long)
    suspend fun deleteApp(packageName: String)
    suspend fun deleteAll()

    /** Deletes non-favorite messages whose messageTimestamp is older than [cutoffMillis]. Returns rows deleted. */
    suspend fun deleteOlderThan(cutoffMillis: Long, keepFavorites: Boolean = true): Int
}

interface SettingsRepository {
    val settings: Flow<VaultSettings>

    /** Synchronous snapshot for the listener hot path; kept up to date from [settings]. */
    val current: VaultSettings
    suspend fun update(transform: (VaultSettings) -> VaultSettings)
}

/** Password-encrypted export/import through the Storage Access Framework. */
interface BackupManager {
    suspend fun export(destination: Uri, password: CharArray): BackupResult
    suspend fun import(source: Uri, password: CharArray, mode: ImportMode = ImportMode.MERGE): BackupResult
}

enum class ImportMode { MERGE, REPLACE }

sealed interface BackupResult {
    data class Success(val messageCount: Int, val conversationCount: Int) : BackupResult
    data class Failure(val reason: Reason, val detail: String? = null) : BackupResult

    enum class Reason { WRONG_PASSWORD, CORRUPT_FILE, UNSUPPORTED_VERSION, IO_ERROR, EMPTY_PASSWORD }
}

/** Schedules/executes retention (auto-delete). Implemented with a daily, constraint-bound WorkManager job. */
interface RetentionScheduler {
    /** (Re)schedules or cancels the periodic job to match [retentionDays] (0 = keep forever = cancel). */
    fun schedule(retentionDays: Int)

    /** Runs retention now; returns rows deleted. */
    suspend fun runNow(): Int
}

/** Asks Glance widgets to refresh. Implementations must debounce and be cheap to call from the capture path. */
fun interface WidgetRefresher {
    fun requestRefresh()
}
