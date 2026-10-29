package com.riyadm.unsendvault.widget

import android.content.Context
import android.util.Log
import com.riyadm.unsendvault.appContainer
import com.riyadm.unsendvault.capture.ListenerStatus
import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import com.riyadm.unsendvault.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot reads (`first()`) for widget rendering. Every loader is bounded by [TIMEOUT_MS] and never throws: failures
 * become [WidgetLoad.Failed]. In [WidgetPrivacyMode.LOCKED] loaders read counts only and never touch message rows.
 */
object WidgetLoaders {
    /** Messages read for Recent/Favorites; more than any widget size shows, enough for per-app grouping. */
    const val LIST_LIMIT = 20
    const val TIMEOUT_MS = 5_000L
    private const val TAG = "UvWidget"

    suspend fun count(vault: VaultRepository, settings: SettingsRepository, now: () -> Long = System::currentTimeMillis) =
        safely(now) {
            val mode = WidgetPrivacy.mode(settings.settings.first())
            WidgetPrivacy.count(vault.observeStats().first(), mode)
        }

    suspend fun recent(vault: VaultRepository, settings: SettingsRepository, now: () -> Long = System::currentTimeMillis) =
        safely(now) {
            when (val mode = WidgetPrivacy.mode(settings.settings.first())) {
                WidgetPrivacyMode.LOCKED -> WidgetPrivacy.messageList(emptyList(), mode, vault.observeStats().first().totalMessages)
                else -> WidgetPrivacy.messageList(vault.observeRecent(LIST_LIMIT).first(), mode)
            }
        }

    suspend fun favorites(vault: VaultRepository, settings: SettingsRepository, now: () -> Long = System::currentTimeMillis) =
        safely(now) {
            when (val mode = WidgetPrivacy.mode(settings.settings.first())) {
                WidgetPrivacyMode.LOCKED -> WidgetPrivacy.messageList(emptyList(), mode, vault.observeStats().first().favorites)
                else -> WidgetPrivacy.messageList(vault.observeFavorites(LIST_LIMIT).first(), mode)
            }
        }

    suspend fun appSummary(vault: VaultRepository, settings: SettingsRepository, now: () -> Long = System::currentTimeMillis) =
        safely(now) {
            when (val mode = WidgetPrivacy.mode(settings.settings.first())) {
                WidgetPrivacyMode.LOCKED -> WidgetPrivacy.appSummary(emptyList(), mode, vault.observeStats().first())
                else -> WidgetPrivacy.appSummary(vault.observeAppSummaries().first(), mode, null)
            }
        }

    suspend fun capture(settings: SettingsRepository, listener: ListenerStatus, now: () -> Long = System::currentTimeMillis) =
        safely(now) {
            WidgetPrivacy.captureState(
                accessGranted = listener.isAccessGranted(),
                captureEnabled = settings.settings.first().captureEnabled,
                connected = listener.connected.value,
            )
        }

    /** Resolves the app container and loads [kind]. Used by the Glance widgets. */
    suspend fun load(context: Context, kind: WidgetKind): WidgetLoad<Any> {
        val container = containerOrNull(context) ?: return WidgetLoad.Failed
        return when (kind) {
            WidgetKind.COUNT -> count(container.vaultRepository, container.settingsRepository)
            WidgetKind.RECENT -> recent(container.vaultRepository, container.settingsRepository)
            WidgetKind.FAVORITES -> favorites(container.vaultRepository, container.settingsRepository)
            WidgetKind.APP_SUMMARY -> appSummary(container.vaultRepository, container.settingsRepository)
            WidgetKind.CAPTURE -> capture(container.settingsRepository, container.listenerStatus)
        }
    }

    internal fun containerOrNull(context: Context): AppContainer? = try {
        context.appContainer
    } catch (e: RuntimeException) {
        Log.w(TAG, "app container unavailable: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun <T : Any> safely(now: () -> Long, block: suspend () -> T): WidgetLoad<T> = try {
        val value = withTimeoutOrNull(TIMEOUT_MS) { block() }
        if (value == null) WidgetLoad.Failed else WidgetLoad.Ready(value, now())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never log data, only the failure type.
        Log.w(TAG, "widget load failed: ${e.javaClass.simpleName}")
        WidgetLoad.Failed
    }
}
