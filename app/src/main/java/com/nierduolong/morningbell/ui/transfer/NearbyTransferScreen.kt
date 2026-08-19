package com.nierduolong.morningbell.ui.transfer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.nierduolong.morningbell.dailylog.lan.NearbySessionCoordinator
import com.nierduolong.morningbell.transfer.NearbyFileShareService
import com.nierduolong.morningbell.transfer.NearbyTransferCoordinator
import com.nierduolong.morningbell.transfer.TransferNetworkMode
import com.nierduolong.morningbell.transfer.TransferNetworkUtils
import com.nierduolong.morningbell.transfer.TransferSelection
import com.nierduolong.morningbell.transfer.TransferSelectionStore
import com.nierduolong.morningbell.transfer.estimatedBytes
import com.nierduolong.morningbell.transfer.label
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyTransferRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val selection by TransferSelectionStore.selection.collectAsState()
    val serviceState by NearbyTransferCoordinator.state.collectAsState()
    val nearbyLogState by NearbySessionCoordinator.state.collectAsState()
    var nextTreeIsRemovable by remember { mutableStateOf(false) }
    var qrMode by remember { mutableStateOf(QrMode.Download) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { TransferSelectionStore.tryPersistRead(context, it) }
        TransferSelectionStore.set(TransferSelectionStore.inspectDocuments(context, uris))
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            TransferSelectionStore.tryPersistRead(context, uri)
            TransferSelectionStore.set(TransferSelectionStore.inspectTree(context, uri, nextTreeIsRemovable))
        }
    }
    val volumePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        TransferSelectionStore.tryPersistRead(context, uri, result.data?.flags ?: Intent.FLAG_GRANT_READ_URI_PERMISSION)
        TransferSelectionStore.set(TransferSelectionStore.inspectTree(context, uri, removable = true))
    }
    val nearbyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val current = TransferSelectionStore.selection.value
        if (granted && current != null) NearbyFileShareService.start(context, current, TransferNetworkMode.AUTO_HOTSPOT)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("附近快传") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "让手机直接把文件流给同一热点或 Wi-Fi 中的电脑/手机。原片不会复制到本机缓存，也不会经过公网。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SourceCard(
                    selection = selection,
                    enabled = serviceState !is NearbyTransferCoordinator.State.Sharing && serviceState !is NearbyTransferCoordinator.State.Starting,
                    onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                    onPickFolder = {
                        nextTreeIsRemovable = false
                        treePicker.launch(null)
                    },
                    onPickVolume = {
                        nextTreeIsRemovable = true
                        launchRemovablePicker(context, volumePicker::launch) { treePicker.launch(null) }
                    },
                    onClear = { TransferSelectionStore.set(null) },
                )
            }

            when (val state = serviceState) {
                NearbyTransferCoordinator.State.Idle -> item {
                    StartCard(
                        selection = selection,
                        nearbyLogUsesHotspot = nearbyLogState is NearbySessionCoordinator.State.Hosting,
                        onAutoHotspot = {
                            val current = selection ?: return@StartCard
                            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Manifest.permission.NEARBY_WIFI_DEVICES
                            } else {
                                Manifest.permission.ACCESS_FINE_LOCATION
                            }
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                NearbyFileShareService.start(context, current, TransferNetworkMode.AUTO_HOTSPOT)
                            } else {
                                nearbyPermission.launch(permission)
                            }
                        },
                        onCurrentNetwork = {
                            selection?.let { NearbyFileShareService.start(context, it, TransferNetworkMode.CURRENT_NETWORK) }
                        },
                    )
                }

                is NearbyTransferCoordinator.State.Starting -> item {
                    StatusCard(title = "正在启动", body = state.message)
                }

                is NearbyTransferCoordinator.State.Failed -> item {
                    StatusCard(title = "未能开始分享", body = state.message)
                    TextButton(onClick = NearbyTransferCoordinator::dismissFailure) { Text("返回重试") }
                }

                is NearbyTransferCoordinator.State.Sharing -> item {
                    SharingCard(
                        state = state,
                        qrMode = qrMode,
                        onQrMode = { qrMode = it },
                        onStop = { NearbyFileShareService.stop(context) },
                    )
                }
            }

            if ((selection as? TransferSelection.Tree)?.removable == true) {
                item { CameraCardSafetyCard() }
            }

            item {
                Text(
                    "传输优化：原文件使用 128 KiB 固定缓冲和 HTTP Range；相机卡最多两路并发读取；缩略图单路生成并写入有上限的缓存。浏览器打包适合少量文件，大文件请逐个下载以获得断点续传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    selection: TransferSelection?,
    enabled: Boolean,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onPickVolume: () -> Unit,
    onClear: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("分享内容", style = MaterialTheme.typography.titleMedium)
            if (selection == null) {
                Text("还没有选择文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(selection.label(), style = MaterialTheme.typography.bodyLarge)
                val bytes = selection.estimatedBytes()
                if (bytes >= 0) Text("约 ${formatBytes(bytes)} · 不复制，直接流式分享", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClear, enabled = enabled) { Text("清除选择") }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFiles, enabled = enabled, modifier = Modifier.weight(1f)) { Text("多个文件") }
                OutlinedButton(onClick = onPickFolder, enabled = enabled, modifier = Modifier.weight(1f)) { Text("文件夹") }
            }
            Button(onClick = onPickVolume, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("相机卡 / U 盘")
            }
        }
    }
}

