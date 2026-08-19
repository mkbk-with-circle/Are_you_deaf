package com.nierduolong.morningbell

import android.app.Application
import com.nierduolong.morningbell.core.CrashLogger
import com.nierduolong.morningbell.dailylog.DailyCompileManager
import com.nierduolong.morningbell.dailylog.DailyCompileWorker
import com.nierduolong.morningbell.dailylog.ThumbnailStore
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MorningBellApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var repository: AppRepository
        private set

    /**
     * 与进程同生命周期的作用域：视频合成这类「不该被返回键取消」的任务放这里，
     * SupervisorJob 保证单个任务失败不会连带杀掉其他任务。
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        database = AppDatabase.build(this)
        repository = AppRepository(this, database)
        DailyCompileWorker.enqueuePeriodic(this)
        appScope.launch {
            repository.seedIfEmpty()
            val logId = repository.ensurePersonalDailyLog()
            repository.rescheduleAllBirthdayReminders()
            // 启动自愈的顺序是有讲究的：
            // 1) 先把路径归一化（历史绝对路径 → 相对路径），否则下一步的孤儿判定会拿
            //    失配的路径去比对，把仍在册的素材当成垃圾；
            // 2) 再清崩溃残留的孤儿文件；
            // 3) 然后补合成昨天的日志；
            // 4) 最后才按保留策略清理原始素材——必须排在合成之后，否则会清掉还没合成的天。
            if (!repository.hasNormalizedDailyLogPaths()) {
                runCatching { repository.normalizeDailyLogPaths() }
            }
            runCatching { repository.pruneOrphanDailyLogFiles() }
            DailyCompileManager.compileYesterdayIfNeeded(this@MorningBellApp, repository, logId)
            runCatching { repository.applyRetentionPolicy() }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ThumbnailStore.onTrimMemory(level)
    }

    @Deprecated("Android framework callback")
    override fun onLowMemory() {
        ThumbnailStore.clearMemoryCache()
        super.onLowMemory()
    }
}
