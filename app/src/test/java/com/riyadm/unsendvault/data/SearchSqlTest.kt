package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.repo.SearchSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSqlTest {
    @Test
    fun matchExpression_quotesEveryTerm() {
        assertEquals("\"hello*\" \"world*\"", SearchSql.ftsMatchExpression("Hello, WORLD!"))
        assertEquals("\"and*\" \"not*\"", SearchSql.ftsMatchExpression("AND NOT"))
        assertEquals("\"inside*\"", SearchSql.ftsMatchExpression("\"inside\""))
        assertEquals("\"drop*\" \"table*\"", SearchSql.ftsMatchExpression("'; DROP TABLE --"))
    }

    @Test
    fun matchExpression_fallsBackForShortCjkAndSymbols() {
        assertNull(SearchSql.ftsMatchExpression("ab"))
        assertNull(SearchSql.ftsMatchExpression("hello a"))
        assertNull(SearchSql.ftsMatchExpression("見面"))
        assertNull(SearchSql.ftsMatchExpression("สวัสดีครับ"))
        assertNull(SearchSql.ftsMatchExpression("?!"))
    }

    @Test
    fun likePattern_escapesWildcards() {
        assertEquals("%50\\% off\\_x\\\\%", SearchSql.likePattern("50% off_x\\"))
    }

    @Test
    fun build_bindsEverythingAsArguments() {
        val built = SearchSql.build(
            MessageQuery(text = "x'y", packages = setOf("a.b"), conversationId = 7, fromMillis = 1, toMillis = 2, favoritesOnly = true, possiblyDeletedOnly = true, limit = 5),
        )
        assertFalse(built.sql.contains("x'y"))
        assertFalse(built.sql.contains("a.b"))
        assertTrue(built.sql.contains("m.is_favorite = 1"))
        assertTrue(built.sql.contains("m.status = 'POSSIBLY_DELETED'"))
        assertEquals(listOf("%x'y%", "%x'y%", "%x'y%", "a.b", 7L, 1L, 2L, 5), built.args)
    }
}
