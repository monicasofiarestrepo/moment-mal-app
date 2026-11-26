package app.momentmal.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.momentmal.container
import java.util.concurrent.TimeUnit

/** Daily content sync from GitHub. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.sync.syncIfStale(minIntervalMs = 12 * 60 * 60_000L)
        return Result.success()
    }
}

/** Weekly backup into Download/MomentMal/. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        runCatching { applicationContext.container.backup.autoBackup() }.fold({ Result.success() }, { Result.retry() })
}

object Workers {
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "content-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            "auto-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build(),
        )
    }
}
