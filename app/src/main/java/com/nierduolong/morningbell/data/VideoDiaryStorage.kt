package com.nierduolong.morningbell.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nierduolong.morningbell.util.AudioPickerUtils
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** 应用专属目录下的「视频日记」根：…/files/VideoDiary/年/月/日/（有视频才创建日文件夹） */
object VideoDiaryStorage {
    private const val ROOT_NAME = "VideoDiary"

    fun rootDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "files_ext")
        return File(base, ROOT_NAME)
    }

    /** 某日对应目录（在首次保存文件时 mkdirs） */
    fun dayDir(
        context: Context,
        dayEpoch: Long,
    ): File {
        val d = LocalDate.ofEpochDay(dayEpoch)
        return File(
            rootDir(context),
            String.format(Locale.US, "%d/%02d/%02d", d.year, d.monthValue, d.dayOfMonth),
        )
    }

    /** 该日正午的 epoch 毫秒，写入文件 mtime，便于按日期整理/上传 */
    fun fileTimeMillisForDay(
        dayEpoch: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long =
        LocalDate.ofEpochDay(dayEpoch).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /**
     * 从 [uri] 复制到 [dayEpoch] 对应日录，仅此时创建目录。
     * @return 相对 [rootDir] 的路径，如 2025/04/21/xxx.mp4
     */
    fun importFromUri(
        context: Context,
        uri: Uri,
        dayEpoch: Long,
    ): Result<ImportedVideo> {
        val dir = dayDir(context, dayEpoch)
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("无法创建目录"))
        }
        val displayName = queryDisplayName(context, uri) ?: "video"
        val ext = extensionOf(displayName)
        val base = sanitizeBaseName(displayName)
        var out = File(dir, "${base}_${System.currentTimeMillis()}.$ext")
        var n = 2
        while (out.exists()) {
            out = File(dir, "${base}_${System.currentTimeMillis()}_$n.$ext")
            n++
        }
        return try {
            var size = 0L
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开源" }
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        output.write(buf, 0, r)
                        size += r
                    }
                }
            }
            AudioPickerUtils.takePersistableReadPermission(context.contentResolver, uri)
            out.setLastModified(fileTimeMillisForDay(dayEpoch))
            val rel = relativePathFromRoot(context, out)
            Result.success(ImportedVideo(relativePath = rel, displayName = out.name, sizeBytes = size))
        } catch (e: Exception) {
            if (out.exists()) out.delete()
            tryPruneEmptyParents(context, dir)
            Result.failure(e)
        }
    }

    data class ImportedVideo(
        val relativePath: String,
        val displayName: String,
        val sizeBytes: Long,
    )

    fun resolveFile(
        context: Context,
        relativePath: String,
    ): File = File(rootDir(context), relativePath)

    fun relativePathFromRoot(
        context: Context,
        file: File,
    ): String {
        val root = rootDir(context).canonicalFile
        val f = file.canonicalFile
        return f.path.removePrefix(root.path).trimStart('/')
    }

    fun deletePhysicalFile(
        context: Context,
        relativePath: String,
    ) {
        val f = resolveFile(context, relativePath)
        if (f.exists()) f.delete()
        tryPruneEmptyParents(context, f.parentFile)
    }

    private fun tryPruneEmptyParents(
        context: Context,
        leafDir: File?,
    ) {
        val root = rootDir(context).canonicalFile
        var d: File? = leafDir?.canonicalFile ?: return
        while (d != null && d.path.startsWith(root.path) && d != root) {
            val list = d.list() ?: break
            if (list.isNotEmpty()) break
            if (!d.delete()) break
            d = d.parentFile
        }
    }

    private fun queryDisplayName(
        context: Context,
        uri: Uri,
    ): String? =
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }

    private fun extensionOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i in 1 until name.length - 1) {
            name.substring(i + 1).lowercase(Locale.US)
        } else {
            "mp4"
        }
    }

    private fun sanitizeBaseName(name: String): String {
        val i = name.lastIndexOf('.')
        val noExt = if (i > 0) name.substring(0, i) else name
        return noExt.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]+"), "_").take(40)
            .ifBlank { "video" }
    }
}
