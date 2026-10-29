package com.riyadm.unsendvault.data

import androidx.room.withTransaction
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import com.riyadm.unsendvault.data.db.ConversationEntity
import com.riyadm.unsendvault.data.db.MessageEntity
import com.riyadm.unsendvault.data.repo.MessageSearch
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.FieldCipher.Field
import com.riyadm.unsendvault.data.security.FieldCipher.IndexDomain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Paging, caps, Flow re-runs and a timing measurement of the decrypt-and-match search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchScanTest : RoomTestBase() {

    @Test
    fun paging_returnsSameResultsAsOnePage_newestFirst_withTiesOnTimestamp() = runBlocking {
        // 9 messages, several sharing a timestamp, so the keyset cursor must use the id tie-breaker.
        val texts = (1..9).map { "note number $it" }
        repo.ingest(parsed(*texts.mapIndexed { i, t -> msg(t, ts = T0 + (i / 3) * Fx.MIN, sender = "S$i") }.toTypedArray()), T0)
        val query = MessageQuery(text = "note")
        val reference = repo.observeMessages(query).first().map { it.id }
        assertEquals(9, reference.size)
        for (pageSize in listOf(1, 2, 4, 9, 50)) {
            val search = MessageSearch(db.cipher, pageSize = pageSize)
            assertEquals("pageSize=$pageSize", reference, search.run(db.dao(), query).map { it.id })
            assertEquals(9, search.lastScanned)
        }
        // Limit stops the scan early.
        val limited = MessageSearch(db.cipher, pageSize = 2)
        assertEquals(reference.take(3), limited.run(db.dao(), query.copy(limit = 3)).map { it.id })
        assertTrue(limited.lastScanned < 9)
        // The scan cap bounds the work: only the newest rows are searched.
        val capped = MessageSearch(db.cipher, pageSize = 2, maxScannedRows = 4)
        assertEquals(reference.take(4), capped.run(db.dao(), query).map { it.id })
        assertEquals(4, capped.lastScanned)
    }

    @Test
    fun searchFlow_reRunsOnInvalidation() = runBlocking {
        repo.ingest(parsed(msg("alpha one")), T0)
        val emissions = mutableListOf<List<String>>()
        val job = launch {
            repo.observeMessages(MessageQuery(text = "alpha")).take(2).collect { list -> emissions += list.map { it.text } }
        }
        withTimeout(10_000) { while (emissions.isEmpty()) kotlinx.coroutines.delay(10) }
        repo.ingest(parsed(msg("alpha two", ts = T0 + 1)), T0)
        withTimeout(10_000) { job.join() }
        assertEquals(listOf("alpha one"), emissions[0])
        assertEquals(listOf("alpha two", "alpha one"), emissions[1])
    }

    /**
     * Measures a no-match search over 20,000 messages (every row is decrypted and folded; nothing matches, so the
     * whole prefilter is scanned). JVM/Robolectric numbers on the development machine, printed to the test output;
     * the assertion is only a loose sanity bound because the machine is shared.
     */
    @Test
    fun measure_noMatchSearch_over20kMessages() = runBlocking {
        val n = 20_000
        val cipher = db.cipher
        val words = listOf("lunch", "tomorrow", "call", "meeting", "photo", "ok", "thanks", "see you", "on my way", "café")
        val insertStart = System.nanoTime()
        db.withTransaction {
            val dao = db.dao()
            val conversationIds = (0 until 50).map { c ->
                val key = "conv-$c"
                val index = cipher.blindIndex(key, IndexDomain.CONVERSATION)
                val owner = FieldCipher.forConversation(Fx.PKG, index)
                dao.insertConversation(
                    ConversationEntity(
                        packageName = Fx.PKG, appLabel = Fx.LABEL, conversationIndex = index,
                        conversationKey = cipher.encrypt(key, Field.CONVERSATION_KEY, owner),
                        title = cipher.encrypt("Conversation $c", Field.CONVERSATION_TITLE, owner),
                        isGroup = false, createdAt = T0, lastMessageAt = T0, lastMessagePreview = null, messageCount = 0,
                    ),
                )
            }
            for (i in 0 until n) {
                val conversationId = conversationIds[i % conversationIds.size]
                val owner = FieldCipher.forMessage(conversationId)
                val text = "Message $i: ${words[i % words.size]} ${words[(i * 7) % words.size]} and some more ordinary words here"
                val sender = "Person ${i % 200}"
                dao.insertMessage(
                    MessageEntity(
                        conversationId = conversationId, packageName = Fx.PKG,
                        sender = cipher.encrypt(sender, Field.MESSAGE_SENDER, owner),
                        senderIndex = cipher.blindIndex(sender, IndexDomain.SENDER),
                        text = cipher.encrypt(text, Field.MESSAGE_TEXT, owner),
                        textIndex = cipher.blindIndex(text, IndexDomain.TEXT),
                        contentKind = "TEXT", messageTimestamp = T0 + i * 1000L, hasPreciseTimestamp = true,
                        firstCapturedAt = T0, lastUpdatedAt = T0, isFavorite = false, status = "ACTIVE",
                        deletionAdapterId = null, deletionMarkerText = null, deletionObservedAt = null, revisionCount = 1,
                    ),
                )
            }
        }
        val insertMs = (System.nanoTime() - insertStart) / 1_000_000

        val search = MessageSearch(cipher)
        val noMatch = MessageQuery(text = "zzqx-no-such-word")
        val times = (1..4).map {
            val t = System.nanoTime()
            val hits = search.run(db.dao(), noMatch)
            assertTrue(hits.isEmpty())
            assertEquals(n, search.lastScanned)
            (System.nanoTime() - t) / 1_000_000
        }
        // Typical query: a frequent word, first page of results (limit 500 of 20k).
        val t2 = System.nanoTime()
        val frequent = search.run(db.dao(), MessageQuery(text = "lunch"))
        val frequentMs = (System.nanoTime() - t2) / 1_000_000
        val frequentScanned = search.lastScanned
        // No text: one SQL query + decrypt of 500 rows.
        val t3 = System.nanoTime()
        val list = search.run(db.dao(), MessageQuery())
        val listMs = (System.nanoTime() - t3) / 1_000_000

        // Breakdown: decrypt + fold alone, on rows already in memory.
        val rows = db.dao().allMessages()
        val t4 = System.nanoTime()
        var chars = 0
        for (m in rows) chars += com.riyadm.unsendvault.data.repo.SearchText.fold(cipher.decrypt(m.text, Field.MESSAGE_TEXT, FieldCipher.forMessage(m.conversationId))).length
        val decryptMs = (System.nanoTime() - t4) / 1_000_000
        println(
            "SEARCH-PERF n=$n insert+encrypt=${insertMs}ms noMatch(ms, cold then warm)=$times " +
                "frequentWord(${frequent.size} hits, scanned $frequentScanned)=${frequentMs}ms list500=${listMs}ms " +
                "decrypt+fold only=${decryptMs}ms ($chars chars)",
        )
        assertEquals(500, frequent.size)
        assertEquals(500, list.size)
        assertTrue("no-match scan unreasonably slow: $times", times.drop(1).minOrNull()!! < 30_000)
    }
}
