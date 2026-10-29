package com.riyadm.unsendvault.capture.adapter

import com.riyadm.unsendvault.capture.Fixtures.T0
import com.riyadm.unsendvault.capture.Fixtures.msg
import com.riyadm.unsendvault.capture.Fixtures.snapshot
import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.parser.CapturePlaceholders
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import com.riyadm.unsendvault.capture.parser.RejectReason
import com.riyadm.unsendvault.core.model.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private val registry = AdapterRegistry.createDefault(CapturePlaceholders())

private fun run(s: NotificationSnapshot): AdapterRegistry.Result = registry.parse(s)

private fun parsedOf(s: NotificationSnapshot): ParseOutcome.Parsed {
    val outcome = run(s).outcome
    assertTrue("expected Parsed but was $outcome", outcome is ParseOutcome.Parsed)
    return outcome as ParseOutcome.Parsed
}

class WhatsAppAdapterTest {
    private val wa = KnownApps.WHATSAPP

    @Test
    fun oneToOneMessagingStyle() {
        val result = run(snapshot(packageName = wa, title = "Alex Rivera", messages = listOf(msg("Alex Rivera", "Hi there", T0))))
        assertEquals("whatsapp", result.adapterId)
        val m = (result.outcome as ParseOutcome.Parsed).messages.single()
        assertEquals("Hi there", m.text)
        assertFalse(m.isGroup)
        assertEquals("name:alex rivera", m.conversationKey)
    }

    @Test
    fun groupMessagingStyleWithCounterInConversationTitle() {
        val out = parsedOf(
            snapshot(
                packageName = wa,
                conversationTitle = "Project Team (3 messages)",
                isGroupConversation = true,
                messages = listOf(msg("Alex Rivera", "a", T0), msg("Sam Lee", "b", T0 + 1), msg(null, "c", T0 + 2)),
            ),
        ).messages
        assertTrue(out.all { it.isGroup && it.conversationTitle == "Project Team" && it.conversationKey == "name:project team" })
        assertEquals(listOf("Alex Rivera", "Sam Lee", "You"), out.map { it.sender })
    }

    @Test
    fun legacyGroupTitleWithCount() {
        val m = parsedOf(snapshot(packageName = wa, title = "Project Team (3 messages): Alex Rivera", text = "Running late")).messages.single()
        assertEquals("Project Team", m.conversationTitle)
        assertEquals("Alex Rivera", m.sender)
        assertTrue(m.isGroup)
        assertEquals("name:project team", m.conversationKey)
    }

    @Test
    fun legacySenderAtGroupTitle() {
        val m = parsedOf(snapshot(packageName = wa, title = "Alex Rivera @ Project Team", text = "Agenda attached")).messages.single()
        assertEquals("Project Team", m.conversationTitle)
        assertEquals("Alex Rivera", m.sender)
        assertTrue(m.isGroup)
    }

    @Test
    fun summaryAndChatterRejected() {
        assertTrue(run(snapshot(packageName = wa, title = "WhatsApp", text = "5 messages from 3 chats")).outcome is ParseOutcome.Rejected)
        assertTrue(run(snapshot(packageName = wa, title = "WhatsApp", text = "WhatsApp Web is currently active")).outcome is ParseOutcome.Rejected)
        assertTrue(run(snapshot(packageName = wa, title = "WhatsApp", text = "Checking for new messages")).outcome is ParseOutcome.Rejected)
        assertEquals(
            ParseOutcome.Rejected(RejectReason.GROUP_SUMMARY),
            run(snapshot(packageName = wa, title = "WhatsApp", textLines = listOf("Alex Rivera: hi"), isGroupSummary = true)).outcome,
        )
    }

    @Test
    fun callsRejected() {
        val outcome = run(snapshot(packageName = wa, title = "Alex Rivera", text = "Incoming voice call", category = "call")).outcome
        assertEquals(ParseOutcome.Rejected(RejectReason.CALL), outcome)
    }

    @Test
    fun deletionMarkerAtSameTimestamp() {
        val p = parsedOf(
            snapshot(
                packageName = wa,
                conversationTitle = "Project Team",
                isGroupConversation = true,
                messages = listOf(msg("Alex Rivera", "Earlier message", T0), msg("Sam Lee", "This message was deleted", T0 + 7)),
            ),
        )
        assertEquals(listOf("Earlier message"), p.messages.map { it.text })
        val marker = p.deletionMarkers.single()
        assertEquals(T0 + 7, marker.markerTimestamp)
        assertEquals("Sam Lee", marker.sender)
        assertEquals("whatsapp", marker.adapterId)
        assertEquals("name:project team", marker.conversationKey)
        assertEquals("This message was deleted", marker.markerText)
    }

