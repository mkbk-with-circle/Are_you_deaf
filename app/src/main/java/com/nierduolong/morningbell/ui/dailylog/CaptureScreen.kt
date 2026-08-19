package com.nierduolong.morningbell.ui.dailylog

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.core.DailyLogStats
import com.nierduolong.morningbell.dailylog.DailyLogStorage
import com.nierduolong.morningbell.dailylog.ThumbnailStore
import com.nierduolong.morningbell.dailylog.lan.NearbySyncManager
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.ui.theme.MediaTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** 低于这个时长认为是误触，直接丢弃，避免一堆 0.2 秒的废片污染当天合成 */
private const val MIN_CLIP_MS = 800L

/** Setlog 功能 3：拍摄不限时长的短视频。点一次开始，再点一次结束 */
@Composable
fun CaptureRoute(
    repo: AppRepository,
    logId: Long,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MorningBellApp
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result ->
                hasCameraPermission = result[Manifest.permission.CAMERA] ?: hasCameraPermission
                hasAudioPermission = result[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
            },
        )
    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasAudioPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    // 没有相机权限就完全没法拍；麦克风缺失只降级成静音录制，不拦截
    if (!hasCameraPermission) {
        PermissionGate(
            onRequest = {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            },
            onBack = onDone,
        )
        return
    }

    var isRecording by remember { mutableStateOf(false) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var currentRecording by remember { mutableStateOf<Recording?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingDayEpoch by remember { mutableLongStateOf(0L) }
    var pendingDurationMs by remember { mutableLongStateOf(0L) }
    var caption by remember { mutableStateOf("") }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    LaunchedEffect(isRecording, recordingStartedAt) {
        while (isRecording) {
            elapsedMs = System.currentTimeMillis() - recordingStartedAt
            delay(200)
        }
    }

    /**
     * 画质钉在 720p：时长不限的前提下如果放任 CameraX 选最高画质（部分机型 4K），
     * 几分钟就能吃掉几个 GB，日结合成也会慢到不可用。
     */
    val videoCapture =
        remember {
            VideoCapture.withOutput(
                Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        ),
                    )
                    .build(),
            )
        }
    val previewView =
        remember {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        }
    val preview =
        remember {
            Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        }

    val cameraProvider by
        produceState<ProcessCameraProvider?>(initialValue = null) {
            value = withContext(Dispatchers.IO) { runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull() }
        }

    LaunchedEffect(cameraProvider, lensFacing) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        val bound =
            runCatching {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
            }
        if (bound.isFailure) {
            Toast.makeText(context, context.getString(R.string.dailylog_capture_camera_error), Toast.LENGTH_SHORT).show()
        }
    }

    // 离开页面必须解绑，否则相机会被这个页面一直占着，返回后其他功能拿不到相机。
    // key 用 Unit：若用 cameraProvider 做 key，它从 null 变成实例时会先触发一次解绑
    DisposableEffect(Unit) {
        onDispose {
            currentRecording?.stop()
            currentRecording = null
            cameraProvider?.unbindAll()
        }
    }

    fun startRecording() {
        if (!DailyLogStorage.hasEnoughFreeSpace(context)) {
            Toast.makeText(context, context.getString(R.string.dailylog_capture_no_space), Toast.LENGTH_LONG).show()
            return
        }
        // 归到「按下录制那一刻」的日期：用结束时刻会让跨零点的录制落到第二天，
        // 与文件所在的日期目录不一致
        val dayEpoch = LocalDate.now().toEpochDay()
        val outputFile = DailyLogStorage.newClipFile(context, logId.coerceAtLeast(0L), dayEpoch)
        val options = FileOutputOptions.Builder(outputFile).build()
        val started =
            runCatching {
                videoCapture.output
                    .prepareRecording(context, options)
                    .apply { if (hasAudioPermission) withAudioEnabled() }
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                isRecording = true
                                recordingStartedAt = System.currentTimeMillis()
                            }

                            is VideoRecordEvent.Finalize -> {
                                isRecording = false
                                currentRecording = null
                                // 以录制统计为准；个别机型统计缺失时回落到墙上时钟
                                val statsMs = event.recordingStats.recordedDurationNanos / 1_000_000
                                val durationMs =
                                    if (statsMs > 0) statsMs else System.currentTimeMillis() - recordingStartedAt
                                when {
                                    event.hasError() -> {
                                        outputFile.delete()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.dailylog_capture_save_error),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }

                                    durationMs < MIN_CLIP_MS -> {
                                        outputFile.delete()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.dailylog_capture_too_short),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }

                                    else -> {
                                        pendingFile = outputFile
                                        pendingDayEpoch = dayEpoch
                                        pendingDurationMs = durationMs
                                    }
                                }
                            }
                        }
                    }
            }
        if (started.isSuccess) {
            currentRecording = started.getOrNull()
            isRecording = true
            recordingStartedAt = System.currentTimeMillis()
        } else {
            Toast.makeText(context, context.getString(R.string.dailylog_capture_camera_error), Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = MediaTokens.onMedia)
            }
            Spacer(Modifier.weight(1f))
            if (isRecording) {
                RecordingBadge(elapsedMs)
                Spacer(Modifier.weight(1f))
            }
            IconButton(
                enabled = !isRecording,
                onClick = {
                    lensFacing =
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                },
            ) {
                Icon(
                    Icons.Filled.Cameraswitch,
                    contentDescription = null,
                    tint = if (isRecording) MediaTokens.onMediaDisabled else MediaTokens.onMedia,
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(
                    if (isRecording) R.string.dailylog_capture_recording_hint else R.string.dailylog_capture_idle_hint,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MediaTokens.onMediaMuted,
            )
            RecordButton(
                isRecording = isRecording,
                onClick = { if (isRecording) currentRecording?.stop() else startRecording() },
            )
        }
    }

    val fileToSave = pendingFile
    if (fileToSave != null) {
        CaptionDialog(
            caption = caption,
            onCaptionChange = { caption = it },
            // 关掉弹窗不该丢素材：默认按「不加说明」保存，只有显式点删除才丢
            onSave = {
                scope.launch {
                    val saved = saveClip(repo, logId, fileToSave, pendingDayEpoch, pendingDurationMs, caption)
                    app.appScope.launch {
                        runCatching { NearbySyncManager.publishAndPullDay(context, repo, saved.first, saved.second) }
                    }
                    pendingFile = null
                    caption = ""
                    onDone()
                }
            },
            onDiscard = {
                fileToSave.delete()
                pendingFile = null
                caption = ""
                onDone()
            },
        )
    }
}

