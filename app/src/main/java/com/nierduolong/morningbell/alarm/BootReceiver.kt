package com.nierduolong.morningbell.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.dailylog.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as MorningBellApp
        // 开机后系统清空了全部闹钟，日志拍摄提醒也要一起补排，否则会静默失效
        ReminderScheduler.rescheduleAfterBoot(context)
        CoroutineScope(Dispatchers.IO).launch {
            app.repository.rescheduleAllEnabled()
        }
    }
}
