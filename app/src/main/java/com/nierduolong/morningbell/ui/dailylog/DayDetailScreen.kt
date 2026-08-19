package com.nierduolong.morningbell.ui.dailylog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.core.DailyLogStats
import com.nierduolong.morningbell.dailylog.CompileCoordinator
import com.nierduolong.morningbell.dailylog.CompilePreflight
import com.nierduolong.morningbell.dailylog.CompilePreflightReport
import com.nierduolong.morningbell.dailylog.ExportShare
import com.nierduolong.morningbell.dailylog.lan.NearbySessionCoordinator
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.LogClipEntity
import com.nierduolong.morningbell.ui.theme.MediaTokens
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 某一天的完整日志：合成结果 + 当天所有素材 + 每条素材的说明与留言 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailRoute(
    repo: AppRepository,
    dayEpoch: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MorningBellApp
    val scope = rememberCoroutineScope()

    val logId by repo.currentLogIdFlow.collectAsState()
    val logs by repo.dailyLogsFlow.collectAsState(initial = emptyList())
    val activeLog = remember(logs, logId) { logs.firstOrNull { it.id == logId } }
    val nearbySession by NearbySessionCoordinator.state.collectAsState()
    val compileState by CompileCoordinator.state.collectAsState()
    // 用 null 表示「还没查出来」，与「这一天确实没有素材」区分开，
    // 否则首帧的空列表会被当成已清空而立刻退出页面
    val loadedClips by
        remember(logId, dayEpoch) {
            logId?.let { repo.clipsForDayFlow(it, dayEpoch) } ?: flowOf(emptyList())
        }.collectAsState(initial = null)
    val clips = loadedClips ?: emptyList()
    val compilations by
        remember(logId) { logId?.let { repo.compilationsFlow(it) } ?: flowOf(emptyList()) }
            .collectAsState(initial = emptyList())
    val compilation = remember(compilations, dayEpoch) { compilations.firstOrNull { it.dayEpoch == dayEpoch } }

    var selectedClip by remember { mutableStateOf<LogClipEntity?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var confirmCleanSources by remember { mutableStateOf(false) }
    var showCompilePreflight by remember { mutableStateOf(false) }
    var checkingCompilePreflight by remember { mutableStateOf(false) }
    var compilePreflightReport by remember { mutableStateOf<CompilePreflightReport?>(null) }
    val sheetState = rememberModalBottomSheetState()
    // 原始素材是否还在。全部清理过的日期不能再合成，也没有可播放的单条素材
    val hasSources = clips.any { it.sourceKept }
    val hasPotentialCompileInputs = hasSources || (activeLog?.isPersonal == false && clips.any { it.clientUuid != null })

    fun startDayCompile(excludedClipIds: Set<Long> = emptySet()) {
        val id = logId ?: return
        showCompilePreflight = false
        CompileCoordinator.start(
            scope = app.appScope,
            context = context,
            repo = repo,
            logId = id,
            dayEpoch = dayEpoch,
            force = compilation != null,
            excludedClipIds = excludedClipIds,
        )
    }

    fun inspectDayBeforeCompile() {
        val id = logId ?: return
        if (activeLog?.isPersonal != false) {
            startDayCompile()
            return
        }
        showCompilePreflight = true
        checkingCompilePreflight = true
        compilePreflightReport = null
        scope.launch {
            val result = runCatching { CompilePreflight.inspect(repo, id, dayEpoch) }
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
            onRetry = ::inspectDayBeforeCompile,
            onCompile = { skip ->
                startDayCompile(if (skip) compilePreflightReport?.unavailableClipIds.orEmpty() else emptySet())
            },
            onDismiss = { showCompilePreflight = false },
        )
    }

    // 素材被删空后这一天已经没有内容可看，直接退回上一页
    LaunchedEffect(loadedClips) {
        val snapshot = loadedClips
        if (snapshot != null && snapshot.isEmpty()) onBack()
    }

    val runningThisDay =
        (compileState as? CompileCoordinator.State.Running)?.takeIf { it.dayEpoch == dayEpoch }

    LaunchedEffect(compileState) {
        when (val state = compileState) {
            is CompileCoordinator.State.Success -> {
                if (state.dayEpoch == dayEpoch) {
                    android.widget.Toast.makeText(context, context.getString(R.string.dailylog_compile_done), android.widget.Toast.LENGTH_SHORT).show()
                    CompileCoordinator.consumeTerminalState()
                }
            }
            is CompileCoordinator.State.Failed -> {
                if (state.dayEpoch == dayEpoch) {
                    android.widget.Toast.makeText(context, state.message, android.widget.Toast.LENGTH_LONG).show()
                    CompileCoordinator.consumeTerminalState()
                }
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatDayLabel(dayEpoch), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(
                            R.string.dailylog_day_summary_fmt,
                            clips.size,
                            DailyLogStats.formatDuration(clips.sumOf { it.durationMs }),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    if (compilation != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        PlaybackQueue.set(listOf(compilation.filePath), formatDayLabel(dayEpoch))
                                        onOpenPlayer()
                                    },
                        ) {
                            VideoThumbnail(path = compilation.filePath, modifier = Modifier.fillMaxSize())
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(52.dp)
                                        .background(MediaTokens.scrim, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MediaTokens.onMedia,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 老系统写相册需要旧存储权限，这里直接不展示入口，只留分享
                            if (ExportShare.canSaveToGallery()) {
                                OutlinedButton(
                                    onClick = {
                                        exporting = true
                                        scope.launch {
                                            val uri =
                                                runCatching {
                                                    ExportShare.saveToGallery(context, File(compilation.filePath))
                                                }.getOrNull()
                                            exporting = false
                                            android.widget.Toast
                                                .makeText(
                                                    context,
                                                    context.getString(
                                                        if (uri != null) {
                                                            R.string.dailylog_export_success
                                                        } else {
                                                            R.string.dailylog_export_fail
                                                        },
                                                    ),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                        }
                                    },
                                    enabled = !exporting,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.dailylog_export_gallery), maxLines = 1)
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            ExportShare.sharePendingIntent(context, null, File(compilation.filePath)),
                                        )
                                    }
                                },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.dailylog_share), maxLines = 1)
                            }
                        }
                        // 合成在手上了，才敢提供「删原始素材换空间」这个选项。
                        // 它是破坏性操作，放成文字按钮而不是描边按钮，视觉重量低于「重新合成」
                        if (hasSources) {
                            TextButton(
                                onClick = { confirmCleanSources = true },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.dailylog_clean_day_sources),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (!hasSources && clips.isNotEmpty()) {
                        Text(
                            stringResource(R.string.dailylog_day_sources_cleaned),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    if (hasPotentialCompileInputs) {
                        Button(
                            onClick = ::inspectDayBeforeCompile,
                            enabled = runningThisDay == null,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        ) {
                            if (runningThisDay != null) {
                                CircularProgressIndicator(
                                    progress = { runningThisDay.progress.coerceAtLeast(0.02f) },
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("${(runningThisDay.progress * 100).toInt()}%", maxLines = 1)
                            } else {
                                Text(
                                    stringResource(
                                        if (compilation != null) {
                                            R.string.dailylog_recompile_day
                                        } else {
                                            R.string.dailylog_compile_day
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    stringResource(R.string.dailylog_day_clips_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp),
                )
            }

            // 与归档页同一套网格语言：满幅、2dp 缝、不切圆角
            items(clips.chunked(3)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    row.forEach { clip ->
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clickable { selectedClip = clip },
                        ) {
                            when {
                                clip.sourceKept || clip.localThumbPath != null ->
                                    VideoThumbnail(
                                        path = clip.filePath,
                                        thumbnailPath = clip.localThumbPath,
                                        modifier = Modifier.fillMaxSize(),
                                        durationMs = clip.durationMs,
                                    )
                                clip.transferState == "available_remote" -> RemoteClipTile(clip.createdAt)
                                else -> CleanedClipTile(clip.createdAt)
                            }
                            if (!clip.caption.isNullOrBlank()) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .size(5.dp)
                                        .background(MediaTokens.onMedia, CircleShape),
                                )
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    val clip = selectedClip
    if (clip != null) {
        val selectedItems = playbackItemsFor(listOf(clip), activeLog, nearbySession)
        ModalBottomSheet(
            onDismissRequest = { selectedClip = null },
            sheetState = sheetState,
        ) {
            ClipDetailSheet(
                repo = repo,
                clip = clip,
                playable = selectedItems.isNotEmpty(),
                onPlay = {
                    PlaybackQueue.setItems(selectedItems, formatDayLabel(dayEpoch))
                    selectedClip = null
                    onOpenPlayer()
                },
                onDeleted = { selectedClip = null },
            )
        }
    }

    if (confirmCleanSources) {
        val id = logId
        AlertDialog(
            onDismissRequest = { confirmCleanSources = false },
            title = { Text(stringResource(R.string.dailylog_clean_day_title)) },
            text = { Text(stringResource(R.string.dailylog_clean_day_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanSources = false
                    if (id == null) return@TextButton
                    scope.launch {
                        val cleaned = repo.cleanDaySources(id, dayEpoch)
                        android.widget.Toast
                            .makeText(
                                context,
                                if (cleaned > 0) {
                                    context.getString(R.string.dailylog_clean_day_done_fmt, cleaned)
                                } else {
                                    context.getString(R.string.dailylog_clean_day_failed)
                                },
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                    }
                }) { Text(stringResource(R.string.dailylog_clean_day_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanSources = false }) {
                    Text(stringResource(R.string.alarm_delete_cancel))
                }
            },
        )
    }
}

/** 已按保留策略清理的素材：没有画面可显示，但时间点仍然是这一天的一部分 */
@Composable
private fun CleanedClipTile(createdAt: Long) {
    val time =
        remember(createdAt) {
            DateTimeFormatter.ofPattern("HH:mm").format(
                Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()),
            )
        }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoteClipTile(createdAt: Long) {
    val time =
        remember(createdAt) {
            DateTimeFormatter.ofPattern("HH:mm").format(
                Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()),
            )
        }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 单条素材：播放、编辑说明、留言、删除 */
@Composable
private fun ClipDetailSheet(
    repo: AppRepository,
    clip: LogClipEntity,
    playable: Boolean,
    onPlay: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val comments by remember(clip.id) { repo.commentsFlow(clip.id) }.collectAsState(initial = emptyList())
    var caption by remember(clip.id) { mutableStateOf(clip.caption ?: "") }
    var newComment by remember(clip.id) { mutableStateOf("") }
    var confirmDelete by remember(clip.id) { mutableStateOf(false) }
    val time =
        remember(clip.createdAt) {
            DateTimeFormatter.ofPattern("HH:mm").format(
                Instant.ofEpochMilli(clip.createdAt).atZone(ZoneId.systemDefault()),
            )
        }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(MaterialTheme.shapes.small)
                        // 素材已清理时不给播放入口，点了只会黑屏
                        .clickable(enabled = playable, onClick = onPlay),
            ) {
                if ((clip.sourceKept && clip.filePath.isNotBlank()) || clip.localThumbPath != null) {
                    VideoThumbnail(
                        path = clip.filePath,
                        thumbnailPath = clip.localThumbPath,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MediaTokens.onMedia,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (clip.transferState == "available_remote") {
                    RemoteClipTile(clip.createdAt)
                } else {
                    CleanedClipTile(clip.createdAt)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(time, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (clip.sourceKept) {
                        DailyLogStats.formatDuration(clip.durationMs)
                    } else if (playable) {
                        "从成员手机流式播放"
                    } else if (clip.transferState == "available_remote") {
                        "成员设备当前离线"
                    } else {
                        stringResource(R.string.dailylog_clip_source_cleaned)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            label = { Text(stringResource(R.string.dailylog_capture_caption_label)) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        TextButton(
            onClick = { scope.launch { repo.updateLogClipCaption(clip.id, caption) } },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.dailylog_capture_caption_save))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
            stringResource(R.string.dailylog_clip_comments_fmt, comments.size),
            style = MaterialTheme.typography.titleSmall,
        )
        if (comments.isEmpty()) {
            Text(
                stringResource(R.string.dailylog_clip_comments_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            comments.forEach { c ->
                Row {
                    Text(
                        c.authorName,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(c.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newComment,
                onValueChange = { newComment = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                label = { Text(stringResource(R.string.dailylog_clip_comment_label)) },
            )
            TextButton(
                enabled = newComment.isNotBlank(),
                onClick = {
                    scope.launch {
                        repo.addLogComment(clip.id, newComment)
                        newComment = ""
                    }
                },
            ) { Text(stringResource(R.string.dailylog_clip_comment_send)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dailylog_clip_delete_title)) },
            text = { Text(stringResource(R.string.dailylog_clip_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteLogClip(clip.id)
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.dailylog_clip_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.alarm_delete_cancel))
                }
            },
        )
    }
}
