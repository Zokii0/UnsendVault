package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.data.Fx.MIN
import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.marker
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueryAndDeleteTest : RoomTestBase() {

    private suspend fun search(text: String?, q: MessageQuery = MessageQuery()) =
        repo.observeMessages(q.copy(text = text)).first().map { it.text }.toSet()

    private suspend fun seed() {
        repo.ingest(
            parsed(
                msg("Hello world, meeting at noon", sender = "Alice", ts = T0, key = "c1", title = "Alice"),
                msg("50% off today_only", sender = "Shop", ts = T0 + MIN, key = "c1", title = "Alice"),
                msg("我们明天见面吧", sender = "李雷", ts = T0 + 2 * MIN, key = "c1", title = "Alice"),
                msg("Café crème brûlée", sender = "Zoë", ts = T0 + 3 * MIN, key = "c1", title = "Alice"),
            ),
            T0,
        )
        repo.ingest(
            parsed(
                msg("Project kickoff notes", sender = "Bob", ts = T0 + 4 * MIN, key = "g1", title = "Work Group", isGroup = true),
                msg("quote \"inside\" here", sender = "Bob", ts = T0 + 5 * MIN, key = "g1", title = "Work Group", isGroup = true),
                pkg = "org.other.app",
                label = "Other",
            ).let { p -> p.copy(messages = p.messages.map { it.copy(packageName = "org.other.app") }) },
            T0,
        )
    }

    @Test
    fun fts_prefixAndMultiTerm() = runBlocking {
        seed()
        assertEquals(setOf("Hello world, meeting at noon"), search("meet"))
        assertEquals(setOf("Hello world, meeting at noon"), search("noon HELLO"))
        assertEquals(setOf("Project kickoff notes"), search("kick"))
        assertEquals(emptySet<String>(), search("hello kickoff"))
    }

    @Test
    fun fts_matchesSenderAndTitle_andUnicodeFolding() = runBlocking {
        seed()
        assertEquals(setOf("Project kickoff notes", "quote \"inside\" here"), search("bob"))
        assertEquals(setOf("Project kickoff notes", "quote \"inside\" here"), search("Work Group"))
        assertEquals(setOf("Café crème brûlée"), search("CAFÉ"))
    }

    @Test
    fun specialCharacters_neverBreakTheQuery() = runBlocking {
        seed()
        val nasty = listOf(
            "\"", "\"\"", "*", "-", "-hello", "hello*", "hello\"", "(", ")", "hello OR world", "NOT hello", "AND",
            "NEAR/2", "text:hello", "'", "'; DROP TABLE messages; --", "\\", "^", "{}", "hello -world", "%", "_",
        )
        nasty.forEach { q -> search(q) } // must not throw
        assertEquals(setOf("quote \"inside\" here"), search("\"inside\""))
        assertEquals(setOf("Hello world, meeting at noon"), search("hello*"))
        assertEquals(setOf("Hello world, meeting at noon"), search("-hello"))
        assertEquals(6, db.dao().allMessages().size) // tables intact
    }

    @Test
    fun likeFallback_shortAndCjk_andEscapesWildcards() = runBlocking {
        seed()
        assertEquals(setOf("我们明天见面吧"), search("见面"))
        assertEquals(setOf("我们明天见面吧"), search("李"))
        assertEquals(setOf("Hello world, meeting at noon", "Project kickoff notes"), search("no"))
        assertEquals(setOf("50% off today_only"), search("%"))
        assertEquals(setOf("50% off today_only"), search("_"))
        assertEquals(setOf("50% off today_only"), search("50%"))
    }

    @Test
    fun filters_packagesConversationDateFavoritesDeleted_andLimit() = runBlocking {
        seed()
        assertEquals(2, repo.observeMessages(MessageQuery(packages = setOf("org.other.app"))).first().size)
        assertEquals(6, repo.observeMessages(MessageQuery(packages = setOf("org.other.app", Fx.PKG))).first().size)
        val c1 = repo.observeConversations(setOf(Fx.PKG)).first().single()
        assertEquals(4, repo.observeMessages(MessageQuery(conversationId = c1.id)).first().size)
        // Inclusive date range.
        val range = repo.observeMessages(MessageQuery(fromMillis = T0 + MIN, toMillis = T0 + 3 * MIN)).first()
        assertEquals(listOf(T0 + 3 * MIN, T0 + 2 * MIN, T0 + MIN), range.map { it.messageTimestamp })
        // Newest first + limit.
        assertEquals(listOf(T0 + 5 * MIN, T0 + 4 * MIN), repo.observeMessages(MessageQuery(limit = 2)).first().map { it.messageTimestamp })

        val target = range.first()
        repo.setFavorite(target.id, true)
        assertEquals(listOf(target.id), repo.observeMessages(MessageQuery(favoritesOnly = true)).first().map { it.id })
        assertEquals(listOf(target.id), repo.observeFavorites(10).first().map { it.id })

        repo.ingest(parsed(markers = listOf(marker("Shop", T0 + MIN))), T0)
        val deleted = repo.observeMessages(MessageQuery(possiblyDeletedOnly = true)).first()
        assertEquals(listOf("50% off today_only"), deleted.map { it.text })
        // Combined: text + package + favorites.
        assertEquals(emptyList<Long>(), repo.observeMessages(MessageQuery(text = "Café", favoritesOnly = true, packages = setOf("org.other.app"))).first().map { it.id })
        assertEquals(listOf(target.id), repo.observeMessages(MessageQuery(text = "Café", favoritesOnly = true, packages = setOf(Fx.PKG))).first().map { it.id })

        repo.setFavorite(target.id, false)
        assertTrue(repo.observeFavorites(10).first().isEmpty())
    }

    @Test
    fun recent_stats_appSummaries() = runBlocking {
        seed()
        assertEquals(listOf(T0 + 5 * MIN, T0 + 4 * MIN, T0 + 3 * MIN), repo.observeRecent(3).first().map { it.messageTimestamp })
        val first = repo.observeRecent(1).first().single()
        repo.setFavorite(first.id, true)
        repo.ingest(parsed(markers = listOf(marker("Alice", T0))), T0 + 99)
        val stats = repo.observeStats().first()
        assertEquals(6, stats.totalMessages)
        assertEquals(2, stats.totalConversations)
        assertEquals(1, stats.favorites)
        assertEquals(1, stats.possiblyDeleted)
        assertEquals(2, stats.apps)
        assertEquals(T0, stats.oldestCapturedAt)
        val apps = repo.observeAppSummaries().first()
        assertEquals(listOf("org.other.app", Fx.PKG), apps.map { it.packageName })
        assertEquals(listOf("Other", Fx.LABEL), apps.map { it.appLabel })
        assertEquals(listOf(2, 4), apps.map { it.messageCount })
        assertEquals(listOf(1, 1), apps.map { it.conversationCount })
        assertEquals(T0 + 5 * MIN, apps.first().lastMessageAt)
    }

    @Test
    fun deleteMessages_cascadesRevisions_prunesEmptyConversation_andFts() = runBlocking {
        repo.ingest(parsed(msg("v1 alpha")), T0)
        repo.ingest(parsed(msg("v2 alpha")), T0 + 1)
        repo.ingest(parsed(msg("other", key = "c2", ts = T0 + 5)), T0)
        assertEquals(1, db.dao().allRevisions().size)
        val id = repo.observeMessages(MessageQuery(text = "alpha")).first().single().id
        repo.deleteMessages(listOf(id))
        assertTrue(db.dao().allRevisions().isEmpty())
        assertTrue(search("alpha").isEmpty())
        assertEquals(listOf("c2"), repo.observeConversations().first().map { it.conversationKey })
        assertNull(repo.observeMessage(id).first())
    }

    @Test
    fun deleteConversation_app_all() = runBlocking {
        seed()
        repo.ingest(parsed(msg("rev a", key = "c9")), T0)
        repo.ingest(parsed(msg("rev b", key = "c9")), T0 + 1)
        val c9 = repo.observeConversations().first().first { it.conversationKey == "c9" }
        repo.deleteConversation(c9.id)
        assertTrue(db.dao().allRevisions().isEmpty())
        assertEquals(6, repo.observeStats().first().totalMessages)

        repo.deleteApp("org.other.app")
        assertEquals(setOf(Fx.PKG), repo.observeAppSummaries().first().map { it.packageName }.toSet())
        assertTrue(search("kickoff").isEmpty())

        repo.deleteAll()
        val stats = repo.observeStats().first()
        assertEquals(0, stats.totalMessages)
        assertEquals(0, stats.totalConversations)
        assertTrue(search("hello").isEmpty())
    }

    @Test
    fun deleteOlderThan_keepsFavorites_prunesEmptyConversations_recomputesAggregates() = runBlocking {
        repo.ingest(parsed(msg("ancient", ts = T0 - 40 * Fx.MIN * 60 * 24, key = "old")), T0)
        repo.ingest(parsed(msg("old fav", ts = T0 - 31 * Fx.MIN * 60 * 24, key = "c1"), msg("old plain", ts = T0 - 30 * Fx.MIN * 60 * 24 - 1, key = "c1"), msg("fresh", ts = T0, key = "c1")), T0)
        val fav = repo.observeMessages(MessageQuery(text = "old fav")).first().single()
        repo.setFavorite(fav.id, true)

        val cutoff = T0 - 30L * 24 * 60 * MIN
        assertEquals(2, repo.deleteOlderThan(cutoff, keepFavorites = true))
        assertEquals(setOf("old fav", "fresh"), search(null))
        val convs = repo.observeConversations().first()
        assertEquals(listOf("c1"), convs.map { it.conversationKey })
        assertEquals(2, convs.single().messageCount)
        assertEquals(MessageStatus.ACTIVE, repo.observeMessage(fav.id).first()!!.message.status)

        assertEquals(1, repo.deleteOlderThan(cutoff, keepFavorites = false))
        assertEquals(setOf("fresh"), search(null))
        assertEquals(1, repo.observeConversations().first().single().messageCount)
    }
}
