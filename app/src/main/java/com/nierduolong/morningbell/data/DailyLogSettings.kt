package com.nierduolong.morningbell.data

import android.content.Context
import com.nierduolong.morningbell.core.RetentionPolicy

/** 每日日志（Setlog 风格）本地设置：拍摄提醒周期与活跃时段、本地昵称、是否已完成首次引导 */
class DailyLogSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class ReminderCadence {
        HOURLY,
        EVERY_3_HOURS,

        /** 活跃时段内随机间隔（45–150 分钟），更接近 Setlog「不定时来一下」的手感 */
        RANDOM,
        OFF,
    }

    fun getReminderCadence(): ReminderCadence =
        when (prefs.getString(KEY_CADENCE, ReminderCadence.OFF.name)) {
            ReminderCadence.HOURLY.name -> ReminderCadence.HOURLY
            ReminderCadence.EVERY_3_HOURS.name -> ReminderCadence.EVERY_3_HOURS
            ReminderCadence.RANDOM.name -> ReminderCadence.RANDOM
            else -> ReminderCadence.OFF
        }

    fun setReminderCadence(cadence: ReminderCadence) {
        prefs.edit().putString(KEY_CADENCE, cadence.name).apply()
    }

    /** 活跃时段起始小时（含），提醒不会早于这个点 */
    fun getActiveStartHour(): Int = prefs.getInt(KEY_ACTIVE_START, DEFAULT_ACTIVE_START).coerceIn(0, 23)

    /** 活跃时段结束小时（不含），提醒不会晚于这个点，避免半夜被叫醒 */
    fun getActiveEndHour(): Int = prefs.getInt(KEY_ACTIVE_END, DEFAULT_ACTIVE_END).coerceIn(1, 24)

    fun setActiveWindow(
        startHour: Int,
        endHour: Int,
    ) {
        val start = startHour.coerceIn(0, 23)
        // 至少留 1 小时窗口，否则调度算法找不到可用时刻
        val end = endHour.coerceIn(start + 1, 24)
        prefs.edit().putInt(KEY_ACTIVE_START, start).putInt(KEY_ACTIVE_END, end).apply()
    }

    /** 原始素材保留天数：0 = 永久保留（默认，不会自动删任何东西） */
    fun getRetentionDays(): Int = RetentionPolicy.normalizeDays(prefs.getInt(KEY_RETENTION_DAYS, 0))

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, RetentionPolicy.normalizeDays(days)).apply()
    }

    /** 路径归一化（绝对 → 相对）只需要成功执行一次 */
    fun hasNormalizedPaths(): Boolean = prefs.getBoolean(KEY_PATHS_NORMALIZED, false)

    fun setPathsNormalized(value: Boolean) {
        prefs.edit().putBoolean(KEY_PATHS_NORMALIZED, value).apply()
    }

    fun getNickname(): String = prefs.getString(KEY_NICKNAME, null)?.takeIf { it.isNotBlank() } ?: "我"

    fun setNickname(name: String) {
        prefs.edit().putString(KEY_NICKNAME, name).apply()
    }

    fun hasOnboarded(): Boolean = prefs.getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
    }

    companion object {
        const val DEFAULT_ACTIVE_START = 9
        const val DEFAULT_ACTIVE_END = 22

        private const val PREFS_NAME = "morning_bell_daily_log_settings"
        private const val KEY_CADENCE = "reminder_cadence"
        private const val KEY_ACTIVE_START = "reminder_active_start_hour"
        private const val KEY_ACTIVE_END = "reminder_active_end_hour"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_PATHS_NORMALIZED = "paths_normalized_v1"
    }
}
