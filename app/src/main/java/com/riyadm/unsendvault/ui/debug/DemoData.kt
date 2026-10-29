package com.riyadm.unsendvault.ui.debug

import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.DeletionMarker
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.flow.first

/**
 * Debug-only generator of SYNTHETIC archive data (fictional people, fictional text) so every UI state can be
 * demonstrated and screenshotted: groups, media placeholders, Android-redacted and filter-hidden content, an updated
 * message with history, an app-provided deletion marker, a neutral notification removal and favorites.
 *
 * Goes through [VaultRepository.ingest] / [VaultRepository.recordRemoval] exactly like real captures.
 */
object DemoData {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private data class App(val pkg: String, val label: String, val adapter: String)

    private val whatsApp = App("com.whatsapp", "WhatsApp", "whatsapp")
    private val instagram = App("com.instagram.android", "Instagram", "instagram")
    private val messenger = App("com.facebook.orca", "Messenger", "messenger")
    private val telegram = App("org.telegram.messenger", "Telegram", "telegram")

    /** Inserts the demo set; returns the number of newly inserted messages. */
    suspend fun insert(vault: VaultRepository, now: Long = System.currentTimeMillis()): Int {
        var inserted = 0
        var seq = 0
        suspend fun post(
            app: App,
            key: String,
            title: String,
            group: Boolean,
            messages: List<Triple<String?, String, Long>>,
            kind: ContentKind = ContentKind.TEXT,
            markers: List<DeletionMarker> = emptyList(),
            capturedAt: Long = messages.maxOfOrNull { it.third } ?: now,
        ): String {
            val notificationKey = "demo|${app.pkg}|$key|${seq++}"
            val parsed = ParsedNotification(
                packageName = app.pkg,
                appLabel = app.label,
                notificationKey = notificationKey,
                postTime = capturedAt,
                adapterId = app.adapter,
                messages = messages.map { (sender, text, ts) ->
                    CapturedMessage(app.pkg, key, title, group, sender, text, kind, ts, hasPreciseTimestamp = true)
                },
                deletionMarkers = markers,
            )
            inserted += vault.ingest(parsed, capturedAt).inserted
            return notificationKey
        }

        // WhatsApp: 1:1 chat with a message the app later replaced by "This message was deleted".
        val alexDeletedAt = now - 2 * HOUR - 14 * MINUTE
        post(
            whatsApp, "alex.rivera", "Alex Rivera", false,
            listOf(
                Triple("Alex Rivera", "Are we still on for the climbing gym tonight?", now - 3 * HOUR),
                Triple("Alex Rivera", "I'll bring the spare chalk bag 🧗", now - 3 * HOUR + 2 * MINUTE),
            ),
        )
        post(whatsApp, "alex.rivera", "Alex Rivera", false, listOf(Triple("Alex Rivera", "Ignore the last voice note, wrong chat 🙈", alexDeletedAt)))
        post(
            whatsApp, "alex.rivera", "Alex Rivera", false, emptyList(),
            markers = listOf(
                DeletionMarker(whatsApp.pkg, "alex.rivera", "Alex Rivera", "This message was deleted", whatsApp.adapter, alexDeletedAt),
            ),
            capturedAt = alexDeletedAt + 3 * MINUTE,
        )

        // WhatsApp group with a media placeholder and an updated (edited) message.
        val agendaAt = now - 26 * HOUR
        post(
            whatsApp, "project.team", "Project Team", true,
            listOf(
                Triple("Sam Chen", "Draft agenda for Thursday is in the shared folder", agendaAt),
                Triple("Priya Nair", "Thanks! I'll add the budget item", agendaAt + 4 * MINUTE),
            ),
        )
        post(whatsApp, "project.team", "Project Team", true, listOf(Triple("Sam Chen", "📷 Photo", agendaAt + 9 * MINUTE)), kind = ContentKind.MEDIA_PLACEHOLDER)
        // Same message (same sender + timestamp) re-posted with new text: recorded as a revision.
        post(whatsApp, "project.team", "Project Team", true, listOf(Triple("Sam Chen", "Draft agenda for Thursday 10:00 is in the shared folder", agendaAt)), capturedAt = agendaAt + 12 * MINUTE)

        // Instagram DMs.
        val jordanKey = post(
            instagram, "jordan.blake", "Jordan Blake", false,
            listOf(
                Triple("Jordan Blake", "That sunset photo from the trail is unreal", now - 50 * MINUTE),
                Triple("Jordan Blake", "Sent an attachment", now - 48 * MINUTE),
            ),
        )
        vault.recordRemoval(RemovalEvent(instagram.pkg, jordanKey, RemovalReason.APP_CANCELLED, now - 40 * MINUTE))

        // Messenger: content withheld by Android, and content hidden by the sensitive-content filter.
        post(messenger, "maya.okafor", "Maya Okafor", false, listOf(Triple("Maya Okafor", "Sensitive notification content hidden", now - 5 * HOUR)), kind = ContentKind.SYSTEM_REDACTED)
        post(messenger, "maya.okafor", "Maya Okafor", false, listOf(Triple("Maya Okafor", "Verification code hidden by UnsendVault", now - 4 * HOUR)), kind = ContentKind.FILTERED_SENSITIVE)
        post(messenger, "maya.okafor", "Maya Okafor", false, listOf(Triple("Maya Okafor", "Lunch on Friday? I found a new ramen place near the office", now - 4 * HOUR + 20 * MINUTE)))

        // Telegram: book club group spread over several days.
        post(
            telegram, "book.club", "Book Club", true,
            listOf(
                Triple("Lena Fischer", "Chapter 7 discussion moved to Sunday", now - 6 * DAY),
                Triple("Omar Haddad", "Works for me. Bringing snacks!", now - 6 * DAY + 30 * MINUTE),
                Triple("Lena Fischer", "Next pick: something under 300 pages please 😅", now - 2 * DAY),
            ),
        )

        // Favorite a couple of messages so the Favorites screen has content.
        listOf("climbing gym", "ramen place").forEach { text ->
            vault.observeMessages(MessageQuery(text = text, limit = 1)).first().firstOrNull()?.let { vault.setFavorite(it.id, true) }
        }
        return inserted
    }
}
