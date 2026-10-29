package com.riyadm.unsendvault.widget

import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.core.model.VaultStats
import com.riyadm.unsendvault.data.VaultRepository
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.widget.WidgetTestData.SECRET_TEXT
import com.riyadm.unsendvault.widget.WidgetTestData.messages
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLoadersTest {

    /** Counts message-row reads so tests can prove locked mode never loads them. */
    private class SpyVault(private val delegate: VaultRepository = InMemoryVaultRepository(initialMessages = messages)) :
        VaultRepository by delegate {
        var rowReads = 0
        override fun observeRecent(limit: Int): Flow<List<ArchivedMessage>> = delegate.observeRecent(limit).also { rowReads++ }
        override fun observeFavorites(limit: Int): Flow<List<ArchivedMessage>> = delegate.observeFavorites(limit).also { rowReads++ }
        override fun observeAppSummaries() = delegate.observeAppSummaries().also { rowReads++ }
    }

    private val now = { WidgetTestData.NOW }

    @Test
    fun `count reads stats`() = runTest {
        val load = WidgetLoaders.count(InMemoryVaultRepository(initialMessages = messages), InMemorySettingsRepository(), now)
        assertEquals(WidgetLoad.Ready(CountDisplay(5, 5, 2, locked = false), WidgetTestData.NOW), load)
    }

    @Test
    fun `recent default mode is grouped metadata`() = runTest {
        val load = WidgetLoaders.recent(SpyVault(), InMemorySettingsRepository(), now) as WidgetLoad.Ready
        assertTrue(load.value is MessageListDisplay.ByApp)
        assertFalse(load.value.toString().contains(SECRET_TEXT))
    }

    @Test
    fun `locked mode never reads message rows`() = runTest {
        val vault = SpyVault()
        val settings = InMemorySettingsRepository(VaultSettings(appLockEnabled = true, widgetShowContent = true))
        val recent = WidgetLoaders.recent(vault, settings, now) as WidgetLoad.Ready
        val favorites = WidgetLoaders.favorites(vault, settings, now) as WidgetLoad.Ready
        val apps = WidgetLoaders.appSummary(vault, settings, now) as WidgetLoad.Ready
        assertEquals(MessageListDisplay.Locked(5), recent.value)
        assertEquals(MessageListDisplay.Locked(0), favorites.value)
        assertEquals(AppSummaryDisplay.Locked(5, 2), apps.value)
        assertEquals(0, vault.rowReads)
    }

    @Test
    fun `content mode only when opted in`() = runTest {
        val settings = InMemorySettingsRepository(VaultSettings(widgetShowContent = true))
        val load = WidgetLoaders.recent(SpyVault(), settings, now) as WidgetLoad.Ready
        assertTrue(load.value is MessageListDisplay.Messages)
    }

    @Test
    fun `repository failure becomes Failed`() = runTest {
        val broken = object : VaultRepository by InMemoryVaultRepository() {
            override fun observeStats(): Flow<VaultStats> = flow { throw IllegalStateException("db closed") }
        }
        assertEquals(WidgetLoad.Failed, WidgetLoaders.count(broken, InMemorySettingsRepository(), now))
    }

    @Test
    fun `hanging repository times out into Failed`() = runTest {
        val hanging = object : VaultRepository by InMemoryVaultRepository() {
            override fun observeStats(): Flow<VaultStats> = flow { awaitCancellation() }
        }
        assertEquals(WidgetLoad.Failed, WidgetLoaders.count(hanging, InMemorySettingsRepository(), now))
    }

    @Test
    fun `capture loader maps listener and settings`() = runTest {
        val listener = FakeListenerStatus(granted = true, connected = false)
        val settings = InMemorySettingsRepository(VaultSettings(captureEnabled = true))
        assertEquals(CaptureState.DISCONNECTED, (WidgetLoaders.capture(settings, listener, now) as WidgetLoad.Ready).value)
        listener.granted = false
        assertEquals(CaptureState.ACCESS_NEEDED, (WidgetLoaders.capture(settings, listener, now) as WidgetLoad.Ready).value)
    }

    @Test
    fun `capture fingerprint never opens the vault`() = runTest {
        val listener = FakeListenerStatus()
        val settings = InMemorySettingsRepository()
        val states = WidgetFingerprints.of(
            WidgetKind.CAPTURE,
            vault = { error("Capture control must not touch the database") },
            settings = { settings },
            listener = { listener },
        ).take(1).toList()
        assertEquals(listOf(CaptureState.ACTIVE), states)
    }

    @Test
    fun `locked fingerprints observe counts only`() = runTest {
        val vault = SpyVault()
        val settings = InMemorySettingsRepository(VaultSettings(appLockEnabled = true))
        val listener = FakeListenerStatus()
        for (kind in listOf(WidgetKind.RECENT, WidgetKind.FAVORITES, WidgetKind.APP_SUMMARY)) {
            WidgetFingerprints.of(kind, { vault }, { settings }, { listener }).first()
        }
        assertEquals(0, vault.rowReads)
    }

    @Test
    fun `capture toggle respects access and explicit target`() = runTest {
        val settings = InMemorySettingsRepository(VaultSettings(captureEnabled = true))
        assertEquals(null, CaptureActions.apply(settings, accessGranted = false, targetEnabled = false))
        assertTrue(settings.current.captureEnabled)

        assertEquals(false, CaptureActions.apply(settings, accessGranted = true, targetEnabled = false))
        assertFalse(settings.current.captureEnabled)
        // A stale second tap with the same target must not flip it back.
        assertEquals(false, CaptureActions.apply(settings, accessGranted = true, targetEnabled = false))
        assertFalse(settings.current.captureEnabled)
        assertEquals(true, CaptureActions.apply(settings, accessGranted = true, targetEnabled = null))
        assertTrue(settings.current.captureEnabled)
    }
}
