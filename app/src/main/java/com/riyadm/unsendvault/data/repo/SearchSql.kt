package com.riyadm.unsendvault.data.repo

import androidx.room.RoomRawQuery
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.db.MESSAGE_ROW_SELECT

/**
 * Builds the SQL prefilter for a [MessageQuery]: every non-text filter (packages, conversation, inclusive date range,
 * favorites, possibly-deleted) runs in SQLite, newest first. Message text, sender and titles are encrypted, so the text
 * part of a query is never part of the SQL; [MessageSearch] decrypts and matches the prefiltered rows page by page.
 * All values are bound as arguments.
 */
object SearchSql {
    data class Built(val sql: String, val args: List<Any?>) {
        fun toRoomRawQuery(): RoomRawQuery = RoomRawQuery(sql) { stmt ->
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> stmt.bindNull(i + 1)
                    is Long -> stmt.bindLong(i + 1, a)
                    is Int -> stmt.bindLong(i + 1, a.toLong())
                    is String -> stmt.bindText(i + 1, a)
                    else -> error("Unsupported arg ${a::class}")
                }
            }
        }
    }

    /** Position after the last row of a page (keyset pagination on the ORDER BY columns). */
    data class Cursor(val messageTimestamp: Long, val id: Long)

    private const val MAX_PACKAGES = 500
    const val MAX_LIMIT = 10_000

    /** Columns of [com.riyadm.unsendvault.data.db.ScanRow]; no join, the scan only needs these. */
    private const val SCAN_SELECT =
        "SELECT m.id, m.conversation_id, m.message_timestamp, m.sender_index, m.text, m.sender FROM messages m"

    /** Full joined rows, filters only, LIMIT = [MessageQuery.limit]: used when the query has no text. */
    fun build(query: MessageQuery): Built = page(MESSAGE_ROW_SELECT, query, after = null, pageSize = query.limit.coerceIn(0, MAX_LIMIT))

    /** One page of the search scan, strictly after [after] in (message_timestamp DESC, id DESC) order. */
    fun scanPage(query: MessageQuery, after: Cursor?, pageSize: Int): Built = page(SCAN_SELECT, query, after, pageSize)

    private fun page(select: String, query: MessageQuery, after: Cursor?, pageSize: Int): Built {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (query.packages.isNotEmpty()) {
            val pkgs = query.packages.take(MAX_PACKAGES)
            where += "m.package_name IN (${pkgs.joinToString(",") { "?" }})"
            args.addAll(pkgs)
        }
        query.conversationId?.let {
            where += "m.conversation_id = ?"
            args += it
        }
        query.fromMillis?.let {
            where += "m.message_timestamp >= ?"
            args += it
        }
        query.toMillis?.let {
            where += "m.message_timestamp <= ?"
            args += it
        }
        if (query.favoritesOnly) where += "m.is_favorite = 1"
        if (query.possiblyDeletedOnly) where += "m.status = 'POSSIBLY_DELETED'"
        if (after != null) {
            where += "(m.message_timestamp < ? OR (m.message_timestamp = ? AND m.id < ?))"
            args += after.messageTimestamp
            args += after.messageTimestamp
            args += after.id
        }
        val sql = buildString {
            append(select)
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY m.message_timestamp DESC, m.id DESC LIMIT ?")
        }
        args += pageSize
        return Built(sql, args)
    }
}