    @Test
    fun deletionMarkerWithLeadingSymbolAndSelfVariant() {
        val p = parsedOf(
            snapshot(
                packageName = wa,
                title = "Alex Rivera",
                messages = listOf(msg("Alex Rivera", "🚫 This message was deleted.", T0), msg(null, "You deleted this message", T0 + 1)),
            ),
        )
        assertTrue(p.messages.isEmpty())
        assertEquals(listOf("Alex Rivera", "You"), p.deletionMarkers.map { it.sender })
    }

    @Test
    fun deletionMarkerTranslations() {
        val phrases = listOf(
            "Se eliminó este mensaje",
            "Esta mensagem foi apagada",
            "Ce message a été supprimé",
            "Diese Nachricht wurde gelöscht",
            "Pesan ini telah dihapus",
            "यह संदेश हटाया गया",
        )
        for ((i, phrase) in phrases.withIndex()) {
            val p = parsedOf(snapshot(packageName = wa, title = "Alex Rivera", messages = listOf(msg("Alex Rivera", phrase, T0 + i))))
            assertEquals(phrase, 1, p.deletionMarkers.size)
            assertTrue(phrase, p.messages.isEmpty())
        }
    }

    @Test
    fun sentencesContainingThePhraseAreNotMarkers() {
        val texts = listOf(
            "I deleted this message from my notes yesterday",
            "Why does it say this message was deleted?",
            "this message was deleted lol jk",
        )
        val p = parsedOf(
            snapshot(packageName = wa, title = "Alex Rivera", messages = texts.mapIndexed { i, t -> msg("Alex Rivera", t, T0 + i) }),
        )
        assertTrue(p.deletionMarkers.isEmpty())
        assertEquals(texts, p.messages.map { it.text })
    }

    @Test
    fun businessPackageUsesSameAdapter() {
        assertEquals("whatsapp", registry.adapterFor(KnownApps.WHATSAPP_BUSINESS).id)
    }
}

class InstagramAdapterTest {
    private val ig = KnownApps.INSTAGRAM

    @Test
    fun directMessage() {
        val m = parsedOf(snapshot(packageName = ig, title = "alex.rivera", messages = listOf(msg("alex.rivera", "yo", T0)))).messages.single()
        assertEquals("alex.rivera", m.sender)
        assertEquals("yo", m.text)
    }

    @Test
    fun unsentMarkerWithKnownName() {
        val p = parsedOf(
            snapshot(packageName = ig, title = "alex.rivera", messages = listOf(msg("alex.rivera", "alex.rivera unsent a message", T0))),
        )
        assertTrue(p.messages.isEmpty())
        assertEquals("alex.rivera", p.deletionMarkers.single().sender)
        assertEquals("instagram", p.deletionMarkers.single().adapterId)
    }

    @Test
    fun bareUnsentMarkerAndFlatLayout() {
        val p = parsedOf(snapshot(packageName = ig, title = "alex.rivera", text = "unsent a message", category = "msg"))
        assertEquals("alex.rivera", p.deletionMarkers.single().sender)
        assertTrue(p.messages.isEmpty())
    }

    @Test
    fun unknownNameBeforeUnsentIsNotAMarker() {
        val p = parsedOf(
            snapshot(packageName = ig, title = "alex.rivera", messages = listOf(msg("alex.rivera", "lol he unsent a message", T0))),
        )
        assertTrue(p.deletionMarkers.isEmpty())
        assertEquals("lol he unsent a message", p.messages.single().text)
    }

    @Test
    fun socialNotificationsAreNotMessages() {
        val adapter = registry.adapterFor(ig)
        assertFalse(adapter.isMessageNotification(snapshot(packageName = ig, title = "Instagram", text = "alex.rivera liked your photo")))
        assertTrue(adapter.isMessageNotification(snapshot(packageName = ig, title = "alex.rivera", text = "hi", channelId = "ig_direct")))
        assertTrue(adapter.isMessageNotification(snapshot(packageName = ig, messages = listOf(msg("a", "b", T0)))))
    }
}

class MessengerAndFacebookAdapterTest {
    @Test
    fun messengerRemovedAndUnsentMarkers() {
        val p = parsedOf(
            snapshot(
                packageName = KnownApps.MESSENGER,
                conversationTitle = "Project Team",
                isGroupConversation = true,
                messages = listOf(
                    msg("Alex Rivera", "Alex Rivera removed a message", T0),
                    msg("Sam Lee", "unsent a message", T0 + 1),
                    msg(null, "You unsent a message", T0 + 2),
                    msg("Sam Lee", "real text", T0 + 3),
                ),
            ),
        )
        assertEquals(listOf("Alex Rivera", "Sam Lee", "You"), p.deletionMarkers.map { it.sender })
        assertEquals(listOf("real text"), p.messages.map { it.text })
        assertTrue(p.deletionMarkers.all { it.adapterId == "messenger" })
    }

