package com.nierduolong.morningbell.ui.dailylog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.LogMemberEntity
import com.nierduolong.morningbell.data.db.DailyLogEntity
import com.nierduolong.morningbell.dailylog.lan.DeviceIdentity
import com.nierduolong.morningbell.dailylog.lan.LanEndpoint
import com.nierduolong.morningbell.dailylog.lan.LanHostDiscovery
import com.nierduolong.morningbell.dailylog.lan.NearbyConnectionManager
import com.nierduolong.morningbell.dailylog.lan.NearbyAutoConnector
import com.nierduolong.morningbell.dailylog.lan.NearbyInvite
import com.nierduolong.morningbell.dailylog.lan.NearbyLogHostService
import com.nierduolong.morningbell.dailylog.lan.NearbyMemberReadiness
import com.nierduolong.morningbell.dailylog.lan.NearbyMemberStatus
import com.nierduolong.morningbell.dailylog.lan.NearbyPendingInvite
import com.nierduolong.morningbell.dailylog.lan.NearbySessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.security.SecureRandom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyLogRoute(
    repo: AppRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by NearbySessionCoordinator.state.collectAsState()
    val logs by repo.dailyLogsFlow.collectAsState(initial = emptyList())
    val currentLogId by repo.currentLogIdFlow.collectAsState()
    val nickname by repo.nicknameFlow.collectAsState()
    val discovery = remember { LanHostDiscovery(context.applicationContext) }
    val discoveryState by discovery.state.collectAsState()
    val autoConnection by NearbyAutoConnector.state.collectAsState()
    val activeLog = remember(logs, currentLogId) { logs.firstOrNull { it.id == currentLogId } }
    val members by
        remember(currentLogId) {
            currentLogId?.let(repo::logMembersFlow) ?: flowOf(emptyList())
        }.collectAsState(initial = emptyList())
    val today = rememberTodayEpochDay()
    val todayClips by
        remember(currentLogId, today) {
            currentLogId?.let { repo.clipsForDayFlow(it, today) } ?: flowOf(emptyList())
        }.collectAsState(initial = emptyList())
    val visibleDiscoveryState =
        when (val automatic = autoConnection) {
            NearbyAutoConnector.State.Searching ->
                discoveryState.takeIf { it is LanHostDiscovery.State.Found } ?: LanHostDiscovery.State.Searching
            is NearbyAutoConnector.State.FoundUnjoined -> LanHostDiscovery.State.Found(automatic.endpoint)
            else -> discoveryState
        }
    var inviteCode by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var pendingInvite by remember { mutableStateOf(NearbyPendingInvite.peek(context)) }
    val sharedPendingInvite by NearbyPendingInvite.state.collectAsState()
    var showScanner by remember { mutableStateOf(false) }

    DisposableEffect(discovery) {
        onDispose { discovery.close() }
    }

    fun beginHosting() {
        scope.launch {
            val identity = withContext(Dispatchers.IO) { DeviceIdentity.getOrCreate() }
            val selected = currentLogId?.let { repo.getDailyLog(it) }
            val log =
                selected?.takeIf { it.role == "owner" && it.remoteId != null && it.inviteCode != null }
                    ?: repo.createNearbyDailyLog(
                        name = "$nickname 的附近日志",
                        hostDeviceId = identity.deviceId,
                        inviteCode = newInviteCode(),
                    )
            repo.upsertLogMember(
                LogMemberEntity(
                    logId = log.id,
                    authorId = identity.deviceId,
                    nickname = nickname,
                    publicKey = identity.publicKeyBase64,
                    avatarSeed = identity.deviceId.take(12),
                ),
            )
            NearbyLogHostService.start(context, log.id, requireNotNull(log.inviteCode))
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) beginHosting()
        }
    val searchPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) discovery.start()
        }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showScanner = true else joinError = "需要相机权限才能扫描邀请二维码，也可以继续手动输入邀请码"
        }

    fun requestAndBegin() {
        val required =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        if (required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            beginHosting()
        } else {
            permissionLauncher.launch(required)
        }
    }

    fun requestSearch() {
        if (NearbyAutoConnector.state.value is NearbyAutoConnector.State.Searching ||
            NearbyAutoConnector.state.value is NearbyAutoConnector.State.FoundUnjoined
        ) return
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            discovery.start()
        } else {
            searchPermissionLauncher.launch(arrayOf(permission))
        }
    }

    fun requestScanner() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun openWifiSettings() {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        runCatching { context.startActivity(Intent(action)) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    fun join(endpoint: LanEndpoint) {
        if (inviteCode.length != 6 || joining) return
        joining = true
        joinError = null
        scope.launch {
            val result =
                runCatching {
                    val identity = withContext(Dispatchers.IO) { DeviceIdentity.getOrCreate() }
                    NearbyConnectionManager.connect(
                        context = context,
                        repository = repo,
                        endpoint = endpoint,
                        inviteCode = inviteCode,
                        identity = identity,
                        nickname = nickname,
                        expectedRemoteLogId = pendingInvite?.remoteLogId,
                    )
                }
            result.onSuccess {
                NearbyPendingInvite.clear(context)
                pendingInvite = null
                discovery.close()
                onBack()
            }.onFailure {
                joinError = connectionErrorMessage(it)
                NearbyAutoConnector.markFailed(joinError ?: "加入失败")
            }
            joining = false
        }
    }

    LaunchedEffect(discoveryState) {
        if (discoveryState is LanHostDiscovery.State.Searching) {
            delay(12_000L)
            if (discovery.state.value is LanHostDiscovery.State.Searching) {
                discovery.stopWithFailure("没有在当前 Wi-Fi 中找到附近 Log")
            }
        }
    }

    LaunchedEffect(sharedPendingInvite) {
        val shared = sharedPendingInvite ?: return@LaunchedEffect
        if (shared != pendingInvite) {
            pendingInvite = shared
            inviteCode = shared.inviteCode
            joinError = null
        }
        requestSearch()
    }

    LaunchedEffect(pendingInvite, visibleDiscoveryState, joining) {
        val pending = pendingInvite ?: return@LaunchedEffect
        val found = visibleDiscoveryState as? LanHostDiscovery.State.Found ?: return@LaunchedEffect
        if (!joining && inviteCode == pending.inviteCode) join(found.endpoint)
    }

    if (showScanner) {
        NearbyInviteScannerDialog(
            onDecoded = { invite ->
                showScanner = false
                pendingInvite = invite
                inviteCode = invite.inviteCode
                joinError = null
                NearbyPendingInvite.save(context, invite)
                requestSearch()
            },
            onDismiss = { showScanner = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("附近 Log") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "由这台手机创建无外网热点。视频默认留在拍摄者手机，播放时只按需流式读取；主机不保存第二份完整原片。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                NearbyConnectionStatusCard(
                    session = session,
                    autoConnection = autoConnection,
                    discoveryState = visibleDiscoveryState,
                    activeLog = activeLog,
                    onRetry = {
                        if (activeLog?.role == "member") {
                            scope.launch { NearbyAutoConnector.reconnectKnownLog(context, repo) }
                        } else {
                            requestSearch()
                        }
                    },
                    onOpenWifi = ::openWifiSettings,
                )
            }

            item {
                when (val value = session) {
                    NearbySessionCoordinator.State.Idle ->
                        Button(onClick = ::requestAndBegin, modifier = Modifier.fillMaxWidth()) {
                            Text("创建无外网附近 Log")
                        }

                    is NearbySessionCoordinator.State.Starting ->
                        Card(Modifier.fillMaxWidth()) {
                            Text("正在创建本地热点…", Modifier.padding(16.dp))
                        }

                    is NearbySessionCoordinator.State.Hosting ->
                        HostingCard(
                            state = value,
                            log = logs.firstOrNull { it.id == value.logId },
                            onStop = { NearbyLogHostService.stop(context) },
                        )

                    is NearbySessionCoordinator.State.Failed ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(value.message, color = MaterialTheme.colorScheme.error)
                                Button(onClick = ::requestAndBegin) { Text("重试") }
                            }
                        }
                }
            }

            if (session !is NearbySessionCoordinator.State.Hosting) {
                item {
                    JoinNearbyCard(
                        discoveryState = visibleDiscoveryState,
                        inviteCode = inviteCode,
                        joining = joining,
                        error = joinError,
                        pendingInvite = pendingInvite,
                        onInviteCodeChange = { changed ->
                            inviteCode = changed.filter(Char::isDigit).take(6)
                            if (pendingInvite?.inviteCode != inviteCode) {
                                pendingInvite = null
                                NearbyPendingInvite.clear(context)
                            }
                        },
                        onSearch = ::requestSearch,
                        onJoin = ::join,
                        onScan = ::requestScanner,
                        onOpenWifi = ::openWifiSettings,
                    )
                }
            }

            if (activeLog?.isPersonal == false && members.isNotEmpty()) {
                item {
                    val hostReachable =
                        (session is NearbySessionCoordinator.State.Hosting &&
                            (session as? NearbySessionCoordinator.State.Hosting)?.logId == activeLog.id) ||
                            (autoConnection is NearbyAutoConnector.State.Connected &&
                                (autoConnection as? NearbyAutoConnector.State.Connected)?.logId == activeLog.id)
                    MemberReadinessCard(
                        statuses =
                            NearbyMemberReadiness.calculate(
                                members = members,
                                clips = todayClips,
                                hostDeviceId = activeLog.hostDeviceId,
                                hostReachable = hostReachable,
                            ),
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Text("我的 Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            }

            items(logs, key = { it.id }) { log ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(log.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (log.role) {
                                    "owner" -> "附近 Log · 主人 · ${log.memberCount} 人"
                                    "member" -> "附近 Log · 成员 · ${log.memberCount} 人"
                                    else -> "个人 Log"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (log.id == currentLogId) {
                            Text("当前", style = MaterialTheme.typography.labelMedium)
                        } else {
                            OutlinedButton(onClick = { scope.launch { repo.selectDailyLog(log.id) } }) {
                                Text("切换")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinNearbyCard(
    discoveryState: LanHostDiscovery.State,
    inviteCode: String,
    joining: Boolean,
    error: String?,
    pendingInvite: NearbyInvite?,
    onInviteCodeChange: (String) -> Unit,
    onSearch: () -> Unit,
    onJoin: (LanEndpoint) -> Unit,
    onScan: () -> Unit,
    onOpenWifi: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("加入别人的附近 Log", style = MaterialTheme.typography.titleMedium)
            Text(
                "先在系统 Wi-Fi 中连接对方页面显示的热点，然后回来搜索。这个热点本身没有 Internet。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("扫描邀请二维码") }
            if (pendingInvite != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已读取：${pendingInvite.logName}", style = MaterialTheme.typography.labelLarge)
                        if (pendingInvite.ssid.isNotBlank()) Text("热点：${pendingInvite.ssid}")
                        if (pendingInvite.passphrase.isNotBlank()) Text("密码：${pendingInvite.passphrase}")
                        Text("连接该热点后会自动搜索并加入。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onOpenWifi) { Text("打开 Wi-Fi 设置") }
                    }
                }
            }
            when (discoveryState) {
                LanHostDiscovery.State.Idle ->
                    OutlinedButton(onClick = onSearch) { Text("搜索当前热点中的 Log") }

                // 搜索进度已由页面上方的统一状态卡展示，这里保留邀请与操作区，避免重复两遍。
                LanHostDiscovery.State.Searching -> Unit

                is LanHostDiscovery.State.Failed -> {
                    Text(discoveryState.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onSearch) { Text("重新搜索") }
                }

                is LanHostDiscovery.State.Found -> {
                    Text("已找到：${discoveryState.endpoint.serviceName}")
                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = onInviteCodeChange,
                        label = { Text("6 位邀请码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onJoin(discoveryState.endpoint) },
                        enabled = inviteCode.length == 6 && !joining,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (joining) "正在加入…" else "加入 Log")
                    }
                }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun HostingCard(
    state: NearbySessionCoordinator.State.Hosting,
    log: DailyLogEntity?,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("附近 Log 已启动", style = MaterialTheme.typography.titleMedium)
            Text("热点：${state.ssid}")
            Text("密码：${state.passphrase}")
            Text("邀请码：${state.inviteCode}")
            Text(
                "局域网端口：${state.port} · 无 Internet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val remoteId = log?.remoteId
            if (remoteId != null) {
                Text("让成员打开 App 扫描；二维码已包含热点和邀请码。", style = MaterialTheme.typography.bodySmall)
                InviteQrImage(
                    invite =
                        NearbyInvite(
                            remoteLogId = remoteId,
                            inviteCode = state.inviteCode,
                            logName = log.name,
                            ssid = state.ssid,
                            passphrase = state.passphrase,
                        ),
                    modifier = Modifier.size(230.dp).align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onStop) { Text("停止附近 Log") }
        }
    }
}

@Composable
private fun NearbyConnectionStatusCard(
    session: NearbySessionCoordinator.State,
    autoConnection: NearbyAutoConnector.State,
    discoveryState: LanHostDiscovery.State,
    activeLog: DailyLogEntity?,
    onRetry: () -> Unit,
    onOpenWifi: () -> Unit,
) {
    val status =
        when {
            session is NearbySessionCoordinator.State.Hosting ->
                ConnectionStatus("正在提供服务", "其他成员可以连接本机热点", MaterialTheme.colorScheme.primary, false)
            session is NearbySessionCoordinator.State.Starting ->
                ConnectionStatus("正在创建热点", "系统正在准备无外网局域网", MaterialTheme.colorScheme.tertiary, true)
            session is NearbySessionCoordinator.State.Failed ->
                ConnectionStatus("热点启动失败", connectionHint(session.message), MaterialTheme.colorScheme.error, false)
            autoConnection is NearbyAutoConnector.State.Connected ->
                ConnectionStatus("已连接", "${autoConnection.logName} · 自动保持连接", MaterialTheme.colorScheme.primary, false)
            autoConnection is NearbyAutoConnector.State.Connecting ->
                ConnectionStatus("正在加入", autoConnection.logName, MaterialTheme.colorScheme.tertiary, true)
            autoConnection is NearbyAutoConnector.State.Reconnecting ->
                ConnectionStatus(
                    "连接中断，正在恢复",
                    "${autoConnection.logName} · 第 ${autoConnection.attempt} 次尝试",
                    MaterialTheme.colorScheme.tertiary,
                    true,
                )
            autoConnection is NearbyAutoConnector.State.WaitingForHotspot ->
                ConnectionStatus(
                    "等待房主热点",
                    "${autoConnection.logName} · ${autoConnection.retryInSeconds} 秒后自动重试",
                    MaterialTheme.colorScheme.tertiary,
                    false,
                )
            autoConnection is NearbyAutoConnector.State.FoundUnjoined ->
                ConnectionStatus("已发现附近 Log", "扫描二维码或输入一次邀请码", MaterialTheme.colorScheme.primary, false)
            discoveryState is LanHostDiscovery.State.Searching || autoConnection is NearbyAutoConnector.State.Searching ->
                ConnectionStatus("正在搜索", "只搜索当前 Wi-Fi 中的附近 Log", MaterialTheme.colorScheme.tertiary, true)
            discoveryState is LanHostDiscovery.State.Found ->
                ConnectionStatus("已发现服务", discoveryState.endpoint.serviceName, MaterialTheme.colorScheme.primary, false)
            discoveryState is LanHostDiscovery.State.Failed ->
                ConnectionStatus("尚未连接", connectionHint(discoveryState.message), MaterialTheme.colorScheme.error, false)
            autoConnection is NearbyAutoConnector.State.Failed ->
                ConnectionStatus("连接未完成", connectionHint(autoConnection.message), MaterialTheme.colorScheme.error, false)
            else ->
                ConnectionStatus(
                    "未连接附近 Log",
                    if (activeLog?.role == "member") "连接房主热点后会自动恢复" else "可以创建房间或加入朋友的房间",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    false,
                )
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("●", color = status.color)
                Column(Modifier.weight(1f)) {
                    Text(status.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        status.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            val needsAction =
                autoConnection is NearbyAutoConnector.State.WaitingForHotspot ||
                    autoConnection is NearbyAutoConnector.State.Failed ||
                    discoveryState is LanHostDiscovery.State.Failed
            if (needsAction) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenWifi) { Text("Wi-Fi 设置") }
                    Button(onClick = onRetry) { Text("立即重试") }
                }
            }
        }
    }
}

@Composable
private fun MemberReadinessCard(statuses: List<NearbyMemberStatus>) {
    val ready = statuses.count { it.ready }
    val online = statuses.count { it.online }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("今日成员状态", style = MaterialTheme.typography.titleMedium)
            Text(
                "$ready/${statuses.size} 人已记录 · $online 人在线",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            statuses.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (member.online) "●" else "○", color = if (member.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Text(member.nickname, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (member.ready) "今日 ${member.clipCount} 条" else "还没拍",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (member.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class ConnectionStatus(
    val title: String,
    val detail: String,
    val color: Color,
    val busy: Boolean,
)

private fun connectionHint(message: String): String {
    val lower = message.lowercase()
    return when {
        "权限" in message || "permission" in lower -> "请允许“附近设备”权限后重试"
        "邀请码" in message -> "邀请码不正确；请重新扫描房主页面的二维码"
        "二维码" in message || "不一致" in message -> "请连接二维码中显示的房主热点"
        "没有发现" in message || "当前 wi-fi" in lower -> "请先连接房主热点，并确认房主页面仍显示“已启动”"
        "响应" in message || "timeout" in lower || "timed out" in lower -> "房主手机暂时没有响应；保持双方 App 在前台后重试"
        else -> message
    }
}

private fun connectionErrorMessage(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        error is SecurityException -> "缺少附近设备权限，请在系统设置中允许后重试"
        message.isBlank() -> "加入失败，请确认已连接房主热点"
        else -> connectionHint(message)
    }
}

private fun newInviteCode(): String {
    val random = SecureRandom()
    return buildString(6) { repeat(6) { append(random.nextInt(10)) } }
}
