package com.riyadm.unsendvault.security.lock

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.riyadm.unsendvault.appContainer
import com.riyadm.unsendvault.core.model.VaultSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-wide app lock. Feeds settings and process foreground/background transitions into [AppLockStateMachine]
 * and exposes the result as a [StateFlow] the activity renders from.
 */
class AppLockManager(
    settings: Flow<VaultSettings>,
    scope: CoroutineScope,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val machine = AppLockStateMachine(clock)
    private val _state = MutableStateFlow(LockState.Initializing)
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** Observer for [ProcessLifecycleOwner]: ON_STOP is dispatched ~700 ms after the last activity stops. */
    val processObserver: DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = onForeground()
        override fun onStop(owner: LifecycleOwner) = onBackground()
    }

    init {
        scope.launch {
            settings.map { it.appLockEnabled to it.lockTimeoutSeconds }
                .distinctUntilChanged()
                .collect { (enabled, timeout) -> publish { machine.onSettings(enabled, timeout) } }
        }
    }

    val isEnabled: Boolean get() = synchronized(machine) { machine.enabled }

    fun onBackground() = publish { machine.onBackground() }
    fun onForeground() = publish { machine.onForeground() }
    fun onAuthenticated() = publish { machine.onAuthenticated() }
    fun lockNow() = publish { machine.lock() }

    /** Call right before launching a user-initiated external activity (file picker, share sheet, system settings). */
    fun expectExternalActivity() = synchronized(machine) { machine.expectExternalActivity() }

    private inline fun publish(block: () -> LockState) {
        synchronized(machine) { _state.value = block() }
    }

    companion object {
        @Volatile
        private var instance: AppLockManager? = null

        /** Must be called on the main thread (registers a ProcessLifecycleOwner observer on first use). */
        fun get(context: Context): AppLockManager = instance ?: synchronized(this) {
            instance ?: run {
                val container = context.appContainer
                AppLockManager(container.settingsRepository.settings, container.appScope).also { manager ->
                    ProcessLifecycleOwner.get().lifecycle.addObserver(manager.processObserver)
                    instance = manager
                }
            }
        }
    }
}
