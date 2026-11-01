package com.riyadm.unsendvault.ui.settings

import com.riyadm.unsendvault.capture.adapter.KnownApps
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.unsendvault.apps.ProfileKind
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.StartTab
import com.riyadm.unsendvault.core.model.ThemeMode
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.RetentionScheduler
import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the exclusion picker: apps seen in the archive plus launcher apps, merged by package. */
@Immutable
data class ExclusionCandidate(
    val packageName: String,
    val label: String,
    val archivedMessages: Int,
    val excluded: Boolean,
    /** Where the app is installed; exclusion is by package, so it covers every profile. */
    val profile: ProfileKind = ProfileKind.PERSONAL,
    val alsoIn: Set<ProfileKind> = emptySet(),
)

/** One row of the "only selected apps" list. */
@Immutable
data class SelectionCandidate(
    val packageName: String,
    val label: String,
    val archivedMessages: Int,
    val selected: Boolean,
    val profile: ProfileKind = ProfileKind.PERSONAL,
    val alsoIn: Set<ProfileKind> = emptySet(),
    /** Sent a chat-style notification while blocked, so it is offered to the user. */
    val isNew: Boolean = false,
)

@Immutable
data class SettingsUiState(
    val settings: VaultSettings? = null,
    val archiveApps: List<AppSummary> = emptyList(),
    val launcherApps: List<InstalledApp>? = null,
    val busy: Boolean = false,
) {
    /** Archive apps first (they matter most), then other launcher apps; excluded apps always included. */
    val exclusionCandidates: List<ExclusionCandidate>
        get() = candidates(settings?.excludedPackages.orEmpty())

    /** The same apps for the "only selected apps" list, with the apps that were offered as "new" always included. */
    val selectionCandidates: List<SelectionCandidate>
        get() {
            val allowed = settings?.allowedPackages.orEmpty()
            val newApps = settings?.newApps.orEmpty()
            return candidates(allowed, newApps).map {
                SelectionCandidate(it.packageName, it.label, it.archivedMessages, it.excluded, it.profile, it.alsoIn, isNew = it.packageName in newApps && !it.excluded)
            }
        }

    /** Builds the app list once for both pickers; [flagged] apps get `excluded = true` (excluded or selected). */
    private fun candidates(flagged: Set<String>, extra: Set<String> = emptySet()): List<ExclusionCandidate> {
        val byPackage = LinkedHashMap<String, ExclusionCandidate>()
        val installed = launcherApps.orEmpty().associateBy { it.packageName }
        archiveApps.sortedBy { it.appLabel.lowercase() }.forEach {
            val app = installed[it.packageName]
            // Archives made before other profiles were looked up may hold the package name as the label.
            val label = if (it.appLabel == it.packageName && app != null) app.label else it.appLabel
            byPackage[it.packageName] = ExclusionCandidate(
                it.packageName, label, it.messageCount, it.packageName in flagged,
                app?.profile ?: ProfileKind.PERSONAL, app?.alsoIn.orEmpty(),
            )
        }
        launcherApps.orEmpty().forEach {
            byPackage.putIfAbsent(it.packageName, ExclusionCandidate(it.packageName, it.label, 0, it.packageName in flagged, it.profile, it.alsoIn))
        }
        (flagged + extra).filterNot { it in byPackage }.sorted().forEach {
            byPackage[it] = ExclusionCandidate(it, it, 0, it in flagged)
        }
        return byPackage.values.toList()
    }
}

