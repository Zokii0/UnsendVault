package com.riyadm.unsendvault.update

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Result of the last "Check now" in this process, shown inline in Settings. */
sealed interface ManualCheckState {
    data object Idle : ManualCheckState
    data object Checking : ManualCheckState
    data class UpToDate(val version: String) : ManualCheckState
    data class Available(val release: ReleaseInfo) : ManualCheckState
    data class Failed(val reason: FailureReason) : ManualCheckState
}

data class UpdateUiState(
    /** Null until the update preferences have loaded. */
    val consent: UpdateConsent? = null,
    val lastCheckAt: Long? = null,
    val currentVersion: String = "",
    /** Release to offer in the update dialog, or null. */
    val prompt: ReleaseInfo? = null,
    val manual: ManualCheckState = ManualCheckState.Idle,
    /** True in debug builds: the automatic check never runs there (Check now still does). */
    val autoCheckDisabledInBuild: Boolean = false,
) {
    val showConsentDialog: Boolean get() = consent == UpdateConsent.UNASKED
    val autoCheckEnabled: Boolean get() = consent == UpdateConsent.ALLOWED
}

/**
 * Process-scoped state holder for the update check, shared by the main-screen prompts and the Settings section.
 *
 * Created lazily by the UI only (AppContainer.updateController); the notification listener, widgets and workers never
 * touch it. [onMainUiVisible] is called by the unlocked main scaffold and triggers at most one automatic check per
 * process; there is no background work, polling or scheduling of any kind.
 */
class UpdateController(
    private val checker: UpdateChecker,
    private val store: UpdateStore,
    private val scope: CoroutineScope,
    private val isDebugBuild: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Session(
        val prompt: ReleaseInfo? = null,
        val dismissedVersion: String? = null,
        val manual: ManualCheckState = ManualCheckState.Idle,
    )

    private val session = MutableStateFlow(Session())
    private val autoStarted = AtomicBoolean(false)

    val state: StateFlow<UpdateUiState> = combine(store.prefs, session) { prefs, s ->
        UpdateUiState(
            consent = prefs.consent,
            lastCheckAt = prefs.lastCheckAt,
            currentVersion = checker.currentVersion,
            prompt = s.prompt,
            manual = s.manual,
            autoCheckDisabledInBuild = isDebugBuild,
        )
    }.stateIn(scope, SharingStarted.Eagerly, UpdateUiState(currentVersion = checker.currentVersion, autoCheckDisabledInBuild = isDebugBuild))

    /** Main UI is visible and unlocked. Runs the automatic check at most once per process. */
    fun onMainUiVisible() {
        if (autoStarted.compareAndSet(false, true)) scope.launch { runAutomaticCheck() }
    }

    fun allowAutomaticChecks() = setAutomaticChecks(true)

    fun declineAutomaticChecks() = setAutomaticChecks(false)

    fun setAutomaticChecks(enabled: Boolean) {
        scope.launch {
            store.edit { it.copy(consent = if (enabled) UpdateConsent.ALLOWED else UpdateConsent.DECLINED) }
            // Still gated by the daily window, so toggling cannot cause repeated requests.
            if (enabled) runAutomaticCheck()
        }
    }

    /** Explicit "Check now": always contacts GitHub once, whatever the consent setting. */
    fun checkNow() {
        if (session.value.manual == ManualCheckState.Checking) return
        session.update { it.copy(manual = ManualCheckState.Checking) }
        scope.launch {
            val manual = when (val result = checker.checkNow(clock())) {
                is CheckResult.UpdateAvailable -> ManualCheckState.Available(result.release)
                is CheckResult.SkippedByUser -> ManualCheckState.Available(result.release)
                is CheckResult.UpToDate -> ManualCheckState.UpToDate(checker.currentVersion)
                is CheckResult.Failed -> ManualCheckState.Failed(result.reason)
                is CheckResult.NotRun -> ManualCheckState.Idle
            }
            log(manual)
            session.update { s ->
                s.copy(manual = manual, prompt = (manual as? ManualCheckState.Available)?.release ?: s.prompt)
            }
        }
    }

    /** Re-opens the update dialog for the release found by the last "Check now". */
    fun showAvailableUpdate() {
        val release = (session.value.manual as? ManualCheckState.Available)?.release ?: return
        session.update { it.copy(prompt = release) }
    }

    /** "Later": hide the dialog for the rest of this process. */
    fun dismissForNow() {
        session.update { it.copy(prompt = null, dismissedVersion = it.prompt?.version ?: it.dismissedVersion) }
    }

    /** "Skip this version": never offer this version automatically again. */
    fun skipVersion() {
        val version = session.value.prompt?.version ?: return
        session.update { it.copy(prompt = null, dismissedVersion = version) }
        scope.launch { store.edit { it.copy(skippedVersion = version) } }
    }

    private suspend fun runAutomaticCheck() {
        val result = checker.checkIfDue(clock())
        log(result)
        if (result is CheckResult.UpdateAvailable) {
            session.update { s -> if (s.dismissedVersion == result.release.version) s else s.copy(prompt = result.release) }
        }
    }

    private fun log(result: Any) {
        if (isDebugBuild) runCatching { Log.d(TAG, "update check: ${result.toString().take(160)}") }
    }

    private companion object {
        const val TAG = "UpdateCheck"
    }
}
