package com.zepdriver.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zepdriver.AppGraph
import java.util.concurrent.TimeUnit

/** Background check for a newer data bundle whenever the phone is online. */
class DataUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // ZepApp.onCreate has always run before a worker starts.
        val repo = AppGraph.playa as? PlayaRepositoryImpl ?: return Result.success()
        return if (repo.checkNow()) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "playa-update"
        private const val ONE_SHOT = "playa-update-now"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every ~6 h while online. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DataUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** As soon as a connection exists (immediately if online now). */
        fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<DataUpdateWorker>()
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.KEEP, request)
        }
    }
}
