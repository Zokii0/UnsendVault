package com.riyadm.unsendvault.capture.parser

import com.riyadm.unsendvault.capture.Fixtures.T0
import com.riyadm.unsendvault.capture.Fixtures.msg
import com.riyadm.unsendvault.capture.Fixtures.snapshot
import com.riyadm.unsendvault.capture.filter.SystemRedaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    @Test
    fun conversationKeyPriority() {
        assertEquals("shortcut:abc", ConversationKeys.of("abc", "Project Team", "Alex"))
        assertEquals("name:project team", ConversationKeys.of(null, "Project Team", "Alex"))
        assertEquals("name:alex", ConversationKeys.of("  ", null, "Alex"))
        assertEquals("unknown", ConversationKeys.of(null, null, " "))
    }

    @Test
    fun titleNormalization() {
        val expected = "name:project team"
        listOf(
            "Project Team",
            "  project   TEAM ",
            "Project Team (3 messages)",
            "Project Team (12 new messages)",
            "Project Team (2)",
            "Project Team · 4 new messages",
            "‪Project Team‬",
            "Project Team:",
        ).forEach { assertEquals(it, expected, ConversationKeys.of(null, it)) }
        assertEquals("Project Team", TextRules.cleanTitle("Project Team (3 messages)"))
        // Counters in the middle are part of the name.
        assertEquals("Team (2) Alpha", TextRules.cleanTitle("Team (2) Alpha"))
        assertNull(TextRules.cleanTitle("  "))
    }

    @Test
    fun noiseIsWholeTextOnly() {
        assertTrue(TextRules.isNoise("5 new messages"))
        assertTrue(TextRules.isNoise("12 messages from 4 chats"))
        assertTrue(TextRules.isNoise("Alex Rivera is typing…"))
        assertFalse(TextRules.isNoise("I have 5 new messages to answer"))
        assertFalse(TextRules.isNoise("Sending you the file now"))
    }

    @Test
    fun callTexts() {
        assertTrue(TextRules.isCallText("Incoming video call"))
        assertTrue(TextRules.isCallText("Missed voice call"))
        assertTrue(TextRules.isCallText("3 missed calls"))
        assertFalse(TextRules.isCallText("I missed your call, sorry"))
    }

    @Test
    fun mediaPlaceholders() {
        assertTrue(TextRules.isMediaPlaceholder("🎥 Video"))
        assertTrue(TextRules.isMediaPlaceholder("GIF"))
        assertTrue(TextRules.isMediaPlaceholder("Attachment"))
        assertTrue(TextRules.isMediaPlaceholder("Alex Rivera sent a photo"))
        assertTrue(TextRules.isMediaPlaceholder("sent 3 photos"))
        assertFalse(TextRules.isMediaPlaceholder("Photo of the day is great"))
        assertFalse(TextRules.isMediaPlaceholder("👍"))
    }

    @Test
    fun senderPrefixSplit() {
        assertEquals("Alex Rivera" to "hello", TextRules.splitSenderPrefix("Alex Rivera: hello"))
        assertNull(TextRules.splitSenderPrefix("see https://example.com"))
        assertNull(TextRules.splitSenderPrefix("10: 30 people"))
        assertNull(TextRules.splitSenderPrefix("no prefix here"))
    }

    @Test
    fun deletionRulesAreExact() {
        assertTrue(DeletionMarkerRules.matchExact("This message was deleted", DeletionMarkerRules.WHATSAPP, "A") != null)
        assertNull(DeletionMarkerRules.matchExact("I deleted this message from my notes yesterday", DeletionMarkerRules.WHATSAPP, "A"))
        assertNull(DeletionMarkerRules.matchExact("This message was deleted by mistake?", DeletionMarkerRules.WHATSAPP, "A"))
        assertNull(DeletionMarkerRules.matchAction("he unsent a message", DeletionMarkerRules.INSTAGRAM_ACTIONS, "A", listOf("Alex")))
        assertEquals("Alex", DeletionMarkerRules.matchAction("Alex unsent a message", DeletionMarkerRules.INSTAGRAM_ACTIONS, "Z", listOf("Alex"))?.sender)
    }

    @Test
    fun systemRedactionDetection() {
        assertTrue(SystemRedaction.looksRedacted(snapshot(title = "Bank", text = "Sensitive notification content hidden")))
        assertTrue(SystemRedaction.looksRedacted(snapshot(title = "A", messages = listOf(msg("A", null, T0)))))
        assertTrue(SystemRedaction.looksRedacted(snapshot(title = "A", text = "x", looksSystemRedacted = true)))
        assertFalse(SystemRedaction.looksRedacted(snapshot(title = "A", messages = listOf(msg("A", null, T0, mime = "image/png")))))
        assertFalse(SystemRedaction.looksRedacted(snapshot(title = "A", text = "Content hidden in the attic, see you")))
    }
}
