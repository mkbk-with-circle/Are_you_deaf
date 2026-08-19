package com.nierduolong.morningbell.dailylog.lan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nierduolong.morningbell.MainActivity
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 让成员切到后台后仍可把自己拍摄的视频流给主机；不创建副本，只保持只读 socket。 */
class NearbyLogPeerService : Service() {
    private var sourceServer: LanPeerSourceServer? = null
    private var activeLogId: Long? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "附近 Log 素材共享", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSource()
            stopSelf()
            return START_NOT_STICKY
        }
        val logId = intent?.getLongExtra(EXTRA_LOG_ID, -1L) ?: -1L
        val inviteCode = intent?.getStringExtra(EXTRA_INVITE_CODE).orEmpty()
        if (logId <= 0 || inviteCode.length != 6) {
            NearbyPeerCoordinator.update(NearbyPeerCoordinator.State.Failed("素材共享参数无效"))
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (activeLogId == logId && sourceServer != null) return START_NOT_STICKY
        stopSource(updateState = false)
        val app = application as MorningBellApp
        val server = runCatching { LanPeerSourceServer(applicationContext, app.repository, logId, inviteCode).also { it.start() } }
        server.onSuccess {
            sourceServer = it
            activeLogId = logId
            NearbyPeerCoordinator.update(NearbyPeerCoordinator.State.Ready(logId, it.port))
            syncJob = serviceScope.launch {
                while (isActive && activeLogId == logId) {
                    runCatching {
                        NearbySyncManager.publishAndPullDay(
                            applicationContext,
                            app.repository,
                            logId,
                            LocalDate.now().toEpochDay(),
                        )
                    }
                    delay(SYNC_INTERVAL_MS)
                }
            }
        }.onFailure {
            NearbyPeerCoordinator.update(NearbyPeerCoordinator.State.Failed(it.message ?: "素材共享启动失败"))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, NearbyLogPeerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("附近 Log 素材可用")
                .setContentText("只在当前热点中按需流式发送，不会上传云端")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "停止共享", stop)
                .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    private fun stopSource(updateState: Boolean = true) {
        syncJob?.cancel()
        syncJob = null
        sourceServer?.close()
        sourceServer = null
        activeLogId = null
        if (updateState) NearbyPeerCoordinator.update(NearbyPeerCoordinator.State.Idle)
    }

    override fun onDestroy() {
        stopSource()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nearby_log_peer"
        private const val NOTIFICATION_ID = 42032
        private const val SYNC_INTERVAL_MS = 10_000L
        private const val ACTION_START = "com.nierduolong.morningbell.nearby.PEER_START"
        private const val ACTION_STOP = "com.nierduolong.morningbell.nearby.PEER_STOP"
        private const val EXTRA_LOG_ID = "log_id"
        private const val EXTRA_INVITE_CODE = "invite_code"

        fun start(context: Context, logId: Long, inviteCode: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NearbyLogPeerService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_LOG_ID, logId)
                    .putExtra(EXTRA_INVITE_CODE, inviteCode),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NearbyLogPeerService::class.java).setAction(ACTION_STOP))
        }
    }
}
