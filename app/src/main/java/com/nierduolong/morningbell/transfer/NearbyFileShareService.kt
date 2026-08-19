package com.nierduolong.morningbell.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nierduolong.morningbell.MainActivity
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.dailylog.lan.NearbySessionCoordinator
import java.util.Locale

class NearbyFileShareService : Service(), NearbyFileShareServer.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: NearbyFileShareServer? = null
    private var catalog: TransferSourceCatalog? = null
    private var activeSession: TransferSessionStore.Session? = null
    private var ssid: String? = null
    private var passphrase: String? = null
    private var activeClients = 0
    private var transferredBytes = 0L
    private var lastNetworkFingerprint = ""
    private var lastNotificationBucket = -1L
    private var wakeLock: PowerManager.WakeLock? = null
    private var preserveFailureOnDestroy = false

    private val networkWatcher = object : Runnable {
        override fun run() {
            val currentServer = server ?: return
            val session = activeSession ?: return
            val fingerprint = TransferNetworkUtils.fingerprint()
            if (fingerprint != lastNetworkFingerprint) {
                lastNetworkFingerprint = fingerprint
                publishSharing(currentServer, session)
            }
            if (System.currentTimeMillis() - session.startedAt >= MAX_SESSION_AGE_MS) {
                fail("分享已运行 12 小时并自动停止；需要时可重新开启")
                return
            }
            handler.postDelayed(this, NETWORK_CHECK_MS)
        }
    }

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val current = catalog ?: return
            if (!current.removable) return
            // USB_DEVICE_DETACHED 也可能只是拔了键盘；稍等 provider 更新后再探活，避免误停。
            handler.postDelayed({
                if (catalog === current && !current.isReadable()) {
                    fail("相机卡或 U 盘已拔出，分享已安全停止")
                }
            }, STORAGE_RECHECK_MS)
        }
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = storageReceiver.onReceive(context, intent)
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        val usb = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        runCatching { ContextCompat.registerReceiver(this, storageReceiver, media, ContextCompat.RECEIVER_EXPORTED) }
        runCatching { ContextCompat.registerReceiver(this, usbReceiver, usb, ContextCompat.RECEIVER_EXPORTED) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(notification("正在准备附近快传…"))
        val session = if (intent == null) {
            TransferSessionStore.load(this)
        } else {
            val selection = TransferSelectionCodec.decode(intent.getStringExtra(EXTRA_SELECTION))
            val mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { TransferNetworkMode.valueOf(it) }.getOrNull() }
            if (selection == null || mode == null) null else TransferSessionStore.Session(
                selection = selection,
                mode = mode,
                token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf { it.length >= 24 } ?: TransferNetworkUtils.newAccessToken(),
                startedAt = System.currentTimeMillis(),
            )
        }
        if (session == null) {
            fail("没有可恢复的分享内容")
            return START_NOT_STICKY
        }
        startSession(session)
        return START_STICKY
    }

    private fun startSession(session: TransferSessionStore.Session) {
        preserveFailureOnDestroy = false
        stopSharing(clearStored = false, publishIdle = false)
        NearbyTransferCoordinator.update(NearbyTransferCoordinator.State.Starting("正在检查所选内容…"))
        val createdCatalog = createCatalog(session.selection)
        if (!createdCatalog.isReadable()) {
            fail("所选文件无法读取；如果是相机卡，请重新插入并授权")
            return
        }
        if (session.mode == TransferNetworkMode.AUTO_HOTSPOT && NearbySessionCoordinator.state.value is NearbySessionCoordinator.State.Hosting) {
            fail("附近 Log 正在使用本地热点；可改选“当前网络分享”复用该热点")
            return
        }
        catalog = createdCatalog
        activeSession = session
        TransferSessionStore.save(this, session)
        acquireWakeLock()
        if (session.mode == TransferNetworkMode.AUTO_HOTSPOT) requestHotspot(session) else startServer(session)
    }

    private fun requestHotspot(session: TransferSessionStore.Session) {
        NearbyTransferCoordinator.update(NearbyTransferCoordinator.State.Starting("正在创建不耗流量的本地热点…"))
        val wifi = getSystemService(WifiManager::class.java)
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        if (activeSession !== session) {
                            reservation.close()
                            return
                        }
                        hotspotReservation = reservation
                        val credentials = hotspotCredentials(reservation)
                        if (credentials == null) {
                            fail("系统没有返回热点名称或密码")
                            return
                        }
                        ssid = credentials.first
                        passphrase = credentials.second
                        startServer(session)
                    }

                    override fun onStopped() {
                        if (activeSession === session) fail("本地热点已被系统关闭")
                    }

                    override fun onFailed(reason: Int) {
                        if (activeSession === session) fail("无法创建本地热点（错误 $reason）；可尝试使用当前网络分享")
                    }
                },
                handler,
            )
        } catch (_: SecurityException) {
            fail("缺少附近设备权限，无法自动创建热点")
        } catch (error: Exception) {
            fail("创建热点失败：${error.message ?: "未知错误"}")
        }
    }

    private fun startServer(session: TransferSessionStore.Session) {
        val currentCatalog = catalog ?: return fail("分享内容已失效")
        val created = NearbyFileShareServer(this, currentCatalog, session.token, this)
        runCatching { created.start() }.onFailure {
            created.close()
            fail("文件服务启动失败：${it.message ?: "未知错误"}")
            return
        }
        server = created
        lastNetworkFingerprint = TransferNetworkUtils.fingerprint()
        publishSharing(created, session)
        handler.removeCallbacks(networkWatcher)
        handler.postDelayed(networkWatcher, 1_000L)
    }

    private fun createCatalog(selection: TransferSelection): TransferSourceCatalog = when (selection) {
        is TransferSelection.Documents -> DocumentTransferCatalog(contentResolver, selection.items)
        is TransferSelection.Tree -> TreeTransferCatalog(
            resolver = contentResolver,
            treeUri = Uri.parse(selection.uri),
            label = selection.label,
            removable = selection.removable,
        )
    }

    private fun publishSharing(currentServer: NearbyFileShareServer, session: TransferSessionStore.Session) {
        val urls = TransferNetworkUtils.shareUrls(currentServer.port, currentServer.sharePath())
        val value = NearbyTransferCoordinator.State.Sharing(
            selectionLabel = session.selection.label(),
            urls = urls,
            ssid = ssid,
            passphrase = passphrase,
            startedAt = session.startedAt,
            activeClients = activeClients,
            transferredBytes = transferredBytes,
        )
        NearbyTransferCoordinator.update(value)
        val summary = when {
            urls.isEmpty() -> "服务已启动，等待热点或 Wi-Fi 地址…"
            activeClients > 0 -> "$activeClients 台设备正在读取 · ${humanBytes(transferredBytes)}"
            transferredBytes > 0 -> "等待接收设备 · 本次已传 ${humanBytes(transferredBytes)}"
            else -> "等待接收设备 · ${session.selection.label()}"
        }
        val bucket = transferredBytes / NOTIFICATION_UPDATE_BYTES
        if (bucket != lastNotificationBucket || activeClients == 0) {
            lastNotificationBucket = bucket
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(summary))
        }
    }

    override fun onMetrics(activeClients: Int, transferredBytes: Long) {
        handler.post {
            this.activeClients = activeClients
            this.transferredBytes = transferredBytes
            val currentServer = server ?: return@post
            val session = activeSession ?: return@post
            publishSharing(currentServer, session)
        }
    }

    override fun onSourceUnavailable(message: String) {
        handler.post {
            val current = catalog ?: return@post
            if (!current.isReadable()) fail(message)
        }
    }

    private fun fail(message: String) {
        preserveFailureOnDestroy = true
        stopSharing(clearStored = true, publishIdle = false)
        NearbyTransferCoordinator.update(NearbyTransferCoordinator.State.Failed(message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSharing(clearStored: Boolean = true, publishIdle: Boolean = true) {
        handler.removeCallbacks(networkWatcher)
        val oldReservation = hotspotReservation
        hotspotReservation = null
        activeSession = null
        server?.close()
        server = null
        oldReservation?.close()
        catalog = null
        ssid = null
        passphrase = null
        activeClients = 0
        transferredBytes = 0
        lastNotificationBucket = -1
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (clearStored) TransferSessionStore.clear(this)
        if (publishIdle) NearbyTransferCoordinator.update(NearbyTransferCoordinator.State.Idle)
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "morningbell:nearby-transfer").apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_AGE_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun hotspotCredentials(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = reservation.softApConfiguration
            val name = config.ssid ?: return null
            name to config.passphrase.orEmpty()
        } else {
            val config = reservation.wifiConfiguration ?: return null
            config.SSID?.trim('"')?.let { it to config.preSharedKey.orEmpty().trim('"') }
        }

    private fun startForegroundCompat(value: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            value,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            MainActivity.openNearbyTransferIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NearbyFileShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("附近快传")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "停止分享", stop)
            .build()
    }

    private fun ensureNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "附近文件分享", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持无外网热点和大文件流式传输"
            },
        )
    }

    override fun onDestroy() {
        stopSharing(publishIdle = !preserveFailureOnDestroy)
        runCatching { unregisterReceiver(storageReceiver) }
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nearby_file_share"
        private const val NOTIFICATION_ID = 42041
        private const val ACTION_START = "com.nierduolong.morningbell.transfer.START"
        private const val ACTION_STOP = "com.nierduolong.morningbell.transfer.STOP"
        private const val EXTRA_SELECTION = "selection"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TOKEN = "token"
        private const val NETWORK_CHECK_MS = 3_000L
        private const val STORAGE_RECHECK_MS = 1_200L
        private const val MAX_SESSION_AGE_MS = 12L * 60 * 60 * 1000
        private const val NOTIFICATION_UPDATE_BYTES = 64L * 1024 * 1024

        fun start(context: Context, selection: TransferSelection, mode: TransferNetworkMode) {
            val intent = Intent(context, NearbyFileShareService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SELECTION, TransferSelectionCodec.encode(selection))
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_TOKEN, TransferNetworkUtils.newAccessToken())
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NearbyFileShareService::class.java).setAction(ACTION_STOP))
        }

        private fun humanBytes(bytes: Long): String {
            var value = bytes.toDouble()
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            for (unit in units) {
                if (value < 1024 || unit == units.last()) {
                    return if (unit == "B") "$bytes B" else String.format(Locale.US, "%.1f %s", value, unit)
                }
                value /= 1024
            }
            return "$bytes B"
        }
    }
}
