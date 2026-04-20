package com.nierduolong.morningbell.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nierduolong.morningbell.MainActivity
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 前台播放闹钟音频；无声 + 震动；有声也可叠加震动 */
class AlarmRingService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var prepJob: Job? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureChannel()
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: return START_NOT_STICKY
        val isChainStep = intent.getBooleanExtra(EXTRA_IS_CHAIN_STEP, false)
        val tap =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, AlarmRingActivity::class.java).apply {
                    putExtra(AlarmRingActivity.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmRingActivity.EXTRA_IS_CHAIN_STEP, isChainStep)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.alarm_channel_desc))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(tap)
                .setOngoing(true)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        prepJob?.cancel()
        prepJob =
            scope.launch(Dispatchers.IO) {
                val app = applicationContext as MorningBellApp
                val profile = app.repository.getAlarmRingProfile(alarmId, isChainStep)
                startRinging(profile)
            }
        return START_STICKY
    }

    private fun startRinging(profile: com.nierduolong.morningbell.data.AppRepository.AlarmRingProfile?) {
        stopRingingInternal()
        val silent = profile?.silent == true
        val vibrate = profile?.vibrate != false
        if (silent && !vibrate) {
            return
        }
        if (silent) {
            vibratePattern(longArrayOf(0, 280, 120, 280, 120, 500), repeatFromIndex = 2)
            return
        }

        val mp = MediaPlayer()
        player = mp
        val attrs =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        mp.setAudioAttributes(attrs)
        mp.isLooping = true

        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val customUri =
            profile?.soundUri
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }

        fun applyAndPlay(uri: Uri): Boolean =
            try {
                mp.reset()
                mp.setDataSource(this@AlarmRingService, uri)
                mp.prepare()
                mp.start()
                true
            } catch (_: Exception) {
                false
            }

        val okCustom = customUri != null && applyAndPlay(customUri)
        if (!okCustom) {
            applyAndPlay(defaultUri)
        }
        if (vibrate) {
            vibratePattern(longArrayOf(0, 500, 400, 500), repeatFromIndex = 0)
        }
    }

    private fun vibratePattern(
        pattern: LongArray,
        repeatFromIndex: Int,
    ) {
        val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        vibrator = v
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(
                VibrationEffect.createWaveform(pattern, repeatFromIndex),
            )
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, repeatFromIndex)
        }
    }

    private fun stopRingingInternal() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
    }

    override fun onDestroy() {
        prepJob?.cancel()
        stopRingingInternal()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val ch =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.alarm_channel_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        mgr.createNotificationChannel(ch)
    }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_IS_CHAIN_STEP = "extra_is_chain_step"
        private const val CHANNEL_ID = "alarm_ring_fg"
        private const val NOTIFICATION_ID = 71011

        fun start(
            context: Context,
            alarmId: Long,
            isChainStep: Boolean,
        ) {
            val i =
                Intent(context, AlarmRingService::class.java).apply {
                    putExtra(EXTRA_ALARM_ID, alarmId)
                    putExtra(EXTRA_IS_CHAIN_STEP, isChainStep)
                }
            ContextCompat.startForegroundService(context, i)
        }
    }
}
