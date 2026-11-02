package cz.flos.pos.eet

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.flos.pos.FlosApp
import java.util.concurrent.TimeUnit

/** Resends sales that are still waiting for EET confirmation, whenever the device is online. */
class EetRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FlosApp
        if (!app.settings.state.value.eetEnabled) return Result.success()
        val left = app.eet.sendPending()
        return if (left > 0) Result.retry() else Result.success()
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** A periodic safety net every 15 minutes (the shortest period Android allows). */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<EetRetryWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("eet-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Retries soon after a failed sending: as soon as there is a connection, then with growing pauses. */
        fun scheduleSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<EetRetryWorker>()
                .setConstraints(online)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("eet-soon", ExistingWorkPolicy.KEEP, req)
        }
    }
}
