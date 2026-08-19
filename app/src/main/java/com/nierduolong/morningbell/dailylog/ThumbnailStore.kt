package com.nierduolong.morningbell.dailylog

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 视频首帧缩略图：内存 LRU + 磁盘 JPEG 两级缓存。
 * 网格里一次要显示几十个 clip，每次都走 [MediaMetadataRetriever] 会明显掉帧，
 * 所以第一次解码后落盘复用，缓存键带上源文件长度以便文件被覆盖时自动失效。
 */
object ThumbnailStore {
    private const val TARGET_WIDTH = 320
    private const val MEMORY_CACHE_MAX_KIB = 16 * 1024
    private const val MAX_DECODE_PIXELS = 512 * 1024
    private const val DISK_CACHE_MAX_BYTES = 40L * 1024 * 1024
    private const val TRIM_EVERY_N_WRITES = 20

    private val memory =
        object : LruCache<String, ImageBitmap>(MEMORY_CACHE_MAX_KIB) {
            override fun sizeOf(key: String, value: ImageBitmap): Int =
                ThumbnailDecodePolicy.estimatedKiB(value.width, value.height)
        }
    /** MediaMetadataRetriever 的全帧解码峰值高；最多允许两张缩略图同时进入解码区。 */
    private val decodePermits = Semaphore(2)
    private var writesSinceTrim = 0

    suspend fun load(
        context: Context,
        videoPath: String,
        thumbnailPath: String? = null,
    ): ImageBitmap? {
        thumbnailPath?.takeIf { it.isNotBlank() }?.let { path ->
            val cached = File(path)
            if (cached.isFile) return loadImageFile(cached)
        }
        val source = File(videoPath)
        if (!source.isFile) return null
        val key = cacheKey(source)
        memory.get(key)?.let { return it }

        val cacheFile = ensureThumbnailFile(context, source) ?: return null
        return loadImageFile(cacheFile, key)
    }

    suspend fun ensureThumbnailFile(
        context: Context,
        videoFile: File,
    ): File? =
        withContext(Dispatchers.IO) {
            if (!videoFile.isFile) return@withContext null
            val cacheFile = File(DailyLogStorage.thumbsDir(context), "${cacheKey(videoFile)}.jpg")
            if (cacheFile.isFile && cacheFile.length() > 0) return@withContext cacheFile
            decodePermits.withPermit {
                // 等待 permit 时另一协程可能已经生成完，进入昂贵解码区后必须再检查一次。
                if (!cacheFile.isFile || cacheFile.length() <= 0) {
                    val frame = extractFrame(videoFile) ?: return@withPermit
                    persist(frame, cacheFile)
                    frame.recycle()
                }
            }
            if (!cacheFile.isFile || cacheFile.length() <= 0) return@withContext null
            trimDiskCacheIfNeeded(context)
            cacheFile
        }

    private suspend fun loadImageFile(
        file: File,
        cacheKey: String = "image:${file.absolutePath}:${file.length()}:${file.lastModified()}",
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            memory.get(cacheKey)?.let { return@withContext it }
            decodePermits.withPermit {
                memory.get(cacheKey)?.let { return@withPermit it }
                decodeDownsampled(file)?.asImageBitmap()?.also {
                    memory.put(cacheKey, it)
                }
            }
        }

    /**
     * 多 Log 后不同成员完全可能产生同名、同长度文件；只用文件名会串缩略图。
     * 路径只参与本机哈希，不会被上传或暴露。
     */
    private fun cacheKey(source: File): String {
        val raw = "${source.absolutePath}|${source.length()}|${source.lastModified()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    private fun extractFrame(source: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            val sourceWidth =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.coerceAtLeast(1)
                    ?: TARGET_WIDTH
            val sourceHeight =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.coerceAtLeast(1)
                    ?: TARGET_WIDTH
            val targetWidth = minOf(TARGET_WIDTH, sourceWidth)
            val targetHeight = (sourceHeight.toLong() * targetWidth / sourceWidth).toInt().coerceAtLeast(1)
            val frame =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        -1,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight,
                    )
                } else {
                    retriever.frameAtTime
                } ?: return null
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

    /** 对来自其他成员的 JPEG 先读尺寸再采样，压缩率极高的超大图片也不能触发 OOM。 */
    private fun decodeDownsampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = ThumbnailDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_PIXELS)
        val decoded =
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return null
        if (decoded.width <= TARGET_WIDTH) return decoded
        val height = (decoded.height.toLong() * TARGET_WIDTH / decoded.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, TARGET_WIDTH, height, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> memory.evictAll()
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> memory.trimToSize(MEMORY_CACHE_MAX_KIB / 2)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> memory.trimToSize(MEMORY_CACHE_MAX_KIB / 4)
        }
    }

    fun clearMemoryCache() = memory.evictAll()

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

internal object ThumbnailDecodePolicy {
    fun estimatedKiB(width: Int, height: Int): Int =
        // ARGB_8888: pixels * 4 / 1024 == pixels / 256。先约分可避免极端尺寸乘 4 溢出 Long。
        ((width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1) + 255) / 256)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    fun sampleSize(
        width: Int,
        height: Int,
        maxPixels: Int,
    ): Int {
        require(maxPixels > 0)
        var sample = 1
        while ((width / sample).coerceAtLeast(1).toLong() * (height / sample).coerceAtLeast(1) > maxPixels) {
            sample = sample shl 1
        }
        return sample
    }
}
