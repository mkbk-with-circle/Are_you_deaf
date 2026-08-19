package com.nierduolong.morningbell.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** 图片预览落在有上限的 cacheDir，避免把编码后的 JPEG 和 Bitmap 同时堆在 Java heap。 */
class TransferImageCache(
    cacheDir: File,
    private val sourceReadGate: Semaphore? = null,
    private val opener: (TransferReadableEntry, Long) -> InputStream = { entry, offset -> entry.openAt(offset) },
) {
    private val directory = File(cacheDir, "nearby-transfer-images").apply { mkdirs() }
    private val gate = Semaphore(1, true)

    fun getOrCreate(entry: TransferReadableEntry, quality: String): File? {
        val maxEdge = maxEdge(quality) ?: return null
        if (!TransferMime.isImage(entry.path)) return null
        val target = File(directory, "${digest("${entry.etagSeed}|$quality|v3")}.jpg")
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        if (!gate.tryAcquire(PREVIEW_WAIT_SECONDS, TimeUnit.SECONDS)) return null
        var sourcePermit = false
        try {
            if (target.isFile && target.length() > 0) return target
            prune()
            sourceReadGate?.acquire()
            sourcePermit = sourceReadGate != null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            opener(entry, 0).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (
                bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_DECODE_PIXELS ||
                bounds.outWidth / sample > maxEdge * 2 ||
                bounds.outHeight / sample > maxEdge * 2
            ) {
                sample *= 2
                if (sample >= 128) break
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var bitmap = opener(entry, 0).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            bitmap = orient(bitmap, readOrientation(entry))
            if (sourcePermit) {
                sourceReadGate?.release()
                sourcePermit = false
            }
            if (bitmap.width > maxEdge || bitmap.height > maxEdge) {
                val scale = minOf(maxEdge.toFloat() / bitmap.width, maxEdge.toFloat() / bitmap.height)
                val resized = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (resized !== bitmap) bitmap.recycle()
                bitmap = resized
            }

            val temp = File(directory, "${target.name}.part")
            val written = runCatching {
                FileOutputStream(temp).buffered(64 * 1024).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, if (quality == "thumb") 72 else 84, output))
                }
                check(temp.length() in 1..MAX_SINGLE_CACHE_BYTES)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                true
            }.getOrDefault(false)
            bitmap.recycle()
            if (!written) {
                temp.delete()
                target.delete()
                return null
            }
            prune()
            return target
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            if (sourcePermit) sourceReadGate?.release()
            gate.release()
        }
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun readOrientation(entry: TransferReadableEntry): Int = runCatching {
        opener(entry, 0).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
                if (it !== source) source.recycle()
            }
        }.getOrDefault(source)
    }

    private fun prune() {
        val allFiles = directory.listFiles() ?: return
        allFiles.filter { it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > PART_MAX_AGE_MS }
            .forEach { it.delete() }
        val files = allFiles.filter { it.isFile && !it.name.endsWith(".part") }
        var total = files.sumOf(File::length)
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy(File::lastModified)) {
            val length = file.length()
            if (file.delete()) total -= length
            if (total <= TARGET_CACHE_BYTES) break
        }
    }

    companion object {
        fun maxEdge(quality: String): Int? = when (quality) {
            "thumb" -> 320
            "low" -> 720
            "mid" -> 1280
            "high" -> 1920
            else -> null
        }

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        // RGB_565 解码约 12 MiB；即使 EXIF 旋转短暂产生第二张 Bitmap，峰值仍约 24 MiB。
        private const val MAX_DECODE_PIXELS = 6_000_000L
        private const val MAX_SINGLE_CACHE_BYTES = 20L * 1024 * 1024
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private const val TARGET_CACHE_BYTES = 48L * 1024 * 1024
        private const val PART_MAX_AGE_MS = 60L * 60 * 1000
        private const val PREVIEW_WAIT_SECONDS = 45L
    }
}
