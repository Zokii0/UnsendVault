package com.riyadm.unsendvault.data.retention

import com.riyadm.unsendvault.data.SettingsRepository
import com.riyadm.unsendvault.data.VaultRepository
import kotlinx.coroutines.flow.first

/** Pure retention rules. */
object RetentionPolicy {
    const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Favorites are never auto-deleted; the user deletes them explicitly. */
    const val KEEP_FAVORITES = true

    /** Messages with messageTimestamp strictly before the returned instant are deleted; null = keep forever. */
    fun cutoffMillis(retentionDays: Int, nowMillis: Long): Long? =
        if (retentionDays <= 0) null else nowMillis - retentionDays.toLong() * DAY_MILLIS
}

/** One retention pass, shared by the worker and [com.riyadm.unsendvault.data.RetentionScheduler.runNow]. */
class RetentionRunner(
    private val settings: SettingsRepository,
    private val vault: VaultRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): Int {
        val days = settings.settings.first().retentionDays
        val cutoff = RetentionPolicy.cutoffMillis(days, clock()) ?: return 0
        return vault.deleteOlderThan(cutoff, keepFavorites = RetentionPolicy.KEEP_FAVORITES)
    }
}
