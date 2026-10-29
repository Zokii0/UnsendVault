package com.riyadm.unsendvault.data.retention

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.riyadm.unsendvault.data.RetentionScheduler
import com.riyadm.unsendvault.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Unique periodic WorkManager job, every 24 h, only while the battery is not low and the device is idle. No exact
 * alarms, no network constraint. retentionDays == 0 cancels the job.
 *
 * Self-syncing: [startSync] (called by DataModule when the scheduler is created) follows
 * `settings.retentionDays` and calls [schedule] on every distinct value, so callers only need to touch
 * `container.retentionScheduler` once at startup. Explicit [schedule] calls stay valid and are idempotent
 * (ExistingPeriodicWorkPolicy.UPDATE keeps the existing period/schedule).
 */
class WorkManagerRetentionScheduler(
    context: Context,
    private val runner: RetentionRunner,
    private val workManagerProvider: () -> WorkManager = { WorkManager.getInstance(context.applicationContext) },
) : RetentionScheduler {

    override fun schedule(retentionDays: Int) {
        try {
            val wm = workManagerProvider()
            if (retentionDays <= 0) {
                wm.cancelUniqueWork(UNIQUE_WORK_NAME)
            } else {
                wm.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, buildRequest())
            }
        } catch (e: IllegalStateException) {
            // WorkManager not initialized (should not happen with the default initializer).
            Log.w(TAG, "Cannot schedule retention", e)
        }
    }

    override suspend fun runNow(): Int = runner.run()

    fun startSync(settings: SettingsRepository, scope: CoroutineScope): Job = scope.launch {
        settings.settings.map { it.retentionDays }.distinctUntilChanged().collect { schedule(it) }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "unsendvault-retention"
        private const val TAG = "UvRetention"

        fun buildRequest(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<RetentionWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true)
                    .build(),
            )
            .addTag(UNIQUE_WORK_NAME)
            .build()
    }
}
