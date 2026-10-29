package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.repo.SearchSql
import com.riyadm.unsendvault.data.repo.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSqlTest {
    @Test
    fun terms_areFoldedLetterDigitRuns() {
        assertEquals(listOf("hello", "world"), SearchText.terms("Hello, WORLD!"))
        assertEquals(listOf("and", "not"), SearchText.terms("AND NOT"))
        assertEquals(listOf("inside"), SearchText.terms("\"inside\""))
        assertEquals(listOf("drop", "table"), SearchText.terms("'; DROP TABLE --"))
        assertEquals(listOf("50"), SearchText.terms("50%"))
        assertEquals(listOf("cafe", "creme"), SearchText.terms("CAFÉ  Crème"))
        assertEquals(listOf("hello"), SearchText.terms("hello hello"))
        assertEquals(listOf("見面"), SearchText.terms("見面"))
        assertEquals(listOf("สวัสดีครับ"), SearchText.terms("สวัสดีครับ"))
    }

    @Test
    fun terms_withoutLettersOrDigits_areLiteralTokens_andBlankIsNoFilter() {
        assertEquals(listOf("%"), SearchText.terms("%"))
        assertEquals(listOf("?!", "\""), SearchText.terms(" ?!  \" "))
        assertEquals(emptyList<String>(), SearchText.terms("   "))
        assertEquals(emptyList<String>(), SearchText.terms(null))
    }

    @Test
    fun fold_caseDiacriticsAndInvisibleCharacters() {
        assertEquals("cafe creme brulee", SearchText.fold("Café Crème BRÛLÉE"))
        assertEquals("zoe", SearchText.fold("Zoë"))
        assertEquals("hello", SearchText.fold("hel​lo"))
        // Precomposed and decomposed input fold the same way.
        assertEquals(SearchText.fold("é"), SearchText.fold("é"))
        // Locale.ROOT, not the device locale (Turkish dotted/dotless I).
        assertEquals("istanbul", SearchText.fold("İSTANBUL"))
        assertEquals("我们明天见面吧", SearchText.fold("我们明天见面吧"))
        assertTrue(SearchText.matchesAll(listOf("cafe", "zoe"), SearchText.fold("Café"), null, SearchText.fold("Zoë")))
        assertFalse(SearchText.matchesAll(listOf("cafe", "bob"), SearchText.fold("Café"), null))
    }

    @Test
    fun page_bindsEverythingAsArguments_andAddsKeysetCursor() {
        val query = MessageQuery(text = "x'y", packages = setOf("a.b"), conversationId = 7, fromMillis = 1, toMillis = 2, favoritesOnly = true, possiblyDeletedOnly = true, limit = 5)
        val built = SearchSql.build(query)
        assertFalse(built.sql.contains("x'y"))
        assertFalse(built.sql.contains("a.b"))
        assertTrue(built.sql.contains("m.is_favorite = 1"))
        assertTrue(built.sql.contains("m.status = 'POSSIBLY_DELETED'"))
        assertTrue(built.sql.endsWith("ORDER BY m.message_timestamp DESC, m.id DESC LIMIT ?"))
        // The text never reaches SQL: it is matched after decryption.
        assertEquals(listOf("a.b", 7L, 1L, 2L, 5), built.args)

        val page = SearchSql.scanPage(query, SearchSql.Cursor(100, 9), pageSize = 400)
        assertFalse("the scan needs no join", page.sql.contains("JOIN"))
        assertTrue(page.sql.contains("(m.message_timestamp < ? OR (m.message_timestamp = ? AND m.id < ?))"))
        assertEquals(listOf("a.b", 7L, 1L, 2L, 100L, 100L, 9L, 400), page.args)
    }

    @Test
    fun build_clampsLimit() {
        assertEquals(SearchSql.MAX_LIMIT, SearchSql.build(MessageQuery(limit = Int.MAX_VALUE)).args.last())
        assertEquals(0, SearchSql.build(MessageQuery(limit = -3)).args.last())
    }
}