    @Test
    fun messengerIsDedicated() {
        assertTrue(registry.adapterFor(KnownApps.MESSENGER).isMessageNotification(snapshot(packageName = KnownApps.MESSENGER, title = "Alex Rivera", text = "hi")))
        assertEquals("messenger", registry.adapterFor(KnownApps.MESSENGER_LITE).id)
    }

    @Test
    fun facebookRejectsSocialEvenWhenParsedDirectly() {
        val outcome = run(snapshot(packageName = KnownApps.FACEBOOK, title = "Facebook", text = "Alex Rivera commented on your post"))
        assertEquals(ParseOutcome.Rejected(RejectReason.NOT_A_MESSAGE), outcome.outcome)
        val ok = run(snapshot(packageName = KnownApps.FACEBOOK, title = "Alex Rivera", text = "hey", category = "msg"))
        assertEquals("hey", (ok.outcome as ParseOutcome.Parsed).messages.single().text)
    }
}

class TelegramSignalAdapterTest {
    @Test
    fun telegramGroupAndMarker() {
        val p = parsedOf(
            snapshot(
                packageName = KnownApps.TELEGRAM,
                conversationTitle = "Project Team",
                isGroupConversation = true,
                messages = listOf(msg("Alex Rivera", "Deploy done", T0), msg("Sam Lee", "This message was deleted", T0 + 1)),
            ),
        )
        assertEquals(listOf("Deploy done"), p.messages.map { it.text })
        assertEquals("telegram", p.deletionMarkers.single().adapterId)
    }

    @Test
    fun telegramLegacySenderAtGroup() {
        val m = parsedOf(snapshot(packageName = KnownApps.TELEGRAM_X, title = "Alex Rivera @ Project Team", text = "hi")).messages.single()
        assertEquals("Project Team", m.conversationTitle)
        assertEquals("Alex Rivera", m.sender)
    }

    @Test
    fun telegramWebBuildRegistered() {
        assertEquals("telegram", registry.adapterFor(KnownApps.TELEGRAM_WEB).id)
    }

    @Test
    fun signalMessagesAndMarker() {
        val p = parsedOf(
            snapshot(
                packageName = KnownApps.SIGNAL,
                title = "Alex Rivera",
                messages = listOf(msg("Alex Rivera", "Hello", T0), msg("Alex Rivera", "This message was deleted.", T0 + 1)),
            ),
        )
        assertEquals(listOf("Hello"), p.messages.map { it.text })
        assertEquals(T0 + 1, p.deletionMarkers.single().markerTimestamp)
    }

    @Test
    fun signalPrivacyPlaceholderIsNoise() {
        assertTrue(run(snapshot(packageName = KnownApps.SIGNAL, title = "Signal", text = "New message")).outcome is ParseOutcome.Rejected)
    }
}

class SmsAndOtherAdaptersTest {
    @Test
    fun googleMessagesFlatSms() {
        val result = run(snapshot(packageName = KnownApps.GOOGLE_MESSAGES, title = "Alex Rivera", text = "On my way"))
        assertEquals("google_messages", result.adapterId)
        assertEquals("On my way", (result.outcome as ParseOutcome.Parsed).messages.single().text)
    }

    @Test
    fun samsungMessagesSummaryRejected() {
        val result = run(snapshot(packageName = KnownApps.SAMSUNG_MESSAGES, title = "Messages", text = "3 new messages"))
        assertTrue(result.outcome is ParseOutcome.Rejected)
    }

    @Test
    fun smsAppChatterRejected() {
        assertTrue(run(snapshot(packageName = KnownApps.GOOGLE_MESSAGES, title = "Alex Rivera", text = "Message not sent. Tap to retry.")).outcome is ParseOutcome.Rejected)
    }

    @Test
    fun weChatCounterPrefixRemovedAndSenderSplit() {
        val m = parsedOf(snapshot(packageName = KnownApps.WECHAT, title = "Project Team", text = "[2] Alex Rivera: lunch?")).messages.single()
        assertEquals("lunch?", m.text)
        assertEquals("Alex Rivera", m.sender)
        assertEquals("Project Team", m.conversationTitle)
    }

