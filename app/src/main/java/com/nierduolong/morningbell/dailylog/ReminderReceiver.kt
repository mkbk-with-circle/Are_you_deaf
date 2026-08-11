package com.nierduolong.morningbell.dailylog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nierduolong.morningbell.MainActivity
import com.nierduolong.morningbell.R

/** Setlog 功能 2：提醒到达 -> 发通知，点击直达拍摄页；随后自续期下一次 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != ACTION_FIRE) return
        ensureChannel(context)
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_OPEN_CAPTURE, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(context.getString(R.string.dailylog_reminder_title))
                .setContentText(context.getString(R.string.dailylog_reminder_body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        // 通知被用户关掉时 notify 只是静默失败，但后续排期不能受影响
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
        }
        ReminderScheduler.scheduleFollowing(context)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.dailylog_reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.dailylog_reminder_channel_desc)
            },
        )
    }

    companion object {
        const val ACTION_FIRE = "com.nierduolong.morningbell.ACTION_DAILY_LOG_REMINDER"
        private const val CHANNEL_ID = "daily_log_reminder"
        private const val NOTIFICATION_ID = 0x5E7107
    }
}
