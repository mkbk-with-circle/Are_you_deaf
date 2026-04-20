package com.nierduolong.morningbell.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.nierduolong.morningbell.MainActivity

/** 使用 setAlarmClock 提高在 Doze / 省电下的可靠性；普通闹钟与连锁步骤用 requestCode 区分防冲突 */
object AlarmScheduler {
    private fun requestCode(
        rawId: Long,
        snoozeOneShot: Boolean,
        isChainStep: Boolean,
    ): Int {
        var h = (rawId xor (rawId shr 32)).toInt()
        h = h xor if (isChainStep) 0x5190C8FF else 0
        h = h xor if (snoozeOneShot) 0x10000000 else 0
        return h and 0x7FFFFFFF
    }

    fun cancel(
        context: Context,
        rawId: Long,
        isChainStep: Boolean,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingFire(context, rawId, snoozeOneShot = false, isChainStep = isChainStep))
        am.cancel(pendingFire(context, rawId, snoozeOneShot = true, isChainStep = isChainStep))
    }

    fun scheduleNext(
        context: Context,
        rawId: Long,
        triggerAtMillis: Long,
        snoozeOneShot: Boolean,
        isChainStep: Boolean,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingFire(context, rawId, snoozeOneShot, isChainStep)
        val show =
            PendingIntent.getActivity(
                context,
                -2000 + requestCode(rawId, snoozeOneShot, isChainStep),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, show)
        am.setAlarmClock(info, pi)
    }

    private fun pendingFire(
        context: Context,
        rawId: Long,
        snoozeOneShot: Boolean,
        isChainStep: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_FIRE
                putExtra(AlarmReceiver.EXTRA_ALARM_ID, rawId)
                putExtra(AlarmReceiver.EXTRA_SNOOZE_ONE_SHOT, snoozeOneShot)
                putExtra(AlarmReceiver.EXTRA_IS_CHAIN_STEP, isChainStep)
            }
        return PendingIntent.getBroadcast(
            context,
            requestCode(rawId, snoozeOneShot, isChainStep),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
