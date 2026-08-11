package com.nierduolong.morningbell.dailylog

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Setlog 功能 4：一日日志自动合成。用 Media3 Transformer 原生拼接当天素材，
 * 避免引入 ffmpeg 二进制。单人场景为顺序拼接；多人分屏留待 Phase 2（有多路素材后再启用网格布局）。
 */
object DailyCompiler {
    private const val PROGRESS_POLL_MS = 250L

    /**
     * 必须在带 Looper 的线程调用（这里切到主线程），[outputFile] 已存在会被覆盖。
     *
     * 先写临时文件再改名：中途失败或被杀进程时不会留下一个「看起来存在、其实播不了」的
     * 半截合成文件——上层判断新鲜度靠的就是文件是否存在。
     */
    suspend fun compile(
        context: Context,
        clipFilePaths: List<String>,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): File {
        val existing = clipFilePaths.filter { File(it).exists() }
        require(existing.isNotEmpty()) { "没有可合成的素材" }
        outputFile.parentFile?.mkdirs()
        val temp = File(outputFile.parentFile, "${outputFile.name}.tmp")
        if (temp.exists()) temp.delete()

        // 单条素材直接拷贝：省掉一次全量转码，画质也不会被二次压缩
        if (existing.size == 1) {
            withContext(Dispatchers.IO) {
                File(existing[0]).copyTo(temp, overwrite = true)
                promote(temp, outputFile)
            }
            onProgress(1f)
            return outputFile
        }

        return withContext(Dispatchers.Main.immediate) {
            val items =
                existing.map { path ->
                    EditedMediaItem.Builder(MediaItem.fromUri(File(path).toUri())).build()
                }
            val sequence = EditedMediaItemSequence(items)
            val composition = Composition.Builder(listOf(sequence)).build()
            val transformer =
                Transformer.Builder(context)
                    .build()

            coroutineScope {
                val progressJob =
                    launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress(holder.progress / 100f)
                            }
                            delay(PROGRESS_POLL_MS)
                        }
                    }
                try {
                    suspendCancellableCoroutine { cont ->
                        transformer.addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    if (!cont.isActive) return
                                    val promoted = runCatching { promote(temp, outputFile) }
                                    if (promoted.isSuccess) {
                                        cont.resume(outputFile)
                                    } else {
                                        cont.resumeWithException(
                                            promoted.exceptionOrNull() ?: IllegalStateException("合成结果落盘失败"),
                                        )
                                    }
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    runCatching { temp.delete() }
                                    if (cont.isActive) cont.resumeWithException(exportException)
                                }
                            },
                        )
                        cont.invokeOnCancellation {
                            runCatching { transformer.cancel() }
                            runCatching { temp.delete() }
                        }
                        try {
                            transformer.start(composition, temp.absolutePath)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            runCatching { temp.delete() }
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                } finally {
                    progressJob.cancel()
                }
            }
        }
    }

    /** 原子化替换：rename 失败（少见的跨卷情况）时退化成拷贝 */
    private fun promote(
        temp: File,
        target: File,
    ) {
        if (target.exists()) target.delete()
        if (temp.renameTo(target)) return
        temp.copyTo(target, overwrite = true)
        temp.delete()
    }
}
