package com.nierduolong.morningbell.dailylog

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nierduolong.morningbell.MorningBellApp
import java.util.concurrent.TimeUnit

/** 后台补合成任务：每天跑一次，把「昨天」还没合成的日志素材拼好，避免用户没开 App 时漏合成 */
class DailyCompileWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MorningBellApp
        return try {
            val logId = app.repository.ensurePersonalDailyLog()
            DailyCompileManager.compileYesterdayIfNeeded(applicationContext, app.repository, logId)
            // 保留策略跟着日结跑：必须在补合成之后，保证被清的天都已经有合成结果
            runCatching { app.repository.applyRetentionPolicy() }
            Result.success()
        } catch (_: Exception) {
            // 合成失败通常是素材损坏这类不会自愈的原因，重试几次就放弃，
            // 免得 WorkManager 反复唤起做无用的转码耗电
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "daily_log_compile_worker"
        private const val MAX_ATTEMPTS = 3

        fun enqueuePeriodic(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<DailyCompileWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        // 转码很吃电和 IO，低电量或空间告急时先不做
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .setRequiresStorageNotLow(true)
                            .build(),
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
