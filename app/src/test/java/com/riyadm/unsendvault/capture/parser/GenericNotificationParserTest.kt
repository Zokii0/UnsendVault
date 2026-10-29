package com.riyadm.unsendvault.capture.parser

import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.Fixtures.T0
import com.riyadm.unsendvault.capture.Fixtures.msg
import com.riyadm.unsendvault.capture.Fixtures.snapshot
import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.adapter.GenericAdapter
import com.riyadm.unsendvault.core.model.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericNotificationParserTest {
    private val parser = GenericAdapter(CapturePlaceholders())

    private fun parsed(s: NotificationSnapshot): ParseOutcome.Parsed {
        val outcome = parser.parseNotification(s)
        assertTrue("expected Parsed but was $outcome", outcome is ParseOutcome.Parsed)
        return outcome as ParseOutcome.Parsed
    }

    private fun messages(s: NotificationSnapshot): List<CapturedMessage> = parsed(s).messages

    private fun rejected(s: NotificationSnapshot): RejectReason {
        val outcome = parser.parseNotification(s)
        assertTrue("expected Rejected but was $outcome", outcome is ParseOutcome.Rejected)
        return (outcome as ParseOutcome.Rejected).reason
    }

    @Test
    fun messagingStyleOneToOne() {
        val out = messages(
            snapshot(
                title = "Alex Rivera",
                shortcutId = "conv-42",
                messages = listOf(msg("Alex Rivera", "Hey, are you around?", T0), msg("Alex Rivera", "Call me later", T0 + 1000)),
            ),
        )
        assertEquals(2, out.size)
        out.forEach {
            assertEquals("shortcut:conv-42", it.conversationKey)
            assertEquals("Alex Rivera", it.conversationTitle)
            assertEquals("Alex Rivera", it.sender)
            assertFalse(it.isGroup)
            assertTrue(it.hasPreciseTimestamp)
            assertEquals(ContentKind.TEXT, it.contentKind)
        }
        assertEquals(listOf(T0, T0 + 1000), out.map { it.messageTimestamp })
    }

    @Test
    fun messagingStyleGroupUsesConversationTitle() {
        val out = messages(
            snapshot(
                title = "Project Team",
                conversationTitle = "Project Team",
                isGroupConversation = true,
                messages = listOf(msg("Alex Rivera", "Standup in 5", T0), msg("Sam Lee", "On my way", T0 + 5)),
            ),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.isGroup && it.conversationTitle == "Project Team" && it.conversationKey == "name:project team" })
        assertEquals(listOf("Alex Rivera", "Sam Lee"), out.map { it.sender })
    }

    @Test
    fun groupDetectedWithoutFlagWhenSeveralSendersShareAConversationTitle() {
        val out = messages(
            snapshot(
                conversationTitle = "Weekend Trip",
                messages = listOf(msg("Alex Rivera", "Tickets booked", T0), msg("Sam Lee", "Nice", T0 + 1)),
            ),
        )
        assertTrue(out.all { it.isGroup })
    }

    @Test
    fun selfMessagesUseConsistentLabel() {
        val out = messages(
            snapshot(
                title = "Alex Rivera",
                selfName = "Jordan",
                messages = listOf(
                    msg("Alex Rivera", "Lunch?", T0),
                    msg(null, "Sure", T0 + 1),
                    msg("Jordan", "12:30 works", T0 + 2),
                ),
            ),
        )
        assertEquals(listOf("Alex Rivera", "You", "You"), out.map { it.sender })
        assertTrue(out.all { it.conversationKey == "name:alex rivera" })
    }

    @Test
    fun repeatedHistoryIsDeduplicated() {
        val history = listOf(msg("Alex Rivera", "One", T0), msg("Alex Rivera", "Two", T0 + 1))
        val out = messages(
            snapshot(
                title = "Alex Rivera",
                messages = history + history + listOf(msg("Alex Rivera", "Three", T0 + 2)),
                historicMessages = listOf(msg("Alex Rivera", "One", T0), msg("Alex Rivera", "Zero", T0 - 10)),
            ),
        )
        assertEquals(listOf("Zero", "One", "Two", "Three"), out.map { it.text })
    }

    @Test
    fun historicVersionAtSameTimestampIsReplacedByCurrent() {
        val out = messages(
            snapshot(
                title = "Alex Rivera",
                messages = listOf(msg("Alex Rivera", "See you at 6 (edited)", T0)),
                historicMessages = listOf(msg("Alex Rivera", "See you at 5", T0)),
            ),
        )
        assertEquals(listOf("See you at 6 (edited)"), out.map { it.text })
    }

    @Test
    fun editedTextKeepsSameIdentityAcrossNotifications() {
        val first = messages(snapshot(title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "Meet at 5", T0)))).single()
        val second = messages(snapshot(title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "Meet at 6", T0)))).single()
        assertEquals(first.conversationKey, second.conversationKey)
        assertEquals(first.sender, second.sender)
        assertEquals(first.messageTimestamp, second.messageTimestamp)
        assertEquals("Meet at 6", second.text)
    }

    @Test
    fun mediaPlaceholdersFromMimeAndText() {
        val out = messages(
            snapshot(
                title = "Alex Rivera",
                messages = listOf(
                    msg("Alex Rivera", null, T0, mime = "image/jpeg"),
                    msg("Alex Rivera", "📷 Photo", T0 + 1),
                    msg("Alex Rivera", "🎤 Voice message", T0 + 2),
                    msg("Alex Rivera", "Sticker", T0 + 3),
                    msg("Alex Rivera", "sent an attachment", T0 + 4),
                    msg("Alex Rivera", "📷 Look at this view", T0 + 5),
                ),
            ),
        )
        assertEquals(6, out.size)
        assertEquals(ContentKind.MEDIA_PLACEHOLDER, out[0].contentKind)
        assertEquals("📷 Photo", out[0].text)
        assertTrue(out.subList(1, 5).all { it.contentKind == ContentKind.MEDIA_PLACEHOLDER })
        assertEquals(ContentKind.TEXT, out[5].contentKind)
    }

    @Test
    fun inboxStyleLinesSplitSenders() {
        val out = messages(
            snapshot(
                title = "Project Team",
                isGroupConversation = true,
                textLines = listOf("Alex Rivera: First", "Sam Lee: Second"),
                whenTime = T0,
            ),
        )
        assertEquals(listOf("Alex Rivera", "Sam Lee"), out.map { it.sender })
        assertEquals(listOf("First", "Second"), out.map { it.text })
        assertTrue(out.all { !it.hasPreciseTimestamp && it.messageTimestamp == T0 && it.conversationTitle == "Project Team" })
    }

    @Test
    fun bigTextPreferredOverTruncatedText() {
        val out = messages(snapshot(title = "Alex Rivera", text = "This is a long mess…", bigText = "This is a long message that was truncated"))
        assertEquals("This is a long message that was truncated", out.single().text)
        assertEquals("Alex Rivera", out.single().sender)
        assertFalse(out.single().hasPreciseTimestamp)
    }

    @Test
    fun plainTitleAndText() {
        val s = snapshot(title = "Alex Rivera (2)", text = "Note: bring snacks", postTime = T0)
        val m = messages(s).single()
        assertEquals("Note: bring snacks", m.text)
        assertEquals("Alex Rivera", m.conversationTitle)
        assertEquals("name:alex rivera", m.conversationKey)
        assertEquals(T0, m.messageTimestamp)
    }

    @Test
    fun summaryNoiseIsSkipped() {
        assertEquals(RejectReason.NOISE, rejected(snapshot(title = "Chat", text = "5 new messages")))
        assertEquals(RejectReason.NOISE, rejected(snapshot(title = "Chat", text = "3 messages from 2 chats")))
        assertEquals(RejectReason.NOISE, rejected(snapshot(title = "Chat", text = "Checking for new messages")))
        assertEquals(RejectReason.NOISE, rejected(snapshot(title = "Chat", text = "Sending…")))
        assertEquals(RejectReason.NOISE, rejected(snapshot(title = "Chat", text = "Backup in progress")))
        assertEquals(RejectReason.GROUP_SUMMARY, rejected(snapshot(title = "Chat", text = "Alex Rivera: hi", isGroupSummary = true)))
    }

    @Test
    fun callsAreExcluded() {
        assertEquals(RejectReason.CALL, rejected(snapshot(title = "Alex Rivera", text = "Incoming voice call", category = "call")))
        assertEquals(RejectReason.CALL, rejected(snapshot(title = "Alex Rivera", text = "Missed video call")))
        assertEquals(RejectReason.CALL, rejected(snapshot(title = "Alex Rivera", text = "2 missed calls")))
        assertEquals(RejectReason.CALL, rejected(snapshot(title = "Missed call", text = "Alex Rivera")))
    }

    @Test
    fun ongoingAndNonMessageCategoriesRejected() {
        assertEquals(RejectReason.ONGOING, rejected(snapshot(title = "x", text = "y", isOngoing = true)))
        assertEquals(RejectReason.NOT_A_MESSAGE, rejected(snapshot(title = "Upload", text = "42%", category = "progress")))
    }

    @Test
    fun genericParserNeverReportsDeletionMarkers() {
        val p = parsed(snapshot(title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "This message was deleted", T0))))
        assertTrue(p.deletionMarkers.isEmpty())
        assertEquals("This message was deleted", p.messages.single().text)
    }

    @Test
    fun systemRedactedPlaceholderText() {
        val m = messages(snapshot(title = "Bank", text = "Sensitive notification content hidden")).single()
        assertEquals(ContentKind.SYSTEM_REDACTED, m.contentKind)
        assertEquals("Content hidden by Android", m.text)
    }

    @Test
    fun messagingStyleWithEmptyTextsIsSystemRedacted() {
        val out = messages(
            snapshot(title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "", T0), msg("Alex Rivera", null, T0 + 1))),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.contentKind == ContentKind.SYSTEM_REDACTED && it.hasPreciseTimestamp })
        assertEquals(listOf(T0, T0 + 1), out.map { it.messageTimestamp })
    }

    @Test
    fun messagingTemplateWithoutAnyContentIsSystemRedacted() {
        val m = messages(snapshot(title = "Alex Rivera", template = "android.app.Notification\$MessagingStyle")).single()
        assertEquals(ContentKind.SYSTEM_REDACTED, m.contentKind)
        assertEquals("Alex Rivera", m.sender)
    }

    @Test
    fun blankMessageAmongVisibleOnesIsSkipped() {
        val out = messages(snapshot(title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "Visible", T0), msg("Alex Rivera", " ", T0 + 1))))
        assertEquals(listOf("Visible"), out.map { it.text })
    }

    @Test
    fun titleThatOnlyNamesTheAppIsNotAConversation() {
        val out = messages(snapshot(packageName = "com.discord", title = "Discord", text = "alex_r: gg"))
        assertEquals("alex_r", out.single().sender)
        assertEquals("gg", out.single().text)
    }

    @Test
    fun missingTimestampFallsBackToPostTime() {
        val m = messages(snapshot(title = "Alex Rivera", postTime = T0 + 5, messages = listOf(msg("Alex Rivera", "hi", 0)))).single()
        assertEquals(T0 + 5, m.messageTimestamp)
        assertFalse(m.hasPreciseTimestamp)
    }
}
