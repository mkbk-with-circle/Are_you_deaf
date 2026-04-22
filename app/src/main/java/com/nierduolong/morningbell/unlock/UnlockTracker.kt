package com.nierduolong.morningbell.unlock

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.nierduolong.morningbell.MorningBellApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 动态监听解锁，写入「6 点后首次解锁」起床时间（需系统允许后台广播） */
object UnlockTracker {
    fun register(app: Application) {
        val f = IntentFilter(Intent.ACTION_USER_PRESENT)
        app.registerReceiver(Receiver, f)
    }

    private object Receiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent?,
        ) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return
            val app = context.applicationContext as MorningBellApp
            CoroutineScope(Dispatchers.IO).launch {
                app.repository.recordUnlockIfMorning()
            }
        }
    }
}
