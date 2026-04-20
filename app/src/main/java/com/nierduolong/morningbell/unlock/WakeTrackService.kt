package com.nierduolong.morningbell.unlock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nierduolong.morningbell.MainActivity
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：保持进程存活以接收 [Intent.ACTION_USER_PRESENT]，写入「统计起始时刻之后当日首次解锁」。
 * 仅靠 Application 动态注册时，进程被杀后无法记录；START_STICKY 在内存紧张被杀后由系统择机重启。
 */
class WakeTrackService : Service() {
    private var receiverRegistered = false

    private val unlockReceiver =
        object : BroadcastReceiver() {
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        promoteToForeground()
        registerUnlock()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        promoteToForeground()
        if (!receiverRegistered) {
            registerUnlock()
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.wake_track_notif_title))
                .setContentText(getString(R.string.wake_track_notif_text))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.wake_track_notif_big)),
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setContentIntent(openApp)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun registerUnlock() {
        if (receiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(unlockReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            receiverRegistered = false
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_track_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.wake_track_channel_desc)
                setShowBadge(false)
            }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "wake_track_fg"
        private const val NOTIFICATION_ID = 71012
    }
}
