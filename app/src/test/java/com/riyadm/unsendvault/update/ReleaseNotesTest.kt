package com.riyadm.unsendvault.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val fixtureBody: String = ReleaseParser.parse(
        checkNotNull(javaClass.classLoader?.getResource("update/github_release_latest.json")).readText(),
    ).let { (it as ReleaseParser.Result.Release).release.notes }

    @Test
    fun prefersWhatChangedSection_andStripsMarkdown() {
        val excerpt = ReleaseNotes.excerpt(fixtureBody)
        assertTrue(excerpt, excerpt.startsWith("First release."))
        assertTrue(excerpt, excerpt.contains("• Fix identity matching"))
        assertFalse(excerpt, excerpt.contains("## "))
        assertFalse(excerpt, excerpt.contains("Verify your download")) // next section is cut
        assertFalse(excerpt, excerpt.contains("sha256sum"))
        assertTrue(excerpt.length <= ReleaseNotes.DEFAULT_MAX_CHARS + 1)
    }

    @Test
    fun stripsHeadingsLinksImagesHtmlAndEmphasis() {
        val body = """
            # Big news
            Read **the** [changelog](https://example.com/x) and `notes`.
            ![logo](https://example.com/logo.png)
            <script>alert(1)</script><b>bold</b>
            <!-- hidden -->
            * item one
            | a | b |
            |---|---|
            See https://example.com/raw
        """.trimIndent()
        val excerpt = ReleaseNotes.excerpt(body)
        assertEquals("Big news\nRead the changelog and notes.\nlogo\nalert(1)bold\n\n• item one\nSee", excerpt)
    }

    @Test
    fun truncatesOnAWordBoundary() {
        val body = (1..200).joinToString(" ") { "word$it" }
        val excerpt = ReleaseNotes.excerpt(body, maxChars = 100)
        assertTrue(excerpt.endsWith("…"))
        assertTrue(excerpt.length <= 101)
        assertFalse(excerpt.removeSuffix("…").endsWith(" "))
        assertTrue(body.startsWith(excerpt.removeSuffix("…")))
    }

    @Test
    fun emptyBody() {
        assertEquals("", ReleaseNotes.excerpt(""))
        assertEquals("", ReleaseNotes.excerpt("<!-- only a comment -->"))
    }
}
