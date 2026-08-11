package com.nierduolong.morningbell.dailylog

import android.content.Context
import com.nierduolong.morningbell.data.AppRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate

/** Setlog 功能 4 的编排层：串起素材查询 → Media3 合成 → 落库，供手动按钮与后台任务共用 */
object DailyCompileManager {
    /** 合成很吃内存与编解码器，同一时刻只允许一个任务，避免手动触发和后台任务撞车 */
    private val lock = Mutex()

    /**
     * 该日已有且仍然新鲜的合成结果直接返回；素材有更新（或文件被清掉）时自动重合成；
     * 没有素材返回 null。
     */
    suspend fun compileDayIfNeeded(
        context: Context,
        repo: AppRepository,
        logId: Long,
        dayEpoch: Long,
        force: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): File? =
        lock.withLock {
            val existing = repo.getCompilationForDay(logId, dayEpoch)
            if (existing != null && !force && !isStale(repo, logId, dayEpoch, existing.filePath, existing.createdAt)) {
                return@withLock File(existing.filePath)
            }

            val clips = repo.clipsForDay(logId, dayEpoch).filter { File(it.filePath).exists() }
            // 没有可用素材时保留已有合成：按保留策略精简过的日期只剩合成结果，
            // 如果先删再发现没素材可拼，那一天就被彻底抹掉了
            if (clips.isEmpty()) return@withLock existing?.let { File(it.filePath) }
            if (existing != null) repo.deleteDailyCompilation(logId, dayEpoch)

            val output = DailyLogStorage.compilationFile(context, dayEpoch)
            val result =
                DailyCompiler.compile(
                    context,
                    clips.sortedBy { it.createdAt }.map { it.filePath },
                    output,
                    onProgress,
                )
            repo.saveDailyCompilation(logId, dayEpoch, result.absolutePath)
            result
        }

    /** 合成文件丢失，或之后又拍了新素材，都算过期 */
    private suspend fun isStale(
        repo: AppRepository,
        logId: Long,
        dayEpoch: Long,
        filePath: String,
        compiledAt: Long,
    ): Boolean {
        if (!File(filePath).exists()) return true
        val lastClipAt = repo.lastClipCreatedAt(logId, dayEpoch) ?: return false
        return lastClipAt > compiledAt
    }

    /** 该日是否需要（重新）合成，用于 UI 决定按钮文案 */
    suspend fun needsCompile(
        repo: AppRepository,
        logId: Long,
        dayEpoch: Long,
    ): Boolean {
        val clips = repo.clipsForDay(logId, dayEpoch)
        if (clips.isEmpty()) return false
        val existing = repo.getCompilationForDay(logId, dayEpoch) ?: return true
        return isStale(repo, logId, dayEpoch, existing.filePath, existing.createdAt)
    }

    /** App 打开时调用：若昨天有素材但还没合成，补一次（今天不自动合成，等到日结） */
    suspend fun compileYesterdayIfNeeded(
        context: Context,
        repo: AppRepository,
        logId: Long,
    ) {
        val yesterday = LocalDate.now().minusDays(1).toEpochDay()
        runCatching { compileDayIfNeeded(context, repo, logId, yesterday) }
    }
}
