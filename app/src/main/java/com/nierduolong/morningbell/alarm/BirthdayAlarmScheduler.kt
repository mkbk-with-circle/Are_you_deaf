package com.nierduolong.morningbell.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.nierduolong.morningbell.MainActivity

/**
 * 生日提醒专用调度：与主闹钟 [AlarmScheduler] 使用不同 requestCode 命名空间，避免 id 撞车。
 */
object BirthdayAlarmScheduler {
    /** 固定前缀，防止与普通闹钟 PendingIntent 冲突 */
    private const val NAMESPACE_XOR = 0x61BDF00D

    private fun requestCode(
        reminderId: Long,
        snoozeOneShot: Boolean,
    ): Int {
        var h = (reminderId xor (reminderId shr 32)).toInt()
        h = h xor NAMESPACE_XOR
        h = h xor if (snoozeOneShot) 0x10000000 else 0
        return h and 0x7FFFFFFF
    }

    fun cancel(
        context: Context,
        reminderId: Long,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingFire(context, reminderId, snoozeOneShot = false))
        am.cancel(pendingFire(context, reminderId, snoozeOneShot = true))
    }

    fun schedule(
        context: Context,
        reminderId: Long,
        triggerAtMillis: Long,
        snoozeOneShot: Boolean,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingFire(context, reminderId, snoozeOneShot)
        val show =
            PendingIntent.getActivity(
                context,
                -3000 + requestCode(reminderId, snoozeOneShot),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, show)
        am.setAlarmClock(info, pi)
    }

    private fun pendingFire(
        context: Context,
        reminderId: Long,
        snoozeOneShot: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_FIRE
                putExtra(AlarmReceiver.EXTRA_ALARM_ID, reminderId)
                putExtra(AlarmReceiver.EXTRA_SNOOZE_ONE_SHOT, snoozeOneShot)
                putExtra(AlarmReceiver.EXTRA_IS_CHAIN_STEP, false)
                putExtra(AlarmReceiver.EXTRA_IS_BIRTHDAY_REMINDER, true)
            }
        return PendingIntent.getBroadcast(
            context,
            requestCode(reminderId, snoozeOneShot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
