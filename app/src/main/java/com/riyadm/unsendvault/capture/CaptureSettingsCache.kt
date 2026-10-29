package com.riyadm.unsendvault.capture

import com.riyadm.unsendvault.core.model.VaultSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * In-memory settings for the capture hot path: the DataStore flow is collected once per process, so the listener's
 * main-thread pre-filter and the background consumer never start a DataStore read per notification.
 *
 * [current] is null until the first value has been read — callers must then not assume defaults (the pre-filter
 * passes the item on; the consumer's [get] waits for the real value via `first()`, exactly like before). After that,
 * [current] follows every persisted change (a few ms after DataStore commits it).
 */
class CaptureSettingsCache(private val onCaptureEnabledChanged: (Boolean) -> Unit = {}) {
    @Volatile
    var current: VaultSettings? = null
        private set

    @Volatile
    private var source: Flow<VaultSettings>? = null

    /** Starts the single collector. Idempotent per instance (the first source wins). */
    fun attach(settings: Flow<VaultSettings>, scope: CoroutineScope) {
        synchronized(this) {
            if (source != null) return
            source = settings
        }
        scope.launch {
            var lastEnabled: Boolean? = null
            try {
                settings.collect { s ->
                    current = s
                    if (s.captureEnabled != lastEnabled) {
                        lastEnabled = s.captureEnabled
                        try {
                            onCaptureEnabledChanged(s.captureEnabled)
                        } catch (e: Exception) {
                            CaptureLog.d("pause sync failed: ${e.javaClass.simpleName}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave current as is; get() keeps falling back to a direct read.
                CaptureLog.d("settings cache stopped: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Latest settings; reads the source directly until the first value has arrived. */
    suspend fun get(): VaultSettings =
        current ?: (source ?: error("CaptureSettingsCache not attached")).first()
}
