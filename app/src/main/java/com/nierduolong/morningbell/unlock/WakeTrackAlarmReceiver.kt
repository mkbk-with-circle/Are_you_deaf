package com.nierduolong.morningbell.unlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 承接 [WakeTrackKeepAliveAlarms] 的定时意图：短延迟自恢复、长间隔保活检查。
 */
class WakeTrackAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        when (intent?.action) {
            WakeTrackKeepAliveAlarms.ACTION_RESTART -> {
                Log.d(TAG, "ACTION_RESTART")
                WakeTrackStarter.ensureRunning(context)
            }
            WakeTrackKeepAliveAlarms.ACTION_RECHECK -> {
                Log.d(TAG, "ACTION_RECHECK")
                WakeTrackStarter.ensureRunning(context)
                WakeTrackKeepAliveAlarms.schedulePeriodicRecheck(context.applicationContext)
            }
        }
    }

    private companion object {
        private const val TAG = "WakeTrackAlarmReceiver"
    }
}
