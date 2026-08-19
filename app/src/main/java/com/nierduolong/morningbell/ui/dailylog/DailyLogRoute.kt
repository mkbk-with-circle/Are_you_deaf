package com.nierduolong.morningbell.ui.dailylog

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.core.DailyLogStats
import com.nierduolong.morningbell.dailylog.CompileCoordinator
import com.nierduolong.morningbell.dailylog.CompilePreflight
import com.nierduolong.morningbell.dailylog.CompilePreflightReport
import com.nierduolong.morningbell.dailylog.ReminderScheduler
import com.nierduolong.morningbell.dailylog.lan.NearbySessionCoordinator
import com.nierduolong.morningbell.dailylog.lan.NearbyAutoConnector
import com.nierduolong.morningbell.dailylog.lan.NearbyMemberReadiness
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.DailyLogSettings
import com.nierduolong.morningbell.data.db.DayLogSummary
import com.nierduolong.morningbell.data.db.LogClipEntity
import com.nierduolong.morningbell.ui.theme.MediaTokens
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Setlog 主功能页：今日素材 + 一键回看 + 归档网格 + 提醒设置 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLogRoute(
    repo: AppRepository,
    onOpenCapture: () -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenNearby: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MorningBellApp
    val scope = rememberCoroutineScope()
    val today = rememberTodayEpochDay()

    val logId by repo.currentLogIdFlow.collectAsState()
    val nickname by repo.nicknameFlow.collectAsState()
    val cadence by repo.reminderCadenceFlow.collectAsState()
    val window by repo.reminderWindowFlow.collectAsState()
    val compileState by CompileCoordinator.state.collectAsState()
    val logs by repo.dailyLogsFlow.collectAsState(initial = emptyList())
    val activeLog = remember(logs, logId) { logs.firstOrNull { it.id == logId } }
    val nearbySession by NearbySessionCoordinator.state.collectAsState()
    val nearbyAutoConnection by NearbyAutoConnector.state.collectAsState()

    val daySummaries by
        remember(logId) { logId?.let { repo.daySummariesFlow(it) } ?: flowOf(emptyList()) }
            .collectAsState(initial = emptyList())
    val todayClips by
        remember(logId, today) {
            logId?.let { repo.clipsForDayFlow(it, today) } ?: flowOf(emptyList())
        }.collectAsState(initial = emptyList())
    val nearbyMembers by
        remember(logId, activeLog?.isPersonal) {
            logId?.takeIf { activeLog?.isPersonal == false }?.let(repo::logMembersFlow) ?: flowOf(emptyList())
        }.collectAsState(initial = emptyList())
    val compilations by
        remember(logId) { logId?.let { repo.compilationsFlow(it) } ?: flowOf(emptyList()) }
            .collectAsState(initial = emptyList())

    val compiledDays = remember(compilations) { compilations.map { it.dayEpoch }.toSet() }
    val streak =
        remember(daySummaries, today) {
            DailyLogStats.computeStreak(daySummaries.map { it.dayEpoch }.toSet(), today)
        }
    val pastDays = remember(daySummaries, today) { daySummaries.filter { it.dayEpoch != today } }

    var showReminderSheet by remember { mutableStateOf(false) }
    var showCompilePreflight by remember { mutableStateOf(false) }
    var checkingCompilePreflight by remember { mutableStateOf(false) }
    var compilePreflightReport by remember { mutableStateOf<CompilePreflightReport?>(null) }
    val sheetState = rememberModalBottomSheetState()

    fun startTodayCompile(excludedClipIds: Set<Long> = emptySet()) {
        val id = logId ?: return
        showCompilePreflight = false
        val started =
            CompileCoordinator.start(
                scope = app.appScope,
                context = context,
                repo = repo,
                logId = id,
                dayEpoch = today,
                force = today in compiledDays,
                excludedClipIds = excludedClipIds,
            )
        if (!started) {
            android.widget.Toast
                .makeText(context, context.getString(R.string.dailylog_compiling), android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun inspectTodayBeforeCompile() {
        val id = logId ?: return
        if (activeLog?.isPersonal != false) {
            startTodayCompile()
            return
        }
        showCompilePreflight = true
        checkingCompilePreflight = true
        compilePreflightReport = null
        scope.launch {
            val result = runCatching { CompilePreflight.inspect(repo, id, today) }
            checkingCompilePreflight = false
            result.onSuccess { compilePreflightReport = it }
                .onFailure {
                    showCompilePreflight = false
                    android.widget.Toast.makeText(context, it.message ?: "无法检查附近素材", android.widget.Toast.LENGTH_SHORT).show()
                }
        }
    }

    if (showCompilePreflight) {
        CompilePreflightDialog(
            checking = checkingCompilePreflight,
            report = compilePreflightReport,
            onRetry = ::inspectTodayBeforeCompile,
            onCompile = { skip ->
                startTodayCompile(if (skip) compilePreflightReport?.unavailableClipIds.orEmpty() else emptySet())
            },
            onDismiss = { showCompilePreflight = false },
        )
    }

    // 合成结束弹一次提示就复位，避免切回页面反复提示
    LaunchedEffect(compileState) {
        val state = compileState
        if (state is CompileCoordinator.State.Success || state is CompileCoordinator.State.Failed) {
            android.widget.Toast
                .makeText(
                    context,
                    if (state is CompileCoordinator.State.Success) {
                        context.getString(R.string.dailylog_compile_done)
                    } else {
                        (state as CompileCoordinator.State.Failed).message
                    },
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            CompileCoordinator.consumeTerminalState()
        }
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            LogHeader(
                title = activeLog?.name ?: nickname,
                streak = streak,
                totalDays = daySummaries.size,
                onOpenReminders = { showReminderSheet = true },
                onOpenNearby = onOpenNearby,
            )
        }

        val showConnectionBanner =
            nearbyAutoConnection is NearbyAutoConnector.State.FoundUnjoined ||
                nearbyAutoConnection is NearbyAutoConnector.State.Connecting ||
                (activeLog?.role == "member" &&
                    (nearbyAutoConnection is NearbyAutoConnector.State.Reconnecting ||
                        nearbyAutoConnection is NearbyAutoConnector.State.WaitingForHotspot ||
                        nearbyAutoConnection is NearbyAutoConnector.State.Failed))
        if (showConnectionBanner) {
            item {
                val message =
                    when (val state = nearbyAutoConnection) {
                        is NearbyAutoConnector.State.Connecting -> "正在自动连接 ${state.logName}…"
                        is NearbyAutoConnector.State.Reconnecting -> "连接中断，正在自动恢复（第 ${state.attempt} 次）"
                        is NearbyAutoConnector.State.WaitingForHotspot -> "等待房主热点 · ${state.retryInSeconds} 秒后重试"
                        is NearbyAutoConnector.State.Failed -> "附近 Log 暂未连接 · 点此查看原因"
                        else -> "已发现热点中的附近 Log · 输入一次邀请码"
                    }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClick = onOpenNearby)
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }

        if (activeLog?.isPersonal == false && nearbyMembers.isNotEmpty()) {
            item {
                val hostReachable =
                    (nearbySession is NearbySessionCoordinator.State.Hosting &&
                        (nearbySession as? NearbySessionCoordinator.State.Hosting)?.logId == activeLog.id) ||
                        (nearbyAutoConnection is NearbyAutoConnector.State.Connected &&
                            (nearbyAutoConnection as? NearbyAutoConnector.State.Connected)?.logId == activeLog.id)
                val statuses =
                    NearbyMemberReadiness.calculate(
                        members = nearbyMembers,
                        clips = todayClips,
                        hostDeviceId = activeLog.hostDeviceId,
                        hostReachable = hostReachable,
                    )
                val ready = statuses.count { it.ready }
                val online = statuses.count { it.online }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenNearby)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(Icons.Filled.People, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        "今日 $ready/${statuses.size} 人已记录 · $online 人在线",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text("查看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item {
            TodayStrip(
                clips = todayClips,
                onRecord = onOpenCapture,
                onPlayFrom = { index ->
                    PlaybackQueue.setItems(
                        playbackItemsFor(todayClips.drop(index), activeLog, nearbySession),
                        context.getString(R.string.dailylog_today_title),
                    )
                    onOpenPlayer()
                },
            )
        }

        item {
            TodayActions(
                hasClips = todayClips.isNotEmpty(),
                alreadyCompiled = today in compiledDays,
                compileState = compileState,
                today = today,
                onPlayToday = {
                    PlaybackQueue.setItems(
                        playbackItemsFor(todayClips, activeLog, nearbySession),
                        context.getString(R.string.dailylog_today_title),
                    )
                    onOpenPlayer()
                },
                onCompile = {
                    inspectTodayBeforeCompile()
                },
            )
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.dailylog_history_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
            )
        }

        if (pastDays.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.dailylog_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            // 网格出血到屏幕两侧、只留 2dp 缝、不切圆角：照片流的画面越连续越好看，
            // 内缩加圆角会把每一格变成一张卡片，那是后台管理界面的语言
            items(pastDays.chunked(3)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    row.forEach { summary ->
                        DayTile(
                            summary = summary,
                            compiled = summary.dayEpoch in compiledDays,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenDay(summary.dayEpoch) },
                        )
                    }
                    // 补齐最后一行的空位，避免两个格子被拉宽变形
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    if (showReminderSheet) {
        ModalBottomSheet(
            onDismissRequest = { showReminderSheet = false },
            sheetState = sheetState,
        ) {
            ReminderSettingsSheet(
                cadence = cadence,
                windowStart = window.first,
                windowEnd = window.second,
                nextTriggerAt = remember(cadence, window) { ReminderScheduler.previewNextTriggerAt(context) },
                notificationsEnabled =
                    remember(showReminderSheet) {
                        NotificationManagerCompat.from(context).areNotificationsEnabled()
                    },
                onOpenNotificationSettings = {
                    val intent =
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(intent) }
                },
                onCadenceChange = { scope.launch { repo.setReminderCadence(it) } },
                onWindowChange = { start, end -> scope.launch { repo.setReminderWindow(start, end) } },
            )
        }
    }
}

/**
 * 昵称 + 两列数字统计。
 * 原本是「已连续记录 3 天 · 共 12 天」一句话，中点连接的句子读起来像凑数；
 * 数字在上、标签在下才是一眼能扫的写法，两个数都直接来自数据库。
 */
@Composable
private fun LogHeader(
    title: String,
    streak: Int,
    totalDays: Int,
    onOpenReminders: () -> Unit,
    onOpenNearby: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenNearby) {
                Icon(Icons.Filled.People, contentDescription = "附近 Log")
            }
            IconButton(onClick = onOpenReminders) {
                Icon(Icons.Filled.NotificationsNone, contentDescription = null)
            }
        }
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            StatColumn(totalDays, stringResource(R.string.dailylog_stat_total))
            StatColumn(streak, stringResource(R.string.dailylog_stat_streak))
        }
    }
}

@Composable
private fun StatColumn(
    value: Int,
    label: String,
) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 今日素材横向条：首格是拍摄入口，后面按时间排开，点一格从那一条开始连播。
 *
 * 圆形 + 时间写在圆下面，而不是压在画面上：这一条一天里会被看很多次，
 * 画面上少一块黑标签，整页就安静一大截。
 */
@Composable
private fun TodayStrip(
    clips: List<LogClipEntity>,
    onRecord: () -> Unit,
    onPlayFrom: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.dailylog_today_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.dailylog_today_count_fmt, clips.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StoryItem(label = stringResource(R.string.dailylog_story_add), onClick = onRecord) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            items(clips.size) { index ->
                val clip = clips[index]
                val time =
                    remember(clip.createdAt) {
                        DateTimeFormatter.ofPattern("HH:mm").format(
                            Instant.ofEpochMilli(clip.createdAt).atZone(ZoneId.systemDefault()),
                        )
                    }
                StoryItem(label = time, onClick = { onPlayFrom(index) }) {
                    if ((clip.sourceKept && clip.filePath.isNotBlank()) || clip.localThumbPath != null) {
                        VideoThumbnail(
                            path = clip.filePath,
                            thumbnailPath = clip.localThumbPath,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 圆形缩略图 + 圆下面一行小字，两者一起可点 */
@Composable
private fun StoryItem(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape)) { content() }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun TodayActions(
    hasClips: Boolean,
    alreadyCompiled: Boolean,
    compileState: CompileCoordinator.State,
    today: Long,
    onPlayToday: () -> Unit,
    onCompile: () -> Unit,
) {
    val runningToday =
        (compileState as? CompileCoordinator.State.Running)?.takeIf { it.dayEpoch == today }
    // 按钮不挂前导图标：文案本身已经说清是什么，
    // 每个按钮都塞一个小图标是最容易认出来的「生成式界面」写法
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onPlayToday,
            enabled = hasClips,
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
        ) {
            Text(stringResource(R.string.dailylog_play_today), maxLines = 1)
        }
        OutlinedButton(
            onClick = onCompile,
            enabled = hasClips && runningToday == null,
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
        ) {
            if (runningToday != null) {
                CircularProgressIndicator(
                    progress = { runningToday.progress.coerceAtLeast(0.02f) },
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("${(runningToday.progress * 100).toInt()}%", maxLines = 1)
            } else {
                Text(
                    stringResource(
                        if (alreadyCompiled) R.string.dailylog_recompile_today else R.string.dailylog_compile_today,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 归档网格的单格：封面 + 日期，已合成的那天多一个胶片小标。
 *
 * 这里原本压了四层信息（日期、已合成圆点、精简、条数），一格才 110dp 见方，
 * 标签比画面还抢眼。条数与「精简」在一日详情页看得更清楚，格子上只留一眼要看的两件事。
 */
@Composable
private fun DayTile(
    summary: DayLogSummary,
    compiled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clickable(onClick = onClick),
    ) {
        if (summary.coverPath != null || summary.coverThumbPath != null) {
            VideoThumbnail(
                path = summary.coverPath.orEmpty(),
                thumbnailPath = summary.coverThumbPath,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(30.dp)
                .background(MediaTokens.bottomScrim),
        )
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDayShort(summary.dayEpoch),
                style = MaterialTheme.typography.labelMedium,
                color = MediaTokens.onMedia,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (compiled) {
                Icon(
                    Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MediaTokens.onMedia,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderSettingsSheet(
    cadence: DailyLogSettings.ReminderCadence,
    windowStart: Int,
    windowEnd: Int,
    nextTriggerAt: Long?,
    notificationsEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onCadenceChange: (DailyLogSettings.ReminderCadence) -> Unit,
    onWindowChange: (Int, Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.dailylog_reminder_section_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.dailylog_reminder_section_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 通知被关掉时提醒等于没开，必须显式告知，否则用户会以为是 App 坏了
        if (!notificationsEnabled) {
            Text(
                stringResource(R.string.dailylog_reminder_notify_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onOpenNotificationSettings),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CadenceChip(cadence, DailyLogSettings.ReminderCadence.HOURLY, R.string.dailylog_reminder_hourly, onCadenceChange)
            CadenceChip(cadence, DailyLogSettings.ReminderCadence.EVERY_3_HOURS, R.string.dailylog_reminder_3h, onCadenceChange)
            CadenceChip(cadence, DailyLogSettings.ReminderCadence.RANDOM, R.string.dailylog_reminder_random, onCadenceChange)
            CadenceChip(cadence, DailyLogSettings.ReminderCadence.OFF, R.string.dailylog_reminder_off, onCadenceChange)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
            stringResource(R.string.dailylog_reminder_window_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.dailylog_reminder_window_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HourStepper(
            label = stringResource(R.string.dailylog_reminder_window_start),
            hour = windowStart,
            onChange = { onWindowChange(it, windowEnd) },
        )
        HourStepper(
            label = stringResource(R.string.dailylog_reminder_window_end),
            hour = windowEnd,
            onChange = { onWindowChange(windowStart, it) },
        )

        if (nextTriggerAt != null) {
            Text(
                stringResource(
                    R.string.dailylog_reminder_next_fmt,
                    DateTimeFormatter.ofPattern("M月d日 HH:mm")
                        .format(Instant.ofEpochMilli(nextTriggerAt).atZone(ZoneId.systemDefault())),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CadenceChip(
    current: DailyLogSettings.ReminderCadence,
    value: DailyLogSettings.ReminderCadence,
    labelResId: Int,
    onChange: (DailyLogSettings.ReminderCadence) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onChange(value) },
        label = { Text(stringResource(labelResId)) },
    )
}

@Composable
private fun HourStepper(
    label: String,
    hour: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        // 用真图标而不是 "−" / "+" 文字：文字符号在不同字体下大小和基线都不一样，
        // 和这一页其它 Material 图标不是一套笔画
        IconButton(onClick = { onChange(hour - 1) }) {
            Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(
            "%02d:00".format(hour),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(58.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange(hour + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}
