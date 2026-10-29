package com.riyadm.unsendvault.data

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.retention.RetentionPolicy
import com.riyadm.unsendvault.data.retention.RetentionRunner
import com.riyadm.unsendvault.data.retention.RetentionWorker
import com.riyadm.unsendvault.data.retention.WorkManagerRetentionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionTest : RoomTestBase() {
    private val day = RetentionPolicy.DAY_MILLIS
    private val originalFactory = RetentionWorker.runnerFactory

    @After
    fun restore() {
        RetentionWorker.runnerFactory = originalFactory
    }

    @Test
    fun policy_cutoff() {
        assertNull(RetentionPolicy.cutoffMillis(0, T0))
        assertNull(RetentionPolicy.cutoffMillis(-3, T0))
        assertEquals(T0 - day, RetentionPolicy.cutoffMillis(1, T0))
        assertEquals(T0 - 30 * day, RetentionPolicy.cutoffMillis(30, T0))
        assertEquals(T0 - 3650L * day, RetentionPolicy.cutoffMillis(3650, T0)) // no Int overflow
        assertTrue(RetentionPolicy.KEEP_FAVORITES)
    }

    private suspend fun seedAges() {
        repo.ingest(parsed(msg("old", ts = T0 - 31 * day), msg("old fav", ts = T0 - 40 * day), msg("new", ts = T0 - day)), T0)
        repo.setFavorite(repo.observeMessages(MessageQuery(text = "old fav")).first().single().id, true)
    }

    @Test
    fun runner_usesSettings_andKeepsFavorites() = runBlocking {
        seedAges()
        val forever = RetentionRunner(InMemorySettingsRepository(VaultSettings(retentionDays = 0)), repo) { T0 }
        assertEquals(0, forever.run())
        val runner = RetentionRunner(InMemorySettingsRepository(VaultSettings(retentionDays = 30)), repo) { T0 }
        assertEquals(1, runner.run())
        assertEquals(setOf("old fav", "new"), repo.observeMessages(MessageQuery()).first().map { it.text }.toSet())
    }

    @Test
    fun worker_success_and_retryOnFailure() = runBlocking {
        seedAges()
        RetentionWorker.runnerFactory = { RetentionRunner(InMemorySettingsRepository(VaultSettings(retentionDays = 30)), repo) { T0 } }
        val worker = TestListenableWorkerBuilder<RetentionWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(2, repo.observeStats().first().totalMessages)

        RetentionWorker.runnerFactory = { error("boom") }
        assertEquals(ListenableWorker.Result.retry(), TestListenableWorkerBuilder<RetentionWorker>(context).build().doWork())
        assertEquals(
            ListenableWorker.Result.failure(),
            TestListenableWorkerBuilder<RetentionWorker>(context).setRunAttemptCount(3).build().doWork(),
        )
    }

    @Test
    fun scheduler_enqueuesConstrainedDailyWork_andCancelsForZero() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val wm = WorkManager.getInstance(context)
        val settings = InMemorySettingsRepository(VaultSettings(retentionDays = 30))
        val scheduler = WorkManagerRetentionScheduler(context, RetentionRunner(settings, repo) { T0 })
        fun infos() = wm.getWorkInfosForUniqueWork(WorkManagerRetentionScheduler.UNIQUE_WORK_NAME).get()

        scheduler.schedule(30)
        val info = infos().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertTrue(info.constraints.requiresDeviceIdle())
        assertEquals(NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
        assertEquals(24 * 60 * 60 * 1000L, info.periodicityInfo!!.repeatIntervalMillis)

        scheduler.schedule(7) // UPDATE keeps a single unique work
        assertEquals(1, infos().count { it.state == WorkInfo.State.ENQUEUED })

        scheduler.schedule(0)
        assertTrue(infos().all { it.state == WorkInfo.State.CANCELLED })

        // Self-sync follows settings.retentionDays.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            scheduler.startSync(settings, scope)
            withTimeout(5_000) { while (infos().none { it.state == WorkInfo.State.ENQUEUED }) delay(20) }
            settings.update { it.copy(retentionDays = 0) }
            withTimeout(5_000) { while (infos().any { it.state == WorkInfo.State.ENQUEUED }) delay(20) }
        } finally {
            scope.cancel()
        }

        seedAges()
        assertEquals(1, scheduler.runNow().also { settings.update { s -> s.copy(retentionDays = 30) } }.let { scheduler.runNow() + it })
    }
}
