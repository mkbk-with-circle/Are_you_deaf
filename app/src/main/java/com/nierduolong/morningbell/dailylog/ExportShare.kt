package com.nierduolong.morningbell.dailylog

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Setlog 功能 5：导出到相册 + 系统分享面板 */
object ExportShare {
    private const val ALBUM_RELATIVE_PATH = "Movies/你尔多龙吗"

    /**
     * 只有 Android 10+ 能用 MediaStore 在无存储权限的情况下写相册。
     * 更老的系统需要 WRITE_EXTERNAL_STORAGE 运行时权限，为一个早已边缘化的版本
     * 引入一整套旧权限流程不值当，UI 会改为只提供「分享」（走 FileProvider，无需权限）。
     */
    fun canSaveToGallery(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 把合成视频写入系统相册 Movies/你尔多龙吗，返回 MediaStore Uri；失败返回 null */
    suspend fun saveToGallery(
        context: Context,
        file: File,
    ): Uri? =
        withContext(Dispatchers.IO) {
            if (!canSaveToGallery() || !file.exists()) return@withContext null
            val values =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM_RELATIVE_PATH)
                    // 写入期间标记 pending，避免相册扫到半截文件
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            val uri =
                runCatching {
                    context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                }.getOrNull() ?: return@withContext null

            val copied =
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("无法打开相册输出流")
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, done, null, null)
                }
            if (copied.isFailure) {
                // 失败要把占位记录删掉，否则相册里会留一条永远 pending 的空条目
                runCatching { context.contentResolver.delete(uri, null, null) }
                return@withContext null
            }
            uri
        }

    /** 唤起系统分享面板；优先用相册 Uri，否则用 FileProvider 包一份 app 私有文件 */
    fun sharePendingIntent(
        context: Context,
        galleryUri: Uri?,
        fallbackFile: File?,
    ): Intent {
        val shareUri =
            galleryUri
                ?: fallbackFile?.let {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
                }
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                if (shareUri != null) {
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        // chooser 自身也要带上读权限，否则部分接收方拿不到私有目录的 Uri
        return Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
