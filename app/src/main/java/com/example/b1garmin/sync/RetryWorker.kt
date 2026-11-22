package com.example.b1garmin.sync

import android.content.Context
import android.util.Log
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
import com.example.b1garmin.data.MeasurementRepository
import com.example.b1garmin.data.RecordRetry
import com.example.b1garmin.data.toEstimateOrNull
import com.example.b1garmin.garmin.GarminAuth
import java.util.concurrent.TimeUnit

/**
 * 后台补传：有网且已登录时，把本地 FAILED 记录逐条重试。
 *
 * 和手动补传共用 [RecordRetry]，成功后回写本地状态和 ledger。
 */
class RetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = MeasurementRepository.get(applicationContext)
        val failed = runCatching { repo.failed() }.getOrElse {
            Log.w(TAG, "读取失败记录出错：${it.message}")
            return Result.retry()
        }
        if (failed.isEmpty()) return Result.success()

        val prefs = applicationContext.getSharedPreferences("b1_prefs", Context.MODE_PRIVATE)
        val region = runCatching {
            GarminAuth.Region.valueOf(prefs.getString("garmin_region", null).orEmpty())
        }.getOrDefault(GarminAuth.Region.GLOBAL)
        val auth = GarminAuth(applicationContext, region)
        if (auth.load() == null) {
            Log.i(TAG, "未登录 Garmin，跳过自动补传")
            return Result.success()
        }

        val ledger = applicationContext.getSharedPreferences("b1_ledger", Context.MODE_PRIVATE)
        var remaining = 0
        var skipped = 0
        for (row in failed) {
            // 没有体成分的记录生成不了 FIT，跳过，避免无限重试
            if (!ledger.getBoolean(row.dedupeKey, false) && row.toEstimateOrNull() == null) {
                skipped++
                continue
            }
            val result = RecordRetry.retry(row, auth, repo, ledger) { Log.i(TAG, it) }
            if (result.isFailure) remaining++
        }
        Log.i(TAG, "自动补传完成：待补 ${failed.size}，跳过 $skipped，仍未成功 $remaining")
        return if (remaining > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "RetryWorker"
        const val PERIODIC_NAME = "periodic_retry_failed"
        const val ONCE_NAME = "once_retry_failed"
        const val PERIOD_HOURS = 6L

        /** App 启动时调用：注册周期任务，并立刻尝试一次。 */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetryWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .build(),
            )
            val once = OneTimeWorkRequestBuilder<RetryWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniqueWork(ONCE_NAME, ExistingWorkPolicy.REPLACE, once)
        }
    }
}
