package com.nierduolong.morningbell.core

/** 日志页展示用的纯计算：连续记录天数与人类可读格式化，与 Android 框架无关，便于单测 */
object DailyLogStats {
    /**
     * 连续记录天数。今天还没拍不算断，从昨天往前数（与心情连续天数同一口径）。
     */
    fun computeStreak(
        daysWithClips: Set<Long>,
        today: Long,
    ): Int {
        if (daysWithClips.isEmpty()) return 0
        var cursor = if (today in daysWithClips) today else today - 1
        if (cursor !in daysWithClips) return 0
        var streak = 0
        while (cursor in daysWithClips) {
            streak++
            cursor--
        }
        return streak
    }

    /** 视频时长：不足一小时用 m:ss，超过则 h:mm:ss */
    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
