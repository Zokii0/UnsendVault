package com.riyadm.unsendvault.data.backup

import com.riyadm.unsendvault.data.ImportMode
import kotlinx.serialization.Serializable

/**
 * Plaintext content of a backup (JSON, gzipped, then encrypted by [BackupCrypto]).
 *
 * Versioning: [schemaVersion] is the first property so it can be peeked even when the rest doesn't parse. Additive
 * changes (new optional fields) keep the version; incompatible changes bump it, and older apps answer
 * UNSUPPORTED_VERSION. Device-specific data (notification keys, row ids) is never exported.
 */
@Serializable
data class BackupPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val conversations: List<BackupConversation>,
    val messages: List<BackupMessage>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class BackupConversation(
    /** File-local reference used by [BackupMessage.conversationRef]; not a database id on import. */
    val ref: Long,
    val packageName: String,
    val appLabel: String,
    val conversationKey: String,
    val title: String,
    val isGroup: Boolean = false,
    val createdAt: Long = 0,
)

@Serializable
data class BackupMessage(
    val conversationRef: Long,
    val sender: String? = null,
    val text: String,
    val contentKind: String = "TEXT",
    val messageTimestamp: Long,
    val hasPreciseTimestamp: Boolean = false,
    val firstCapturedAt: Long,
    val lastUpdatedAt: Long,
    val isFavorite: Boolean = false,
    val status: String = "ACTIVE",
    val deletionEvidence: BackupEvidence? = null,
    val revisionCount: Int = 1,
    val lastRemovalReason: String? = null,
    val lastRemovedAt: Long? = null,
    val revisions: List<BackupRevision> = emptyList(),
)

@Serializable
data class BackupEvidence(
    val adapterId: String,
    val markerText: String,
    val observedAt: Long,
    val confidence: String = "HIGH",
)

@Serializable
data class BackupRevision(
    val text: String,
    val contentKind: String = "TEXT",
    val capturedAt: Long,
)

/** Data access the backup manager needs; implemented by the Room repository. */
interface BackupStore {
    /** Snapshot of the whole archive, or null when the vault is unavailable. */
    suspend fun exportSnapshot(exportedAt: Long): BackupPayload?

    /**
     * Imports [payload] in one transaction. MERGE dedupes with the same identity rules as ingest (idempotent);
     * REPLACE clears the archive first. Returns null when the vault is unavailable.
     * @throws BackupFormatException when the payload is internally inconsistent.
     */
    suspend fun importPayload(payload: BackupPayload, mode: ImportMode): ImportCounts?
}

data class ImportCounts(val messages: Int, val conversations: Int)