    @Test
    fun snapchatContentlessChatIsNoise() {
        assertTrue(run(snapshot(packageName = KnownApps.SNAPCHAT, title = "Alex Rivera", text = "sent you a Chat")).outcome is ParseOutcome.Rejected)
        val snap = parsedOf(snapshot(packageName = KnownApps.SNAPCHAT, title = "Alex Rivera", text = "sent you a Snap")).messages.single()
        assertEquals(ContentKind.MEDIA_PLACEHOLDER, snap.contentKind)
    }

    @Test
    fun everyListedPackageHasAnAdapterAndLabel() {
        val expected = mapOf(
            KnownApps.DISCORD to "discord", KnownApps.VIBER to "viber", KnownApps.LINE to "line",
            KnownApps.SNAPCHAT to "snapchat", KnownApps.SLACK to "slack", KnownApps.TEAMS to "teams",
            KnownApps.SKYPE to "skype", KnownApps.WECHAT to "wechat", KnownApps.KAKAOTALK to "kakaotalk",
            KnownApps.THREEMA to "threema", KnownApps.SESSION to "session", KnownApps.ELEMENT to "element",
            KnownApps.WIRE to "wire", KnownApps.IMO to "imo", KnownApps.BOTIM to "botim", KnownApps.TRUECALLER to "truecaller",
            KnownApps.INSTAGRAM_LITE to "instagram", KnownApps.SAMSUNG_MESSAGES to "samsung_messages",
        )
        for ((pkg, id) in expected) {
            assertEquals(pkg, id, registry.adapterFor(pkg).id)
            assertTrue(pkg, KnownApps.labelFor(pkg) != null)
        }
        assertSame(registry.generic, registry.adapterFor("com.example.unknown"))
    }

    @Test
    fun mixedAppsOnlyClaimMessagingNotifications() {
        val slack = registry.adapterFor(KnownApps.SLACK)
        assertFalse(slack.isMessageNotification(snapshot(packageName = KnownApps.SLACK, title = "Reminder", text = "Stand-up")))
        assertTrue(slack.isMessageNotification(snapshot(packageName = KnownApps.SLACK, messages = listOf(msg("Alex Rivera", "ping", T0)))))
        val viber = registry.adapterFor(KnownApps.VIBER)
        assertTrue(viber.isMessageNotification(snapshot(packageName = KnownApps.VIBER, title = "Alex Rivera", text = "hi")))
    }
}

class AdapterRegistryFallbackTest {
    private val generic = GenericAdapter(CapturePlaceholders())

    private class FakeAdapter(private val result: () -> ParseOutcome?) : AppAdapter {
        override val id = "fake"
        override val packages = setOf("com.example.fake")
        override fun isMessageNotification(s: NotificationSnapshot) = true
        override fun parse(s: NotificationSnapshot): ParseOutcome? = result()
    }

    private val s = snapshot(packageName = "com.example.fake", title = "Alex Rivera", text = "hello")

    @Test
    fun throwingAdapterFallsBackToGeneric() {
        val r = AdapterRegistry(listOf(FakeAdapter { error("boom") }), generic).parse(s)
        assertEquals("generic", r.adapterId)
        assertEquals("hello", (r.outcome as ParseOutcome.Parsed).messages.single().text)
    }

    @Test
    fun emptyOrNullResultFallsBackToGeneric() {
        assertEquals("generic", AdapterRegistry(listOf(FakeAdapter { ParseOutcome.Parsed.EMPTY }), generic).parse(s).adapterId)
        assertEquals("generic", AdapterRegistry(listOf(FakeAdapter { null }), generic).parse(s).adapterId)
    }

    @Test
    fun rejectionIsFinal() {
        val r = AdapterRegistry(listOf(FakeAdapter { ParseOutcome.Rejected(RejectReason.NOISE) }), generic).parse(s)
        assertEquals("fake", r.adapterId)
        assertEquals(ParseOutcome.Rejected(RejectReason.NOISE), r.outcome)
    }

    @Test
    fun stackOverflowInAdapterIsContained() {
        val r = AdapterRegistry(listOf(FakeAdapter { throw StackOverflowError() }), generic).parse(s)
        assertEquals("generic", r.adapterId)
    }

    @Test
    fun labelResolverFallsBackAndCaches() {
        var calls = 0
        val resolver = AppLabelResolver { pkg -> calls++; if (pkg == "com.example.found") "Found App" else throw IllegalStateException() }
        assertEquals("Found App", resolver.labelFor("com.example.found"))
        assertEquals("Found App", resolver.labelFor("com.example.found"))
        assertEquals("WhatsApp", resolver.labelFor(KnownApps.WHATSAPP))
        assertEquals("com.example.other", resolver.labelFor("com.example.other"))
        assertEquals(3, calls)
        assertNull(KnownApps.labelFor("com.example.other"))
    }
}
