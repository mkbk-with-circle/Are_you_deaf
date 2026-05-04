package com.nierduolong.morningbell.unlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 用 AlarmManager 在「被系统结束后」自恢复、并周期性校准前台服务，减轻一夜深睡/省电后进程消失的问题。
 * 注：无厂商白名单/忽略电池时仍无法保证 100% 杀不死；自启动+忽略电池+勿强制停止 仍是根本。
 */
object WakeTrackKeepAliveAlarms {
    const val ACTION_RESTART = "com.nierduolong.morningbell.WAKE_TRACK_RESTART"
    const val ACTION_RECHECK = "com.nierduolong.morningbell.WAKE_TRACK_RECHECK"

    private const val TAG = "WakeTrackKeepAlive"
    private const val RC_RESTART = 71021
    private const val RC_RECHECK = 71022
    private const val RESTART_DELAY_MS = 1_500L
    /** 长间隔抽查：即便进程曾被杀，若闹钟仍在（未被强制停止），有机会重新拉起 FGS */
    private const val RECHECK_INTERVAL_MS = 2L * 60 * 60 * 1_000L

    private fun restartIntent(ctx: Context) =
        Intent(ctx, WakeTrackAlarmReceiver::class.java).setAction(ACTION_RESTART)

    private fun recheckIntent(ctx: Context) =
        Intent(ctx, WakeTrackAlarmReceiver::class.java).setAction(ACTION_RECHECK)

    private fun pRestart(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            RC_RESTART,
            restartIntent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pRecheck(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            RC_RECHECK,
            recheckIntent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun cancelRestartAlarm(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(
            pRestart(ctx.applicationContext),
        )
    }

    /**
     * 服务 [Service.onDestroy] 时：短延迟再尝试 [WakeTrackStarter.ensureRunning]。
     */
    fun scheduleRestartAfterStop(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val app = ctx.applicationContext
        val at = System.currentTimeMillis() + RESTART_DELAY_MS
        val pi = pRestart(app)
        try {
            scheduleShortExactish(am, at, pi)
        } catch (e: Exception) {
            Log.w(TAG, "scheduleRestartAfterStop: $e")
        }
    }

    /**
     * 服务存活时排下一次远程校准（链式，避免依赖进程内 Handler）。
     */
    fun schedulePeriodicRecheck(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val app = ctx.applicationContext
        val at = System.currentTimeMillis() + RECHECK_INTERVAL_MS
        val pi = pRecheck(app)
        try {
            // 2h 不需要秒级准确，inexact/allowWhileIdle 即可穿透部分 Doze
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "schedulePeriodicRecheck: $e")
        }
    }

    private fun scheduleShortExactish(
        am: AlarmManager,
        at: Long,
        pi: PendingIntent,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
            return
        }
        // API 31+ 无「精确闹钟」时 setExact 会抛；退回普通 set
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            try {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: Exception) {
                Log.w(TAG, "scheduleShortExactish fallback: $e")
            }
            return
        }
        am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
    }
}
