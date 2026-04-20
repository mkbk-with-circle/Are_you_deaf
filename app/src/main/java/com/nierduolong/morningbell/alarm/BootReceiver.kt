package com.nierduolong.morningbell.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.unlock.WakeTrackStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        WakeTrackStarter.ensureRunning(context)
        val app = context.applicationContext as MorningBellApp
        CoroutineScope(Dispatchers.IO).launch {
            app.repository.rescheduleAllEnabled()
        }
    }
}