/** 落库时兜一层：录制统计异常时用文件元数据里的真实时长，避免合成顺序和时长展示出错 */
private suspend fun saveClip(
    repo: AppRepository,
    logId: Long,
    file: File,
    dayEpoch: Long,
    durationMs: Long,
    caption: String,
): Pair<Long, Long> {
    val effectiveLogId = if (logId > 0) logId else repo.ensurePersonalDailyLog()
    val duration = if (durationMs > 0) durationMs else ThumbnailStore.durationMs(file.absolutePath)
    repo.insertLogClip(
        logId = effectiveLogId,
        filePath = file.absolutePath,
        durationMs = duration,
        caption = caption,
        dayEpoch = dayEpoch,
    )
    return effectiveLogId to dayEpoch
}

@Composable
private fun RecordingBadge(elapsedMs: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .background(MediaTokens.scrim, CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(Modifier.size(7.dp).background(MediaTokens.record, CircleShape))
        Text(
            DailyLogStats.formatDuration(elapsedMs),
            style = MaterialTheme.typography.labelMedium,
            color = MediaTokens.onMedia,
        )
    }
}

/** 白圈 + 内部形状在圆与圆角方之间变形，是短视频拍摄键的通用手感 */
@Composable
private fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
) {
    val innerSize by animateDpAsState(if (isRecording) 30.dp else 64.dp, label = "recordInnerSize")
    val innerColor by
        animateColorAsState(
            if (isRecording) MediaTokens.record else MediaTokens.onMedia,
            label = "recordInnerColor",
        )
    Box(
        modifier =
            Modifier
                .size(78.dp)
                .border(3.dp, MediaTokens.onMedia, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(innerSize)
                    .background(innerColor, if (isRecording) RoundedCornerShape(8.dp) else CircleShape),
        )
    }
}

@Composable
private fun CaptionDialog(
    caption: String,
    onCaptionChange: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSave,
        title = { Text(stringResource(R.string.dailylog_capture_caption_title)) },
        text = {
            OutlinedTextField(
                value = caption,
                onValueChange = onCaptionChange,
                label = { Text(stringResource(R.string.dailylog_capture_caption_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.dailylog_capture_caption_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.dailylog_capture_discard))
            }
        },
    )
}

@Composable
private fun PermissionGate(
    onRequest: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.dailylog_capture_permission_needed),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.dailylog_capture_permission_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onRequest) {
                Text(stringResource(R.string.dailylog_capture_permission_grant))
            }
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.dailylog_capture_back))
            }
        }
    }
}
