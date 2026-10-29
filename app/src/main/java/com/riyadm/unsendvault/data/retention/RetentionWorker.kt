package com.riyadm.unsendvault.data.retention

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riyadm.unsendvault.appContainer
import kotlinx.coroutines.CancellationException

/** Daily auto-delete. Dependencies come from the application's [com.riyadm.unsendvault.di.AppContainer]. */
class RetentionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val deleted = runnerFactory(applicationContext).run()
        Log.i(TAG, "Retention removed $deleted message(s)")
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Retention failed", e)
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val TAG = "UvRetention"
        private const val MAX_ATTEMPTS = 3

        /** Test seam; production resolves the container from the Application. */
        @VisibleForTesting
        @Volatile
        var runnerFactory: (Context) -> RetentionRunner = { ctx ->
            val container = ctx.appContainer
            RetentionRunner(container.settingsRepository, container.vaultRepository)
        }
    }
}