sealed interface SettingsEvent {
    /** Excluding an app that already has archived messages: offer to delete them too. */
    data class OfferDeleteAppData(val packageName: String, val label: String, val messageCount: Int) : SettingsEvent
    data class AppDataDeleted(val label: String) : SettingsEvent
    data class CleanupFinished(val deleted: Int) : SettingsEvent
    data object AllDataDeleted : SettingsEvent
    data class DemoDataInserted(val messages: Int) : SettingsEvent
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val vault: VaultRepository,
    private val retentionScheduler: RetentionScheduler,
    private val installedApps: InstalledAppsSource,
    private val demoDataInserter: (suspend () -> Int)? = null,
    /** Removes posted saved-message notifications (feature off, content hidden, app lock on, all data deleted). */
    private val cancelSavedNotifications: () -> Unit = {},
) : ViewModel() {
    private val launcherApps = MutableStateFlow<List<InstalledApp>?>(null)
    private val busy = MutableStateFlow(false)
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, vault.observeAppSummaries(), launcherApps, busy,
    ) { s, apps, launcher, isBusy -> SettingsUiState(s, apps, launcher, isBusy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun update(transform: (VaultSettings) -> VaultSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    // Capture
    fun setCaptureEnabled(value: Boolean) = update { it.copy(captureEnabled = value) }
    fun setMessagesOnly(value: Boolean) = update { it.copy(messagesOnly = value) }

    fun setSelectedAppsOnly(value: Boolean) {
        viewModelScope.launch {
            // First time on: start from the chat apps found on the phone and the apps already archived, so nothing that
            // was being saved stops, and the user only adjusts the list.
            val seed = if (value && settingsRepository.current.allowedPackages.isEmpty()) initialSelection() else emptySet()
            settingsRepository.update {
                it.copy(selectedAppsOnly = value, allowedPackages = if (seed.isNotEmpty()) seed else it.allowedPackages)
            }
        }
    }

    private suspend fun initialSelection(): Set<String> {
        val installed = launcherApps.value
            ?: runCatching { installedApps.launcherApps() }.getOrDefault(emptyList()).also { launcherApps.value = it }
        val archived = vault.observeAppSummaries().first()
        val chatApps = installed.map { it.packageName }.filter { it in KnownApps.chatPackages }
        return (chatApps + archived.map { it.packageName }).toSet() - settingsRepository.current.excludedPackages
    }

    /** Ticks or unticks an app in the "only selected apps" list. Ticking also clears it from "new" and from excluded. */
    fun setAllowed(packageName: String, allowed: Boolean) = update {
        it.copy(
            allowedPackages = if (allowed) it.allowedPackages + packageName else it.allowedPackages - packageName,
            newApps = it.newApps - packageName,
            excludedPackages = if (allowed) it.excludedPackages - packageName else it.excludedPackages,
        )
    }

    fun loadLauncherApps() {
        if (launcherApps.value != null) return
        viewModelScope.launch { launcherApps.value = runCatching { installedApps.launcherApps() }.getOrDefault(emptyList()) }
    }

    fun setExcluded(candidate: ExclusionCandidate, excluded: Boolean) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(excludedPackages = if (excluded) it.excludedPackages + candidate.packageName else it.excludedPackages - candidate.packageName)
            }
            if (excluded && candidate.archivedMessages > 0) {
                _events.send(SettingsEvent.OfferDeleteAppData(candidate.packageName, candidate.label, candidate.archivedMessages))
            }
        }
    }

    fun deleteAppData(packageName: String, label: String) {
        viewModelScope.launch {
            vault.deleteApp(packageName)
            _events.send(SettingsEvent.AppDataDeleted(label))
        }
    }

    // Retention
    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(retentionDays = days) }
            retentionScheduler.schedule(days)
        }
    }

    fun runCleanupNow() {
        viewModelScope.launch {
            busy.value = true
            val deleted = try {
                retentionScheduler.runNow()
            } finally {
                busy.value = false
            }
            _events.send(SettingsEvent.CleanupFinished(deleted))
        }
    }

    // Privacy
    fun setFilterOtp(value: Boolean) = update { it.copy(filterOtp = value) }
    fun setFilterSensitive(value: Boolean) = update { it.copy(filterSensitive = value) }
    fun setSecureScreen(value: Boolean) = update { it.copy(secureScreen = value) }

    /** Widget content is always hidden while the app lock is on. */
    fun setWidgetShowContent(value: Boolean) = update { if (it.appLockEnabled) it.copy(widgetShowContent = false) else it.copy(widgetShowContent = value) }

    // Security. The UI verifies the device credential before calling setAppLockEnabled(true).
    fun setAppLockEnabled(value: Boolean) {
        update {
            if (value) it.copy(appLockEnabled = true, widgetShowContent = false, notificationShowContent = false) else it.copy(appLockEnabled = false)
        }
        if (value) cancelSavedNotifications() // posted ones may show chat names and text
    }

    fun setStartTab(tab: StartTab) = update { it.copy(startTab = tab) }

    /** The screen asks for POST_NOTIFICATIONS first on Android 13+; this only stores the choice. */
    fun setSavedMessageNotifications(value: Boolean) {
        update { it.copy(savedMessageNotifications = value) }
        if (!value) cancelSavedNotifications()
    }

    /** Forced off while the app lock is enabled (same rule as widget content). */
    fun setNotificationShowContent(value: Boolean) {
        update { if (it.appLockEnabled) it.copy(notificationShowContent = false) else it.copy(notificationShowContent = value) }
        if (!value) cancelSavedNotifications()
    }

    fun setLockTimeout(seconds: Int) = update { it.copy(lockTimeoutSeconds = seconds) }

    // Appearance
    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setAmoledBlack(value: Boolean) = update { it.copy(amoledBlack = value) }
    fun setDynamicColor(value: Boolean) = update { it.copy(dynamicColor = value) }

    // Backup reminder
    fun setBackupReminderDays(days: Int) = update { it.copy(backupReminderDays = days) }

    // Data
    fun deleteAll() {
        viewModelScope.launch {
            busy.value = true
            try {
                vault.deleteAll()
            } finally {
                busy.value = false
            }
            cancelSavedNotifications()
            _events.send(SettingsEvent.AllDataDeleted)
        }
    }

    val canInsertDemoData: Boolean get() = demoDataInserter != null

    fun insertDemoData() {
        val inserter = demoDataInserter ?: return
        viewModelScope.launch {
            busy.value = true
            val count = try {
                inserter()
            } finally {
                busy.value = false
            }
            _events.send(SettingsEvent.DemoDataInserted(count))
        }
    }

    companion object {
        val RETENTION_OPTIONS = listOf(0, 7, 30, 90, 365)
        val REMINDER_OPTIONS = listOf(0, 7, 14, 30, 60)
    }
}
