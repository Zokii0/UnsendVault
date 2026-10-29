package com.riyadm.unsendvault.capture

import android.app.Application
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.capture.CapturePreFilter.Result
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.adapter.KnownApps
import com.riyadm.unsendvault.core.model.VaultSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [SnapshotFactory.preFilter] on real Notification objects (platform + AndroidX builders). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 34], application = Application::class)
class PreFilterRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val own = "com.riyadm.unsendvault"
    private val filter = CapturePreFilter(AdapterRegistry.createDefault(), own)
    private val t0 = 1_700_000_000_000L

    @Suppress("DEPRECATION")
    private fun sbn(pkg: String, n: Notification, id: Int = 1) =
        StatusBarNotification(pkg, pkg, id, null, 10001, 0, 0, n, Process.myUserHandle(), t0)

    private fun builder(channel: String = "chat") =
        NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)

    private fun messaging(): Notification {
        val alex = Person.Builder().setName("Alex Rivera").build()
        return builder()
            .setStyle(
                NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
                    .addMessage("hello", t0, alex),
            ).build()
    }

    @Test
    fun messagingStyleFromAnyAppPasses() {
        assertEquals(Result.ACCEPT, SnapshotFactory.preFilter(sbn("com.example.chat", messaging()), filter, VaultSettings()))
    }

    @Test
    fun plainNotificationFromUnknownAppIsDroppedWithoutASnapshot() {
        // A big picture notification (bitmap in extras) is rejected from header fields + key lookups only.
        val n = builder("promo").setContentTitle("Sale").setContentText("50% off")
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)))
            .build()
        assertEquals(Result.NOT_A_MESSAGE, SnapshotFactory.preFilter(sbn("com.example.shop", n), filter, VaultSettings()))
        assertEquals(Result.ACCEPT, SnapshotFactory.preFilter(sbn("com.example.shop", n), filter, VaultSettings(messagesOnly = false)))
        assertEquals(Result.ACCEPT, SnapshotFactory.preFilter(sbn("com.example.shop", n), filter, null))
    }

    @Test
    fun headerRejects() {
        val ongoing = builder().setContentTitle("Uploading").setOngoing(true).build()
        assertEquals(Result.ONGOING, SnapshotFactory.preFilter(sbn(KnownApps.WHATSAPP, ongoing), filter, null))
        val call = builder().setContentTitle("Alex Rivera").setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL).build()
        assertEquals(Result.NON_MESSAGE_CATEGORY, SnapshotFactory.preFilter(sbn(KnownApps.WHATSAPP, call), filter, null))
        assertEquals(Result.OWN_PACKAGE, SnapshotFactory.preFilter(sbn(own, messaging()), filter, null))
        val paused = VaultSettings(captureEnabled = false)
        assertEquals(Result.PAUSED, SnapshotFactory.preFilter(sbn(KnownApps.WHATSAPP, messaging()), filter, paused))
    }

    @Test
    fun groupSummaryWithoutMessagesIsDroppedButMessagingSummaryIsKept() {
        val summary = builder().setContentTitle("WhatsApp").setContentText("4 messages from 2 chats")
            .setGroup("g").setGroupSummary(true).build()
        assertEquals(Result.GROUP_SUMMARY_WITHOUT_MESSAGES, SnapshotFactory.preFilter(sbn(KnownApps.WHATSAPP, summary), filter, VaultSettings()))

        val alex = Person.Builder().setName("Alex Rivera").build()
        val messagingSummary = builder().setGroup("g").setGroupSummary(true)
            .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build()).addMessage("only a summary", t0, alex))
            .build()
        val verdict = SnapshotFactory.preFilter(sbn(KnownApps.WHATSAPP, messagingSummary), filter, VaultSettings())
        assertEquals(Result.ACCEPT, verdict)
        // And the full path still captures it.
        val s = SnapshotFactory.fromStatusBarNotification(sbn(KnownApps.WHATSAPP, messagingSummary), own)!!
        assertTrue(s.isGroupSummary)
        assertEquals("only a summary", s.messages.single().text)
    }

    @Test
    fun removalVerdictDistinguishesSettingsFromContent() {
        val plain = builder().setContentTitle("Order").setContentText("Shipped").build()
        val v = SnapshotFactory.preFilter(sbn("com.example.shop", plain), filter, VaultSettings())
        assertFalse(v.accept)
        assertFalse("content reject: forwarded only if the key was archived", v.isSettingsReject)
        val excluded = SnapshotFactory.preFilter(
            sbn(KnownApps.WHATSAPP, messaging()), filter, VaultSettings(excludedPackages = setOf(KnownApps.WHATSAPP)),
        )
        assertTrue(excluded.isSettingsReject)
    }
}
