package com.riyadm.unsendvault.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Persisted update-check state. Lives in its own DataStore file, separate from the vault settings. */
data class UpdatePrefs(
    val consent: UpdateConsent = UpdateConsent.UNASKED,
    /** Epoch millis of the last attempt (successful or not), null if never checked. */
    val lastCheckAt: Long? = null,
    /** True when the last attempt failed; the next automatic attempt then waits only the shorter retry window. */
    val lastCheckFailed: Boolean = false,
    val skippedVersion: String? = null,
    /** Latest eligible release seen by the last successful check (avoids re-fetching within the window). */
    val lastKnownLatest: ReleaseInfo? = null,
)

interface UpdateStore {
    val prefs: Flow<UpdatePrefs>

    suspend fun current(): UpdatePrefs = prefs.first()

    suspend fun edit(transform: (UpdatePrefs) -> UpdatePrefs)
}

/** Process-wide Preferences DataStore "update_check". A corrupt file is replaced with defaults. */
val Context.updateCheckDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "update_check",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class UpdatePreferences(private val dataStore: DataStore<Preferences>) : UpdateStore {

    override val prefs: Flow<UpdatePrefs> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::read)
        .distinctUntilChanged()

    override suspend fun edit(transform: (UpdatePrefs) -> UpdatePrefs) {
        dataStore.edit { p ->
            val next = transform(read(p))
            p[CONSENT] = next.consent.name
            p.setOrRemove(LAST_CHECK_AT, next.lastCheckAt)
            p[LAST_CHECK_FAILED] = next.lastCheckFailed
            p.setOrRemove(SKIPPED_VERSION, next.skippedVersion)
            p.setOrRemove(LAST_KNOWN_LATEST, next.lastKnownLatest?.let { ReleaseParser.json.encodeToString(ReleaseInfo.serializer(), it) })
        }
    }

    private fun read(p: Preferences) = UpdatePrefs(
        consent = p[CONSENT]?.let { name -> UpdateConsent.entries.firstOrNull { it.name == name } } ?: UpdateConsent.UNASKED,
        lastCheckAt = p[LAST_CHECK_AT],
        lastCheckFailed = p[LAST_CHECK_FAILED] ?: false,
        skippedVersion = p[SKIPPED_VERSION],
        lastKnownLatest = p[LAST_KNOWN_LATEST]?.let { raw ->
            runCatching { ReleaseParser.json.decodeFromString(ReleaseInfo.serializer(), raw) }.getOrNull()
        },
    )

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else set(key, value)
    }

    private companion object {
        val CONSENT = stringPreferencesKey("consent")
        val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        val LAST_CHECK_FAILED = booleanPreferencesKey("last_check_failed")
        val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        val LAST_KNOWN_LATEST = stringPreferencesKey("last_known_latest")
    }
}
