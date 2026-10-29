package com.riyadm.unsendvault.ui.settings

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.data.BackupManager
import com.riyadm.unsendvault.data.BackupResult
import com.riyadm.unsendvault.data.ImportMode
import com.riyadm.unsendvault.data.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class PasswordIssue { TOO_SHORT, MISMATCH }

enum class PasswordStrength { WEAK, FAIR, STRONG }

object PasswordRules {
    const val MIN_LENGTH = 8

    fun validate(password: String, confirm: String): PasswordIssue? = when {
        password.length < MIN_LENGTH -> PasswordIssue.TOO_SHORT
        password != confirm -> PasswordIssue.MISMATCH
        else -> null
    }

    /** Rough hint only (length + character variety); never blocks a password that meets the minimum. */
    fun strength(password: String): PasswordStrength {
        val classes = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        return when {
            password.length >= 16 || (password.length >= 12 && classes >= 3) -> PasswordStrength.STRONG
            password.length >= 10 && classes >= 2 -> PasswordStrength.FAIR
            else -> PasswordStrength.WEAK
        }
    }
}

enum class BackupOperation { EXPORT, IMPORT }

@Immutable
data class BackupUiState(
    val exportDialogOpen: Boolean = false,
    /** Set after the user picked a backup file; the import dialog (password + mode) is shown while non-null. */
    val importSource: Uri? = null,
    val running: BackupOperation? = null,
)

sealed interface BackupEvent {
    /** Open the system "create document" picker with this suggested file name. */
    data class PickExportDestination(val suggestedName: String) : BackupEvent
    data object PickImportSource : BackupEvent
    data class Finished(val operation: BackupOperation, val result: BackupResult) : BackupEvent
    /** The pending password was lost (e.g. process death while the picker was open). */
    data object PasswordLost : BackupEvent
}

class BackupViewModel(
    private val backupManager: BackupManager,
    private val settingsRepository: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()
    private val _events = Channel<BackupEvent>(Channel.BUFFERED)
    val events: Flow<BackupEvent> = _events.receiveAsFlow()

    /** Held only between the password dialog and the picker result; wiped right after use. */
    private var pendingExportPassword: CharArray? = null

    fun openExportDialog() = _state.update { it.copy(exportDialogOpen = true) }

    fun dismissExportDialog() = _state.update { it.copy(exportDialogOpen = false) }

    /** Returns the validation issue, or null when accepted (the picker is then requested). */
    fun confirmExportPassword(password: String, confirm: String): PasswordIssue? {
        PasswordRules.validate(password, confirm)?.let { return it }
        wipePending()
        pendingExportPassword = password.toCharArray()
        _state.update { it.copy(exportDialogOpen = false) }
        val date = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(clock()).atZone(zone))
        _events.trySend(BackupEvent.PickExportDestination("unsendvault-backup-$date.uvbackup"))
        return null
    }

    fun onExportDestination(uri: Uri?) {
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null) {
            password?.fill('\u0000')
            return
        }
        if (password == null) {
            _events.trySend(BackupEvent.PasswordLost)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(running = BackupOperation.EXPORT) }
            val result = try {
                backupManager.export(uri, password)
            } finally {
                password.fill('\u0000')
                _state.update { it.copy(running = null) }
            }
            if (result is BackupResult.Success) settingsRepository.update { it.copy(lastExportAt = clock()) }
            _events.send(BackupEvent.Finished(BackupOperation.EXPORT, result))
        }
    }

    fun startImport() {
        _events.trySend(BackupEvent.PickImportSource)
    }

    fun onImportSource(uri: Uri?) {
        if (uri != null) _state.update { it.copy(importSource = uri) }
    }

    fun dismissImportDialog() = _state.update { it.copy(importSource = null) }

    fun confirmImport(password: String, mode: ImportMode) {
        val source = _state.value.importSource ?: return
        if (password.isEmpty()) {
            _events.trySend(BackupEvent.Finished(BackupOperation.IMPORT, BackupResult.Failure(BackupResult.Reason.EMPTY_PASSWORD)))
            return
        }
        val chars = password.toCharArray()
        viewModelScope.launch {
            _state.update { it.copy(importSource = null, running = BackupOperation.IMPORT) }
            val result = try {
                backupManager.import(source, chars, mode)
            } finally {
                chars.fill('\u0000')
                _state.update { it.copy(running = null) }
            }
            _events.send(BackupEvent.Finished(BackupOperation.IMPORT, result))
        }
    }

    private fun wipePending() {
        pendingExportPassword?.fill('\u0000')
        pendingExportPassword = null
    }

    override fun onCleared() {
        wipePending()
    }
}
