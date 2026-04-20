package com.nierduolong.morningbell.data

import android.content.Context

/** 早起统计：当日「首次解锁」需不早于该时刻（从 0 点起的分钟数 0–1439） */
class WakeSettings(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMinuteOfDay(): Int =
        prefs.getInt(KEY_WAKE_START_MINUTE_OF_DAY, DEFAULT_MINUTE_OF_DAY).coerceIn(0, MAX_MINUTE_OF_DAY)

    fun setMinuteOfDay(minutes: Int) {
        prefs.edit().putInt(KEY_WAKE_START_MINUTE_OF_DAY, minutes.coerceIn(0, MAX_MINUTE_OF_DAY)).apply()
    }

    companion object {
        private const val PREFS_NAME = "morning_bell_wake_settings"
        private const val KEY_WAKE_START_MINUTE_OF_DAY = "wake_start_minute_of_day"
        /** 默认 6:00 */
        const val DEFAULT_MINUTE_OF_DAY = 6 * 60
        private const val MAX_MINUTE_OF_DAY = 23 * 60 + 59

        fun formatMinuteOfDay(minutes: Int): String {
            val m = minutes.coerceIn(0, MAX_MINUTE_OF_DAY)
            val h = m / 60
            val mi = m % 60
            return "%02d:%02d".format(h, mi)
        }
    }
}
