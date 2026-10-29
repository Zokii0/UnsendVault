package com.riyadm.unsendvault.widget

import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.core.model.VaultStats
import com.riyadm.unsendvault.widget.WidgetTestData.FILTERED_TEXT
import com.riyadm.unsendvault.widget.WidgetTestData.GROUP_TITLE
import com.riyadm.unsendvault.widget.WidgetTestData.REDACTED_TEXT
import com.riyadm.unsendvault.widget.WidgetTestData.SECRET_TEXT
import com.riyadm.unsendvault.widget.WidgetTestData.SENDER
import com.riyadm.unsendvault.widget.WidgetTestData.message
import com.riyadm.unsendvault.widget.WidgetTestData.messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPrivacyTest {

    @Test
    fun `default settings are metadata only`() {
        assertEquals(WidgetPrivacyMode.METADATA, WidgetPrivacy.mode(VaultSettings()))
    }

    @Test
    fun `content requires opt in and app lock off`() {
        assertEquals(WidgetPrivacyMode.CONTENT, WidgetPrivacy.mode(VaultSettings(widgetShowContent = true, appLockEnabled = false)))
        assertEquals(WidgetPrivacyMode.LOCKED, WidgetPrivacy.mode(VaultSettings(widgetShowContent = true, appLockEnabled = true)))
        assertEquals(WidgetPrivacyMode.LOCKED, WidgetPrivacy.mode(VaultSettings(widgetShowContent = false, appLockEnabled = true)))
    }

    @Test
    fun `redacted and filtered kinds are never displayable`() {
        assertTrue(WidgetPrivacy.isContentDisplayable(ContentKind.TEXT))
        assertTrue(WidgetPrivacy.isContentDisplayable(ContentKind.MEDIA_PLACEHOLDER))
        assertFalse(WidgetPrivacy.isContentDisplayable(ContentKind.SYSTEM_REDACTED))
        assertFalse(WidgetPrivacy.isContentDisplayable(ContentKind.FILTERED_SENSITIVE))
    }

    @Test
    fun `metadata mode groups by app and carries no content`() {
        val display = WidgetPrivacy.messageList(messages, WidgetPrivacyMode.METADATA) as MessageListDisplay.ByApp
        assertEquals(listOf("WhatsApp", "Signal"), display.groups.map { it.appLabel })
        assertEquals(4, display.groups[0].messageCount)
        assertEquals(messages[0].messageTimestamp, display.groups[0].latestAt)
        assertNoLeak(display.toString())
    }

    @Test
    fun `content mode shows sender and preview but never redacted or filtered text`() {
        val display = WidgetPrivacy.messageList(messages, WidgetPrivacyMode.CONTENT) as MessageListDisplay.Messages
        val first = display.rows[0]
        assertEquals(SENDER, first.title)
        assertEquals(SECRET_TEXT, first.preview)
        assertFalse(first.contentHidden)

        val hidden = display.rows.filter { it.contentHidden }
        assertEquals(2, hidden.size)
        hidden.forEach {
            assertNull(it.title)
            assertNull(it.preview)
        }
        val all = display.toString()
        assertFalse(all.contains(REDACTED_TEXT))
        assertFalse(all.contains(FILTERED_TEXT))
        assertFalse(all.contains("Carol"))
        assertFalse(all.contains("Dave"))
    }

    @Test
    fun `content mode falls back to conversation title when sender is missing`() {
        val display = WidgetPrivacy.messageList(listOf(message(1, sender = null)), WidgetPrivacyMode.CONTENT) as MessageListDisplay.Messages
        assertEquals(GROUP_TITLE, display.rows.single().title)
    }

    @Test
    fun `locked mode exposes only a count`() {
        val display = WidgetPrivacy.messageList(messages, WidgetPrivacyMode.LOCKED, lockedCount = 42)
        assertEquals(MessageListDisplay.Locked(42), display)
        assertNoLeak(display.toString())
        assertFalse(display.toString().contains("WhatsApp"))
    }

    @Test
    fun `empty list is empty in every unlocked mode`() {
        assertEquals(MessageListDisplay.Empty, WidgetPrivacy.messageList(emptyList(), WidgetPrivacyMode.METADATA))
        assertEquals(MessageListDisplay.Empty, WidgetPrivacy.messageList(emptyList(), WidgetPrivacyMode.CONTENT))
    }

    @Test
    fun `count display flags lock and clamps negatives`() {
        val stats = VaultStats(10, 3, 1, 0, 2, null, null)
        assertEquals(CountDisplay(10, 3, 2, locked = true), WidgetPrivacy.count(stats, WidgetPrivacyMode.LOCKED))
        assertEquals(CountDisplay(0, 0, 0, locked = false), WidgetPrivacy.count(stats.copy(totalMessages = -1, totalConversations = -1, apps = -1), WidgetPrivacyMode.METADATA))
    }

    @Test
    fun `app summary sorts by recent activity, caps rows and hides names when locked`() {
        val summaries = (1..10).map { AppSummary("pkg.$it", "App $it", it * 10, it, lastMessageAt = it * 1000L) } +
            AppSummary("pkg.none", "No activity", 5, 1, null) + AppSummary("pkg.zero", "Zero", 0, 0, 99_999L)
        val apps = WidgetPrivacy.appSummary(summaries, WidgetPrivacyMode.METADATA, null) as AppSummaryDisplay.Apps
        assertEquals(WidgetPrivacy.MAX_APP_ROWS, apps.rows.size)
        assertEquals("App 10", apps.rows.first().appLabel)
        assertFalse(apps.rows.any { it.messageCount == 0 })

        val stats = VaultStats(550, 60, 0, 0, 10, null, null)
        val locked = WidgetPrivacy.appSummary(summaries, WidgetPrivacyMode.LOCKED, stats)
        assertEquals(AppSummaryDisplay.Locked(550, 10), locked)
        assertEquals(AppSummaryDisplay.Empty, WidgetPrivacy.appSummary(emptyList(), WidgetPrivacyMode.METADATA, null))
    }

    @Test
    fun `capture state precedence`() {
        assertEquals(CaptureState.ACCESS_NEEDED, WidgetPrivacy.captureState(accessGranted = false, captureEnabled = true, connected = true))
        assertEquals(CaptureState.PAUSED, WidgetPrivacy.captureState(accessGranted = true, captureEnabled = false, connected = false))
        assertEquals(CaptureState.DISCONNECTED, WidgetPrivacy.captureState(accessGranted = true, captureEnabled = true, connected = false))
        assertEquals(CaptureState.ACTIVE, WidgetPrivacy.captureState(accessGranted = true, captureEnabled = true, connected = true))
    }

    @Test
    fun `sanitize flattens, strips bidi overrides and truncates safely`() {
        assertNull(WidgetPrivacy.sanitize(null, 10))
        assertNull(WidgetPrivacy.sanitize(" \n\t ", 10))
        assertEquals("a b c", WidgetPrivacy.sanitize("a\nb\r\n\tc", 10))
        assertEquals("abcdef", WidgetPrivacy.sanitize("abc‮def⁦", 20))
        assertEquals("abcd…", WidgetPrivacy.sanitize("abcdefghij", 5))
        // Never split a surrogate pair.
        val emoji = "abc😀def"
        val cut = WidgetPrivacy.sanitize(emoji, 5)!!
        assertFalse(cut.dropLast(1).last().isHighSurrogate())
        assertTrue(cut.endsWith("…"))
    }

    private fun assertNoLeak(text: String) {
        assertFalse(text.contains(SECRET_TEXT))
        assertFalse(text.contains(SENDER))
        assertFalse(text.contains(GROUP_TITLE))
        assertFalse(text.contains("meet at 6"))
        assertFalse(text.contains("Bob"))
    }
}
