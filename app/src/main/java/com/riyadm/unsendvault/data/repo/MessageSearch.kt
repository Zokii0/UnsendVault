package com.riyadm.unsendvault.data.repo

import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.db.MessageRow
import com.riyadm.unsendvault.data.db.VaultDao
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.FieldCipher.Field
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Text search over encrypted rows (there is no plaintext index to query).
 *
 * 1. SQL prefilter with every non-text filter, newest first ([SearchSql.scanPage]), fetched in pages of [pageSize]
 *    rows with keyset pagination on (message_timestamp, id) — each page is an index range scan, never an OFFSET — and
 *    a lean projection (id, conversation, timestamp, sender index, encrypted text and sender; no join).
 * 2. For each row: decrypt and fold the text; only if some term is still missing, the sender (folded value cached per
 *    sender_index) and the conversation title (cached per conversation). Every term must occur in one of the three.
 * 3. Stops at the query's limit, at the end of the prefilter, or after [maxScannedRows] rows. Only the matches are
 *    then loaded as full rows, and mapped with the values already decrypted (no field is decrypted twice).
 *
 * Cost is linear in the rows scanned: roughly one AES-GCM decrypt plus one fold per row. Measured numbers are in
 * docs/IMPLEMENTATION_PLAN.md ("Search"). The scan cap bounds the worst case on huge archives: a query whose matches
 * are all older than the newest [maxScannedRows] prefiltered rows returns what was found so far; narrowing by app,
 * conversation or date moves the window.
 */
internal class MessageSearch(
    private val cipher: FieldCipher,
    private val pageSize: Int = PAGE_SIZE,
    private val maxScannedRows: Int = MAX_SCANNED_ROWS,
) {
    /** Rows decrypted by the last [run] (for tests and measurements). */
    var lastScanned = 0
        private set

    suspend fun run(dao: VaultDao, query: MessageQuery): List<ArchivedMessage> {
        val terms = SearchText.terms(query.text)
        val limit = query.limit.coerceIn(0, SearchSql.MAX_LIMIT)
        lastScanned = 0
        if (limit == 0) return emptyList()
        if (terms.isEmpty()) {
            return dao.messageRows(SearchSql.build(query).toRoomRawQuery()).map { it.toDomain(cipher) }
        }

        val matches = ArrayList<Match>(minOf(limit, 64))
        val titles = HashMap<Long, Pair<String, String>>() // conversation id -> (title, folded)
        val senders = HashMap<String, String>() // sender_index -> folded sender
        var cursor: SearchSql.Cursor? = null
        var scanned = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.scanRows(SearchSql.scanPage(query, cursor, pageSize).toRoomRawQuery())
            for (row in page) {
                scanned++
                val owner = FieldCipher.forMessage(row.conversationId)
                val text = cipher.decrypt(row.text, Field.MESSAGE_TEXT, owner)
                val foldedText = SearchText.fold(text)
                var missing = terms.filter { !foldedText.contains(it) }
                var sender: String? = null
                var senderKnown = false
                val senderBlob = row.sender
                if (missing.isNotEmpty() && senderBlob != null) {
                    var folded = senders[row.senderIndex]
                    if (folded == null) {
                        val decrypted = cipher.decrypt(senderBlob, Field.MESSAGE_SENDER, owner)
                        sender = decrypted
                        senderKnown = true
                        folded = SearchText.fold(decrypted)
                        senders[row.senderIndex] = folded
                    }
                    val f = folded
                    missing = missing.filter { !f.contains(it) }
                }
                if (missing.isNotEmpty()) {
                    val title = titles[row.conversationId] ?: loadTitle(dao, row.conversationId).also { titles[row.conversationId] = it }
                    missing = missing.filter { !title.second.contains(it) }
                }
                if (missing.isEmpty()) {
                    matches += Match(row.id, text, sender, senderKnown, titles[row.conversationId]?.first)
                    if (matches.size >= limit) break
                }
            }
            if (matches.size >= limit || page.size < pageSize || scanned >= maxScannedRows) break
            val last = page.last()
            cursor = SearchSql.Cursor(last.messageTimestamp, last.id)
        }
        lastScanned = scanned

        // Full rows only for the matches, mapped with the values decrypted above.
        val rows = HashMap<Long, MessageRow>(matches.size * 2)
        matches.map { it.id }.chunked(ID_CHUNK).forEach { ids -> dao.messageRowsByIds(ids).associateByTo(rows) { it.message.id } }
        return matches.mapNotNull { m ->
            rows[m.id]?.toDomain(cipher, text = m.text, sender = m.sender, senderKnown = m.senderKnown, title = m.title)
        }
    }

    private class Match(val id: Long, val text: String, val sender: String?, val senderKnown: Boolean, val title: String?)

    private suspend fun loadTitle(dao: VaultDao, conversationId: Long): Pair<String, String> {
        val title = dao.conversation(conversationId)?.let { c ->
            cipher.decrypt(c.title, Field.CONVERSATION_TITLE, c.binding())
        }.orEmpty()
        return title to SearchText.fold(title)
    }

    companion object {
        const val PAGE_SIZE = 400
        const val MAX_SCANNED_ROWS = 50_000
        private const val ID_CHUNK = 500
    }
}
