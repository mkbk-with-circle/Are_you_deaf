package com.nierduolong.morningbell.dailylog.lan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nierduolong.morningbell.MainActivity
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.R
import kotlinx.coroutines.runBlocking
import com.nierduolong.morningbell.transfer.NearbyTransferCoordinator

/**
 * 持有 LocalOnlyHotspot reservation 和内嵌 HTTP 服务。只要通知仍在，热点就不会因为页面退出
 * 被释放；用户从通知或页面停止后会立即关闭 socket 与热点。
 */
class NearbyLogHostService : Service() {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: LanHostServer? = null
    private var advertiser: LanNsdAdvertiser? = null
    private var activeLogId: Long? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopHosting()
            stopSelf()
            return START_NOT_STICKY
        }
        val logId = intent?.getLongExtra(EXTRA_LOG_ID, -1L) ?: -1L
        val inviteCode = intent?.getStringExtra(EXTRA_INVITE_CODE).orEmpty()
        if (logId <= 0 || inviteCode.length != 6) {
            NearbySessionCoordinator.update(NearbySessionCoordinator.State.Failed("附近 Log 参数无效"))
            stopSelf()
            return START_NOT_STICKY
        }
        val transferState = NearbyTransferCoordinator.state.value
        if (transferState is NearbyTransferCoordinator.State.Sharing && transferState.ssid != null) {
            NearbySessionCoordinator.update(
                NearbySessionCoordinator.State.Failed("附近快传正在使用本地热点；请先停止快传，或让快传复用附近 Log 热点"),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(notification("正在创建无外网热点…"))
        if (activeLogId == logId && reservation != null) return START_NOT_STICKY
        stopHosting(updateState = false)
        activeLogId = logId
        NearbySessionCoordinator.update(NearbySessionCoordinator.State.Starting(logId))
        requestHotspot(logId, inviteCode)
        return START_NOT_STICKY
    }

    private fun requestHotspot(
        logId: Long,
        inviteCode: String,
    ) {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = value
                        val credentials = credentials(value)
                        if (credentials == null) {
                            fail("系统没有返回热点凭据")
                            return
                        }
                        val app = application as MorningBellApp
                        val log = runBlocking { app.repository.getDailyLog(logId) }
                        if (log?.remoteId == null) {
                            fail("附近 Log 不存在")
                            return
                        }
                        val hostServer = LanHostServer(applicationContext, app.repository, logId, inviteCode)
                        runCatching { hostServer.start() }.onFailure {
                            hostServer.close()
                            fail("局域网服务启动失败：${it.message ?: "未知错误"}")
                            return
                        }
                        server = hostServer
                        advertiser = LanNsdAdvertiser(this@NearbyLogHostService).also {
                            runCatching { it.register(hostServer.port, log.remoteId) }
                        }
                        NearbySessionCoordinator.update(
                            NearbySessionCoordinator.State.Hosting(
                                logId = logId,
                                ssid = credentials.first,
                                passphrase = credentials.second,
                                inviteCode = inviteCode,
                                port = hostServer.port,
                            ),
                        )
                        val manager = getSystemService(NotificationManager::class.java)
                        manager.notify(NOTIFICATION_ID, notification("附近 Log 正在运行 · ${credentials.first}"))
                    }

                    override fun onStopped() {
                        stopHosting()
                        stopSelf()
                    }

                    override fun onFailed(reason: Int) {
                        fail("无法创建本地热点（错误 $reason）")
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: SecurityException) {
            fail("缺少附近设备权限")
        } catch (e: Exception) {
            fail("无法创建本地热点：${e.message ?: "未知错误"}")
        }
    }

    @Suppress("DEPRECATION")
    private fun credentials(value: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = value.softApConfiguration
            val ssid = config.ssid ?: return null
            ssid to config.passphrase.orEmpty()
        } else {
            val config = value.wifiConfiguration ?: return null
            val ssid = config.SSID?.trim('"') ?: return null
            ssid to config.preSharedKey.orEmpty().trim('"')
        }
    }

    private fun fail(message: String) {
        stopHosting(updateState = false)
        NearbySessionCoordinator.update(NearbySessionCoordinator.State.Failed(message))
        stopSelf()
    }

    private fun stopHosting(updateState: Boolean = true) {
        advertiser?.close()
        advertiser = null
        server?.close()
        server = null
        reservation?.close()
        reservation = null
        activeLogId = null
        if (updateState) NearbySessionCoordinator.update(NearbySessionCoordinator.State.Idle)
    }

    private fun startInForeground(value: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            value,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun notification(text: String): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, NearbyLogHostService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("附近 Log")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "附近 Log 局域网服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "保持无外网热点和局域网视频流" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopHosting()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nearby_log_host"
        private const val NOTIFICATION_ID = 42031
        private const val ACTION_START = "com.nierduolong.morningbell.nearby.START"
        private const val ACTION_STOP = "com.nierduolong.morningbell.nearby.STOP"
        private const val EXTRA_LOG_ID = "log_id"
        private const val EXTRA_INVITE_CODE = "invite_code"

        fun start(
            context: Context,
            logId: Long,
            inviteCode: String,
        ) {
            val intent =
                Intent(context, NearbyLogHostService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_LOG_ID, logId)
                    .putExtra(EXTRA_INVITE_CODE, inviteCode)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NearbyLogHostService::class.java).setAction(ACTION_STOP))
        }
    }
}