@Composable
private fun StartCard(
    selection: TransferSelection?,
    nearbyLogUsesHotspot: Boolean,
    onAutoHotspot: () -> Unit,
    onCurrentNetwork: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAutoHotspot, enabled = selection != null && !nearbyLogUsesHotspot, modifier = Modifier.fillMaxWidth()) {
            Text("创建无流量热点并分享")
        }
        OutlinedButton(onClick = onCurrentNetwork, enabled = selection != null, modifier = Modifier.fillMaxWidth()) {
            Text("在当前热点 / Wi-Fi 中分享")
        }
        Text(
            if (nearbyLogUsesHotspot) {
                "附近 Log 已经创建热点，请使用“当前网络分享”，两项功能可以共用这个热点。"
            } else {
                "自动热点没有互联网出口；当前网络模式适合已经打开个人热点，或所有设备已经连到同一个 Wi-Fi。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class QrMode { Download, Wifi }

@Composable
private fun SharingCard(
    state: NearbyTransferCoordinator.State.Sharing,
    qrMode: QrMode,
    onQrMode: (QrMode) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val firstUrl = state.urls.firstOrNull()
    val wifiPayload = if (state.ssid != null && state.passphrase != null) {
        TransferNetworkUtils.wifiQrPayload(state.ssid, state.passphrase)
    } else null
    val shownPayload = if (qrMode == QrMode.Wifi && wifiPayload != null) wifiPayload else firstUrl

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("正在分享 · ${state.selectionLabel}", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    state.activeClients > 0 -> "${state.activeClients} 台设备正在读取 · 已传 ${formatBytes(state.transferredBytes)}"
                    state.transferredBytes > 0 -> "等待接收设备连接 · 本次已传 ${formatBytes(state.transferredBytes)}"
                    else -> "等待接收设备连接"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (wifiPayload != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onQrMode(QrMode.Wifi) }) { Text(if (qrMode == QrMode.Wifi) "● Wi-Fi" else "Wi-Fi") }
                    TextButton(onClick = { onQrMode(QrMode.Download) }) { Text(if (qrMode == QrMode.Download) "● 下载地址" else "下载地址") }
                }
            }
            if (shownPayload != null) {
                val bitmap = remember(shownPayload) { renderQr(shownPayload, 480) }
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = if (qrMode == QrMode.Wifi) "热点二维码" else "下载地址二维码",
                    modifier = Modifier.size(220.dp).background(androidx.compose.ui.graphics.Color.White).padding(8.dp),
                )
            }
            if (state.ssid != null) {
                Text("热点：${state.ssid}", style = MaterialTheme.typography.bodyMedium)
                Text("密码：${state.passphrase.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            }
            if (state.urls.isEmpty()) {
                Text("还没有可用局域网地址，请确认热点或 Wi-Fi 已开启", color = MaterialTheme.colorScheme.error)
            } else {
                state.urls.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { firstUrl?.let { copyText(context, it) } }) { Text("复制地址") }
                    OutlinedButton(onClick = { firstUrl?.let { openUrl(context, it) } }) { Text("本机预览") }
                }
                TextButton(onClick = { firstUrl?.let { shareText(context, it) } }) { Text("分享下载地址") }
            }
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止分享") }
        }
    }
}

@Composable
private fun CameraCardSafetyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("相机卡安全提示", style = MaterialTheme.typography.titleSmall)
            Text("• 分享是只读操作，不会修改或删除卡内文件。", style = MaterialTheme.typography.bodySmall)
            Text("• 若系统不允许选择整张卡，请进入卡内 DCIM 或具体相机目录后再点“使用此文件夹”。", style = MaterialTheme.typography.bodySmall)
            Text("• 传输完成后先在这里停止分享，再从系统中弹出存储，最后拔读卡器。", style = MaterialTheme.typography.bodySmall)
            Text("• 低质量读卡器可能发热或断连；大文件请逐个下载并保持手机供电。", style = MaterialTheme.typography.bodySmall)
            Text("• 不建议边传输边让相机或其他设备写同一张卡。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun launchRemovablePicker(
    context: Context,
    launch: (Intent) -> Unit,
    fallback: () -> Unit,
) {
    val storage = context.getSystemService(StorageManager::class.java)
    val volumes = storage.storageVolumes.filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
    val volume = volumes.singleOrNull()
    if (volume == null) {
        if (volumes.isEmpty()) Toast.makeText(context, "未检测到相机卡或 U 盘，请插入后在系统页面中选择", Toast.LENGTH_LONG).show()
        fallback()
        return
    }
    val intent = volumeTreeIntent(volume)
    if (intent == null) {
        fallback()
    } else {
        Toast.makeText(context, "请进入相机卡的 DCIM 或具体相机目录，再点“使用此文件夹”", Toast.LENGTH_LONG).show()
        launch(intent)
    }
}

@Suppress("DEPRECATION")
private fun volumeTreeIntent(volume: StorageVolume): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) volume.createOpenDocumentTreeIntent() else volume.createAccessIntent(null)

private fun copyText(context: Context, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("附近快传地址", value))
    Toast.makeText(context, "地址已复制", Toast.LENGTH_SHORT).show()
}

private fun openUrl(context: Context, value: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
        .onFailure { Toast.makeText(context, "没有可打开网页的应用", Toast.LENGTH_SHORT).show() }
}

private fun shareText(context: Context, value: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, value)
    }
    context.startActivity(Intent.createChooser(intent, "分享附近快传地址"))
}

private fun renderQr(value: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1),
    )
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    val row = IntArray(size)
    for (y in 0 until size) {
        for (x in 0 until size) row[x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        bitmap.setPixels(row, 0, size, 0, y, size, 1)
    }
    return bitmap
}

private fun formatBytes(bytes: Long): String {
    var value = bytes.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    for (unit in units) {
        if (value < 1024 || unit == units.last()) return if (unit == "B") "$bytes B" else String.format(Locale.US, "%.1f %s", value, unit)
        value /= 1024
    }
    return "$bytes B"
}
