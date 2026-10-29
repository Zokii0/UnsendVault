package com.riyadm.unsendvault.update

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdatePreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun store(file: File) = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
    )

    @Test
    fun defaults() = runBlocking {
        assertEquals(UpdatePrefs(), UpdatePreferences(store(File(tmp.root, "a.preferences_pb"))).current())
    }

    @Test
    fun everyFieldRoundTrips() = runBlocking {
        val prefs = UpdatePreferences(store(File(tmp.root, "b.preferences_pb")))
        val expected = UpdatePrefs(
            consent = UpdateConsent.ALLOWED,
            lastCheckAt = 1_800_000_000_000L,
            lastCheckFailed = true,
            skippedVersion = "1.0.16",
            lastKnownLatest = release("1.0.17"),
        )
        prefs.edit { expected }
        assertEquals(expected, prefs.current())
        prefs.edit { it.copy(skippedVersion = null, lastKnownLatest = null, lastCheckAt = null) }
        assertEquals(expected.copy(skippedVersion = null, lastKnownLatest = null, lastCheckAt = null), prefs.current())
    }

    @Test
    fun unreadableCacheAndUnknownConsentFallBackToDefaults() = runBlocking {
        val ds = store(File(tmp.root, "c.preferences_pb"))
        ds.edit {
            it[stringPreferencesKey("consent")] = "MAYBE"
            it[stringPreferencesKey("last_known_latest")] = "{not json"
        }
        val prefs = UpdatePreferences(ds).current()
        assertEquals(UpdateConsent.UNASKED, prefs.consent)
        assertNull(prefs.lastKnownLatest)
    }
}
