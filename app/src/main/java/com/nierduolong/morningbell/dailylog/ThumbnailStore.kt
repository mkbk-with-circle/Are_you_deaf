package com.nierduolong.morningbell.dailylog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频首帧缩略图：内存 LRU + 磁盘 JPEG 两级缓存。
 * 网格里一次要显示几十个 clip，每次都走 [MediaMetadataRetriever] 会明显掉帧，
 * 所以第一次解码后落盘复用，缓存键带上源文件长度以便文件被覆盖时自动失效。
 */
object ThumbnailStore {
    private const val TARGET_WIDTH = 360
    private const val DISK_CACHE_MAX_BYTES = 40L * 1024 * 1024
    private const val TRIM_EVERY_N_WRITES = 20

    private val memory = LruCache<String, ImageBitmap>(80)
    private var writesSinceTrim = 0

    suspend fun load(
        context: Context,
        videoPath: String,
    ): ImageBitmap? {
        val source = File(videoPath)
        if (!source.exists()) return null
        val key = cacheKey(source)
        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val cacheFile = File(DailyLogStorage.thumbsDir(context), "$key.jpg")
            val bitmap =
                if (cacheFile.exists()) {
                    runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
                } else {
                    extractFrame(source)?.also {
                        persist(it, cacheFile)
                        trimDiskCacheIfNeeded(context)
                    }
                }
            bitmap?.asImageBitmap()?.also { memory.put(key, it) }
        }
    }

    private fun cacheKey(source: File): String = "${source.nameWithoutExtension}_${source.length()}"

    private fun extractFrame(source: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            val frame = retriever.frameAtTime ?: return null
            val scale = TARGET_WIDTH.toFloat() / frame.width.coerceAtLeast(1)
            if (scale >= 1f) {
                frame
            } else {
                Bitmap.createScaledBitmap(
                    frame,
                    TARGET_WIDTH,
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== frame) frame.recycle() }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun persist(
        bitmap: Bitmap,
        target: File,
    ) {
        runCatching {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }.onFailure { target.delete() }
    }

    /**
     * 缩略图缓存没有上限就会随素材数量单调增长。每写若干张检查一次总大小，
     * 超限时按最近访问时间淘汰最旧的一半，避免频繁全目录扫描。
     */
    private fun trimDiskCacheIfNeeded(context: Context) {
        writesSinceTrim++
        if (writesSinceTrim < TRIM_EVERY_N_WRITES) return
        writesSinceTrim = 0
        runCatching {
            val files = DailyLogStorage.thumbsDir(context).listFiles()?.toList() ?: return
            var total = files.sumOf { it.length() }
            if (total <= DISK_CACHE_MAX_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= DISK_CACHE_MAX_BYTES / 2) return
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    /** 读取视频真实时长；录制统计缺失时作为兜底 */
    suspend fun durationMs(videoPath: String): Long =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoPath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            } finally {
                runCatching { retriever.release() }
            }
        }
}
