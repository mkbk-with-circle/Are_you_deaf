package com.nierduolong.morningbell.ui.dailylog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nierduolong.morningbell.core.DailyLogStats
import com.nierduolong.morningbell.ui.theme.MediaTokens
import kotlinx.coroutines.delay
import java.io.File

/**
 * 播放请求的临时载体。文件路径列表塞进导航参数既难看又有长度上限，
 * 单人本地场景直接用进程内单例传递，配合空列表兜底即可。
 */
object PlaybackQueue {
    var paths: List<String> = emptyList()
        private set
    var title: String = ""
        private set

    fun set(
        paths: List<String>,
        title: String,
    ) {
        this.paths = paths.filter { File(it).exists() }
        this.title = title
    }

    fun clear() {
        paths = emptyList()
        title = ""
    }
}

/**
 * 极简全屏播放器：黑底、单击暂停/继续、顶部一条细进度。
 * 多条素材会按顺序自动连播，等于「不合成也能看今天的日志」。
 */
@Composable
fun VideoPlayerRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val paths = remember { PlaybackQueue.paths }
    val title = remember { PlaybackQueue.title }

    if (paths.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // 变量名刻意不叫 player：PlayerView 自身有同名属性，在 apply 块里会把外层变量遮蔽掉
    val exoPlayer =
        remember {
            ExoPlayer.Builder(context).build().apply {
                setMediaItems(paths.map { MediaItem.fromUri(File(it).toURI().toString()) })
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
                playWhenReady = true
            }
        }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0)
            val total = exoPlayer.duration
            durationMs = if (total > 0) total else 0
            progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            delay(120)
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).also { view ->
                    view.useController = false
                    view.setShutterBackgroundColor(android.graphics.Color.BLACK)
                    view.player = exoPlayer
                }
            },
        )

        // 点击画面任意处切换播放状态，和短视频产品的手感一致
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
            contentAlignment = Alignment.Center,
        ) {
            if (!isPlaying) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MediaTokens.onMedia,
                    modifier = Modifier.size(64.dp),
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(2.dp)
                        .background(MediaTokens.trackIdle),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(progress)
                            .height(2.dp)
                            .background(MediaTokens.onMedia),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = MediaTokens.onMedia)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MediaTokens.onMedia,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${DailyLogStats.formatDuration(positionMs)} / ${DailyLogStats.formatDuration(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTokens.onMediaMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }

    }
}
