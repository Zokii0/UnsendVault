package com.riyadm.unsendvault.capture.parser

import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.DeletionMarker

/**
 * Strings the capture layer writes into stored message text. Production values are loaded from
 * `res/values/strings_capture.xml` (see [com.riyadm.unsendvault.capture.CaptureModule]); the defaults keep parsers
 * usable in plain JVM tests.
 */
data class CapturePlaceholders(
    /** Sender label for the user's own messages (MessagingStyle messages with a null sender or the self name). */
    val selfSender: String = "You",
    val systemRedacted: String = "Content hidden by Android",
    val otpHidden: String = "One-time code hidden by UnsendVault",
    val sensitiveHidden: String = "Sensitive content hidden by UnsendVault",
    val mediaPhoto: String = "📷 Photo",
    val mediaVideo: String = "🎥 Video",
    val mediaAudio: String = "🎤 Audio",
    val mediaAttachment: String = "📎 Attachment",
)

/** Result of running a parser/adapter over one snapshot. */
sealed interface ParseOutcome {
    data class Parsed(
        val messages: List<CapturedMessage>,
        val deletionMarkers: List<DeletionMarker> = emptyList(),
    ) : ParseOutcome {
        val isEmpty: Boolean get() = messages.isEmpty() && deletionMarkers.isEmpty()

        companion object {
            val EMPTY = Parsed(emptyList())
        }
    }

    /** The parser recognised the notification and decided it must not be archived. Never triggers a fallback. */
    data class Rejected(val reason: RejectReason) : ParseOutcome
}

enum class RejectReason { ONGOING, GROUP_SUMMARY, CALL, NOT_A_MESSAGE, NOISE, EMPTY }

/** What a notification title says about the conversation. */
data class TitleInfo(
    /** Display title of the conversation (group name or contact), already cleaned. */
    val conversationTitle: String?,
    /** Sender implied by the title (1:1 chats, or "Sender @ Group" layouts). */
    val sender: String?,
    val isGroup: Boolean,
)
