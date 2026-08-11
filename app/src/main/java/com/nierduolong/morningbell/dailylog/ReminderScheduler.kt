package com.nierduolong.morningbell.dailylog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nierduolong.morningbell.core.ReminderWindowPolicy
import com.nierduolong.morningbell.data.DailyLogSettings
import kotlin.random.Random

/**
 * Setlog 功能 2：定时拍摄提醒（每小时 / 每 3 小时 / 随机 / 关闭），只在活跃时段内响。
 * 用单个自续期的精确闹钟实现周期提醒，避免 [AlarmManager.setRepeating] 在 Doze 下漂移。
 */
object ReminderScheduler {
    private const val REQUEST_CODE = 0x5E7106
    private const val RANDOM_MIN_MINUTES = 45
    private const val RANDOM_MAX_MINUTES = 150

    /** 切换提醒周期：取消旧闹钟，非「关闭」时立即排下一次 */
    fun apply(
        context: Context,
        cadence: DailyLogSettings.ReminderCadence,
    ) {
        cancel(context)
        if (cadence == DailyLogSettings.ReminderCadence.OFF) return
        scheduleNext(context, nextTriggerAt(context, cadence))
    }

    /** 提醒触发后，续排下一次（周期若已被关闭则不再续） */
    fun scheduleFollowing(context: Context) {
        val cadence = DailyLogSettings(context).getReminderCadence()
        if (cadence == DailyLogSettings.ReminderCadence.OFF) return
        scheduleNext(context, nextTriggerAt(context, cadence))
    }

    /** 开机后系统会清空所有闹钟，必须按当前设置重新排一次，否则提醒会静默失效 */
    fun rescheduleAfterBoot(context: Context) {
        apply(context, DailyLogSettings(context).getReminderCadence())
    }

    /** 供 UI 展示「下一次大约什么时候」，不产生副作用 */
    fun previewNextTriggerAt(context: Context): Long? {
        val cadence = DailyLogSettings(context).getReminderCadence()
        if (cadence == DailyLogSettings.ReminderCadence.OFF) return null
        return nextTriggerAt(context, cadence)
    }

    private fun nextTriggerAt(
        context: Context,
        cadence: DailyLogSettings.ReminderCadence,
    ): Long {
        val settings = DailyLogSettings(context)
        return ReminderWindowPolicy.nextTriggerAt(
            nowMillis = System.currentTimeMillis(),
            intervalMinutes = intervalMinutes(cadence),
            startHour = settings.getActiveStartHour(),
            endHour = settings.getActiveEndHour(),
        )
    }

    /** internal 便于单元测试直接校验周期换算，不引入 Android 依赖 */
    internal fun intervalMinutes(cadence: DailyLogSettings.ReminderCadence): Int =
        when (cadence) {
            DailyLogSettings.ReminderCadence.HOURLY -> 60
            DailyLogSettings.ReminderCadence.EVERY_3_HOURS -> 180
            DailyLogSettings.ReminderCadence.RANDOM -> Random.nextInt(RANDOM_MIN_MINUTES, RANDOM_MAX_MINUTES + 1)
            DailyLogSettings.ReminderCadence.OFF -> 0
        }

    /**
     * Android 12+ 用户可以撤销「精确闹钟」权限，此时 setExactAndAllowWhileIdle 会抛 SecurityException。
     * 提醒并非分秒必争，降级成非精确闹钟继续可用，比崩溃或彻底不提醒都好。
     */
    private fun scheduleNext(
        context: Context,
        triggerAtMillis: Long,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingFire(context)
        val canExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }.onFailure {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending) }
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingFire(context))
    }

    private fun pendingFire(context: Context): PendingIntent {
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_FIRE
            }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
