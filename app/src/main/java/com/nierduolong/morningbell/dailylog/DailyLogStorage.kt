package com.nierduolong.morningbell.dailylog

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** app 私有目录（不需要存储权限），按日归档拍摄素材与合成结果 */
object DailyLogStorage {
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 低于此可用空间就拒绝开始录制，避免录到一半写满磁盘产生损坏文件 */
    const val MIN_FREE_BYTES = 300L * 1024 * 1024

    private fun root(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "dailylog")
        dir.mkdirs()
        return dir
    }

    fun clipsDirForDay(
        context: Context,
        dayEpoch: Long,
    ): File {
        val dayStr = dayFmt.format(LocalDate.ofEpochDay(dayEpoch))
        val dir = File(root(context), dayStr)
        dir.mkdirs()
        return dir
    }

    fun newClipFile(
        context: Context,
        dayEpoch: Long,
    ): File = File(clipsDirForDay(context, dayEpoch), "clip_${System.currentTimeMillis()}.mp4")

    fun compilationsDir(context: Context): File {
        val dir = File(root(context), "compilations")
        dir.mkdirs()
        return dir
    }

    fun compilationFile(
        context: Context,
        dayEpoch: Long,
    ): File {
        val dayStr = dayFmt.format(LocalDate.ofEpochDay(dayEpoch))
        return File(compilationsDir(context), "daily_$dayStr.mp4")
    }

    fun thumbsDir(context: Context): File {
        val dir = File(root(context), "thumbs")
        dir.mkdirs()
        return dir
    }

    /**
     * 入库前把绝对路径压成相对 `dailylog/` 的路径。
     * 外置私有目录的挂载点会随换机、分区调整、恢复备份而变化，存绝对路径等于让全部
     * 历史记录在某天集体指向不存在的文件。不在日志目录下的路径原样返回（不该发生，兜底）。
     */
    fun relativize(
        context: Context,
        absolutePath: String,
    ): String {
        val prefix = root(context).absolutePath + File.separator
        return if (absolutePath.startsWith(prefix)) absolutePath.removePrefix(prefix) else absolutePath
    }

    fun relativize(
        context: Context,
        file: File,
    ): String = relativize(context, file.absolutePath)

    /** 读取时还原成本机绝对路径；历史遗留的绝对路径按原样使用，保证旧数据仍可播放 */
    fun resolve(
        context: Context,
        stored: String,
    ): File = if (stored.startsWith(File.separator)) File(stored) else File(root(context), stored)

    /**
     * 文件名 → 相对路径 的索引。批量重定位时先建一次索引，避免每条记录都全目录扫描
     * 一遍（几百条死链就是几百次遍历）。文件名带毫秒时间戳，重名可以忽略。
     */
    fun fileIndexByName(context: Context): Map<String, String> =
        runCatching {
            root(context).walkTopDown()
                .filter { it.isFile }
                .associate { it.name to relativize(context, it) }
        }.getOrDefault(emptyMap())

    /**
     * 记录指向的文件找不到时，按文件名在日志目录内重新定位，返回可入库的相对路径。
     * 覆盖「换机后目录前缀变了但文件确实还在」这种情况，避免记录变成死链。
     */
    fun relocate(
        context: Context,
        stored: String,
        index: Map<String, String> = fileIndexByName(context),
    ): String? {
        val name = File(stored).name
        if (name.isEmpty()) return null
        return index[name]
    }

    /** 清理完某天的原始素材后，空目录留着只会让归档目录越翻越乱 */
    fun deleteDayDirIfEmpty(
        context: Context,
        dayEpoch: Long,
    ) {
        runCatching {
            val dir = clipsDirForDay(context, dayEpoch)
            if (dir.listFiles()?.isEmpty() == true) dir.delete()
        }
    }

    /** 录制前的空间闸门：容量不足时上层应提示用户而不是照常开录 */
    fun hasEnoughFreeSpace(context: Context): Boolean = usableSpaceBytes(context) > MIN_FREE_BYTES

    fun usableSpaceBytes(context: Context): Long = runCatching { root(context).usableSpace }.getOrDefault(Long.MAX_VALUE)

    /** 日志功能占用的总字节数（素材 + 合成 + 缩略图），用于「我的」页展示与清理决策 */
    fun occupiedBytes(context: Context): Long =
        runCatching {
            root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    /** 删除缩略图缓存（可随时重建，属于最安全的清理项） */
    fun clearThumbnailCache(context: Context) {
        runCatching { thumbsDir(context).deleteRecursively() }
    }

    /** 孤儿判定的静默期：刚录完还没落库的文件不能被当成垃圾删掉 */
    private const val ORPHAN_MIN_AGE_MS = 6L * 60 * 60 * 1000

    /**
     * 清掉数据库里已经不存在记录的孤儿文件（例如录制中途崩溃留下的半截 mp4）。
     * [knownPaths] 由调用方从 Room 查出，避免存储层反向依赖数据层。
     *
     * 比对用**文件名**而不是完整路径：素材文件名带毫秒时间戳，本身已经唯一，而完整路径
     * 会因为换机、恢复备份、相对/绝对混存而整体失配——那种情况下按路径比对会把用户
     * 全部素材判成孤儿。
     *
     * 这个函数会删用户数据，所以还设了几道保险：
     * - 数据库一条素材都没查到时直接放弃（可能是库没读起来，而不是真的没素材）；
     * - 只清超过静默期的文件，避免和正在进行的录制抢文件；
     * - 待删数量反常地超过在册数量时中止。
     */
    fun pruneOrphanFiles(
        context: Context,
        knownPaths: Set<String>,
    ): Int {
        if (knownPaths.isEmpty()) return 0
        val knownNames = knownPaths.mapTo(HashSet()) { File(it).name }
        val thumbs = thumbsDir(context).absolutePath
        val compilations = compilationsDir(context).absolutePath
        val now = System.currentTimeMillis()

        val candidates =
            root(context).walkTopDown().filter { it.isFile }.filter { file ->
                val path = file.absolutePath
                val underCache = path.startsWith(thumbs) || path.startsWith(compilations)
                val stale = now - file.lastModified() > ORPHAN_MIN_AGE_MS
                // 合成目录只清残留的临时文件，正式合成结果由数据库删除逻辑负责
                if (underCache) {
                    path.startsWith(compilations) && path.endsWith(".tmp") && stale
                } else {
                    file.name !in knownNames && stale
                }
            }.toList()

        if (candidates.size > knownNames.size) return 0
        return candidates.count { it.delete() }
    }
}
