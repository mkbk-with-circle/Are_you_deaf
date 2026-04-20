package com.nierduolong.morningbell.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** 选取本地音频后的持久读权限与展示名 */
object AudioPickerUtils {
    fun takePersistableReadPermission(
        cr: ContentResolver,
        uri: Uri,
    ) {
        try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // 部分 Rom 或未带 persistable 标记时仍可尝试直接播放一次
        }
    }

    fun ringtoneSummary(
        context: Context,
        uriString: String?,
        defaultLabel: String,
    ): String {
        if (uriString.isNullOrBlank()) return defaultLabel
        val parsed =
            try {
                Uri.parse(uriString)
            } catch (_: Exception) {
                return defaultLabel
            }
        val name =
            try {
                context.contentResolver.query(
                    parsed,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        val base = name?.takeIf { it.isNotBlank() } ?: parsed.lastPathSegment ?: ""
        return if (base.isBlank()) "自定义音频" else base
    }
}
