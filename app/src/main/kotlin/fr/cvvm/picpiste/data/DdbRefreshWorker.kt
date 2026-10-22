package fr.cvvm.picpiste.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.cvvm.picpiste.repository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads the OGN device database once a day, on Wi-Fi, in the background: the copy is
 * fresh before going to the field, where mobile data is often weak.
 */
class DdbRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = applicationContext.repository
        return try {
            withContext(Dispatchers.IO) { repo.ddbStore.download() }
            repo.onDdbDownloaded()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "OGN device database not downloaded: $e")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "DdbRefreshWorker"
        private const val NAME = "ogn-ddb-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DdbRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
