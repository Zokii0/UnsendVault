package com.riyadm.unsendvault.widget

import com.riyadm.unsendvault.capture.ListenerStatus
import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * What each widget kind would display, as a flow. The sync engine re-renders a kind only when its fingerprint changes,
 * so e.g. a settings change that doesn't affect privacy, or a favorite toggle in metadata mode, costs nothing.
 *
 * Repositories are passed as providers so a kind that doesn't need the database (Capture control) never opens it.
 * In [WidgetPrivacyMode.LOCKED] only aggregate counts are observed.
 */
object WidgetFingerprints {
    fun of(
        kind: WidgetKind,
        vault: () -> VaultRepository,
        settings: () -> SettingsRepository,
        listener: () -> ListenerStatus,
    ): Flow<Any?> {
        val mode: Flow<WidgetPrivacyMode> = settings().settings.map(WidgetPrivacy::mode).distinctUntilChanged()
        return when (kind) {
            WidgetKind.COUNT -> combine(mode, vault().observeStats()) { m, stats -> WidgetPrivacy.count(stats, m) }

            WidgetKind.RECENT -> mode.flatMapLatest { m ->
                if (m == WidgetPrivacyMode.LOCKED) {
                    vault().observeStats().map { WidgetPrivacy.messageList(emptyList(), m, it.totalMessages) }
                } else {
                    vault().observeRecent(WidgetLoaders.LIST_LIMIT).map { WidgetPrivacy.messageList(it, m) }
                }
            }

            WidgetKind.FAVORITES -> mode.flatMapLatest { m ->
                if (m == WidgetPrivacyMode.LOCKED) {
                    vault().observeStats().map { WidgetPrivacy.messageList(emptyList(), m, it.favorites) }
                } else {
                    vault().observeFavorites(WidgetLoaders.LIST_LIMIT).map { WidgetPrivacy.messageList(it, m) }
                }
            }

            WidgetKind.APP_SUMMARY -> mode.flatMapLatest { m ->
                if (m == WidgetPrivacyMode.LOCKED) {
                    vault().observeStats().map { WidgetPrivacy.appSummary(emptyList(), m, it) }
                } else {
                    vault().observeAppSummaries().map { WidgetPrivacy.appSummary(it, m, null) }
                }
            }

            WidgetKind.CAPTURE -> {
                val status = listener()
                combine(settings().settings.map { it.captureEnabled }.distinctUntilChanged(), status.connected) { enabled, connected ->
                    WidgetPrivacy.captureState(status.isAccessGranted(), enabled, connected)
                }
            }
        }
    }
}
