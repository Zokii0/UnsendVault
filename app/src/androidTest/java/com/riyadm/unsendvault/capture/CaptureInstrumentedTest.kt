package com.riyadm.unsendvault.capture

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device checks for the capture layer that need the real framework (OEM settings activities, real Notification). */
@RunWith(AndroidJUnit4::class)
class CaptureInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun atLeastOneAccessSettingsScreenResolves() {
        val pm = context.packageManager
        val resolved = NotificationAccess.settingsIntents(context).filter { it.resolveActivity(pm) != null }
        assertTrue("no Notification Access settings screen resolves on this device", resolved.isNotEmpty())
        assertNotNull(AndroidListenerStatus(context).accessSettingsIntent().resolveActivity(pm))
    }

    @Test
    fun realMessagingStyleNotificationParses() {
        val alex = Person.Builder().setName("Alex Rivera").build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
            .setConversationTitle("Project Team")
            .setGroupConversation(true)
            .addMessage("Standup in 5", 1_700_000_000_000L, alex)
        val n = NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .build()
        val snapshot = SnapshotFactory.fromNotification("com.example.chat", "k", 1, null, 1_700_000_001_000L, n)!!
        val outcome = AdapterRegistry.createDefault().parse(snapshot).outcome as ParseOutcome.Parsed
        val message = outcome.messages.single()
        assertEquals("Standup in 5", message.text)
        assertEquals("Alex Rivera", message.sender)
        assertEquals("Project Team", message.conversationTitle)
        assertTrue(message.isGroup)
    }
}
