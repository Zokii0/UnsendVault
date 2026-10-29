package com.riyadm.unsendvault.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.unsendvault.capture.ListenerStatus
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.DeletionEvidence
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.data.BackupManager
import com.riyadm.unsendvault.data.BackupResult
import com.riyadm.unsendvault.data.ImportMode
import com.riyadm.unsendvault.data.RetentionScheduler
import com.riyadm.unsendvault.security.lock.AuthResult
import com.riyadm.unsendvault.security.lock.LockAuthenticator
import com.riyadm.unsendvault.security.lock.LockAvailability
import kotlinx.coroutines.flow.MutableStateFlow

fun str(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

fun plural(id: Int, count: Int): String =
    InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(id, count, count)

/** Synthetic fixtures only. */
object UiFixtures {
    const val BASE = 1_760_000_000_000L
    val alex = Conversation(1, "com.whatsapp", "WhatsApp", "alex", "Alex Rivera", false, BASE + 3_000, 2, "See you at 7")
    val club = Conversation(3, "org.telegram.messenger", "Telegram", "club", "Book Club", true, BASE + 1_000, 1, "Chapter 7 on Sunday")

    fun message(
        id: Long,
        c: Conversation,
        text: String,
        at: Long,
        sender: String? = c.title,
        favorite: Boolean = false,
        deleted: Boolean = false,
        kind: ContentKind = ContentKind.TEXT,
        removal: RemovalReason? = null,
        revisions: Int = 1,
    ) = ArchivedMessage(
        id, c.id, c.packageName, c.appLabel, c.title, sender, text, kind, at, at + 500, at + 900, favorite,
        if (deleted) MessageStatus.POSSIBLY_DELETED else MessageStatus.ACTIVE,
        if (deleted) DeletionEvidence("whatsapp", "This message was deleted", at + 60_000) else null,
        revisions, removal, removal?.let { at + 120_000 },
    )

    val messages = listOf(
        message(1, alex, "Climbing gym tonight?", BASE + 1_000),
        message(2, alex, "See you at 7", BASE + 3_000, favorite = true),
        message(4, club, "Chapter 7 on Sunday", BASE + 1_500, sender = "Lena Fischer"),
    )
}

class FakeListenerStatus(var granted: Boolean) : ListenerStatus {
    override val connected = MutableStateFlow(granted)
    override fun isAccessGranted() = granted
    override fun requestRebind() = Unit
    override fun accessSettingsIntent() = Intent("test.ACCESS_SETTINGS")
}

class FakeRetentionScheduler : RetentionScheduler {
    override fun schedule(retentionDays: Int) = Unit
    override suspend fun runNow() = 0
}

class FakeBackupManager : BackupManager {
    override suspend fun export(destination: Uri, password: CharArray) = BackupResult.Success(0, 0)
    override suspend fun import(source: Uri, password: CharArray, mode: ImportMode) = BackupResult.Success(0, 0)
}

/** Records prompts without answering, so tests decide the outcome. */
class FakeLockAuthenticator(var availability: LockAvailability = LockAvailability.AVAILABLE) : LockAuthenticator {
    val pending = mutableListOf<(AuthResult) -> Unit>()
    override fun availability() = availability
    override fun authenticate(onResult: (AuthResult) -> Unit) {
        pending += onResult
    }
}
