package com.riyadm.unsendvault.ui

import android.content.Intent
import android.net.Uri
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import com.riyadm.unsendvault.capture.ListenerStatus
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.DeletionEvidence
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.data.BackupManager
import com.riyadm.unsendvault.data.BackupResult
import com.riyadm.unsendvault.data.ImportMode
import com.riyadm.unsendvault.data.RetentionScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Synthetic fixtures only (fictional people and text). */
object Fixtures {
    const val WHATSAPP = "com.whatsapp"
    const val TELEGRAM = "org.telegram.messenger"
    const val BASE = 1_760_000_000_000L // fixed instant, Oct 2025

    val alex = Conversation(1, WHATSAPP, "WhatsApp", "alex", "Alex Rivera", false, BASE + 3_000, 2, "See you at 7")
    val team = Conversation(2, WHATSAPP, "WhatsApp", "team", "Project Team", true, BASE + 2_000, 1, "Agenda is up")
    val club = Conversation(3, TELEGRAM, "Telegram", "club", "Book Club", true, BASE + 1_000, 1, "Chapter 7 on Sunday")
    val conversations = listOf(alex, team, club)

    fun message(
        id: Long,
        conversation: Conversation,
        text: String,
        at: Long,
        sender: String? = conversation.title,
        favorite: Boolean = false,
        deleted: Boolean = false,
        kind: ContentKind = ContentKind.TEXT,
    ) = ArchivedMessage(
        id = id,
        conversationId = conversation.id,
        packageName = conversation.packageName,
        appLabel = conversation.appLabel,
        conversationTitle = conversation.title,
        sender = sender,
        text = text,
        contentKind = kind,
        messageTimestamp = at,
        firstCapturedAt = at + 500,
        lastUpdatedAt = at + 500,
        isFavorite = favorite,
        status = if (deleted) MessageStatus.POSSIBLY_DELETED else MessageStatus.ACTIVE,
        deletionEvidence = if (deleted) DeletionEvidence("whatsapp", "This message was deleted", at + 60_000) else null,
        revisionCount = 1,
        lastRemovalReason = null,
        lastRemovedAt = null,
    )

    val messages = listOf(
        message(1, alex, "Climbing gym tonight?", BASE + 1_000),
        message(2, alex, "See you at 7", BASE + 3_000, favorite = true),
        message(3, team, "Agenda is up", BASE + 2_000, sender = "Sam Chen", deleted = true),
        message(4, club, "Chapter 7 on Sunday", BASE + 1_500, sender = "Lena Fischer"),
    )
}

class FakeListenerStatus(var granted: Boolean = true, connected: Boolean = true) : ListenerStatus {
    override val connected = MutableStateFlow(connected)
    var rebindRequests = 0
    override fun isAccessGranted(): Boolean = granted
    override fun requestRebind() {
        rebindRequests++
    }
    override fun accessSettingsIntent(): Intent = Intent("test.ACCESS_SETTINGS")
}

class FakeRetentionScheduler(private val deletes: Int = 0) : RetentionScheduler {
    val scheduled = mutableListOf<Int>()
    var runs = 0
    override fun schedule(retentionDays: Int) {
        scheduled += retentionDays
    }
    override suspend fun runNow(): Int {
        runs++
        return deletes
    }
}

class FakeBackupManager(
    var exportResult: BackupResult = BackupResult.Success(4, 3),
    var importResult: BackupResult = BackupResult.Success(4, 3),
) : BackupManager {
    val exports = mutableListOf<Pair<Uri, String>>()
    val imports = mutableListOf<Triple<Uri, String, ImportMode>>()
    var lastPasswordArray: CharArray? = null

    override suspend fun export(destination: Uri, password: CharArray): BackupResult {
        exports += destination to String(password)
        lastPasswordArray = password
        return exportResult
    }

    override suspend fun import(source: Uri, password: CharArray, mode: ImportMode): BackupResult {
        imports += Triple(source, String(password), mode)
        lastPasswordArray = password
        return importResult
    }
}

/** Installs a test Main dispatcher (unconfined by default) for viewModelScope. */
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/**
 * Turbine `test` that tolerates trailing emissions: StateFlow-backed UI state may emit intermediate combinations after
 * the one a test waits for.
 */
suspend fun <T> Flow<T>.testState(validate: suspend ReceiveTurbine<T>.() -> Unit) = test {
    validate()
    cancelAndIgnoreRemainingEvents()
}

/** Skips intermediate emissions until one matches. */
suspend fun <T> ReceiveTurbine<T>.awaitMatching(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
