package com.nierduolong.morningbell.ui.dailylog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.core.DailyLogStats
import com.nierduolong.morningbell.dailylog.ThumbnailStore
import com.nierduolong.morningbell.ui.theme.MediaTokens
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「今天」是随时钟变化的量。App 长时间停在前台跨过零点时，
 * 如果只在进入页面时算一次，用户会把新一天的素材记到昨天头上，所以每分钟复核一次。
 */
@Composable
fun rememberTodayEpochDay(): Long {
    var today by remember { mutableStateOf(LocalDate.now().toEpochDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            today = LocalDate.now().toEpochDay()
        }
    }
    return today
}

/** 归档与详情页共用的日期文案 */
fun formatDayLabel(dayEpoch: Long): String =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA).format(LocalDate.ofEpochDay(dayEpoch))

fun formatDayShort(dayEpoch: Long): String = DateTimeFormatter.ofPattern("M/d").format(LocalDate.ofEpochDay(dayEpoch))

/**
 * 视频首帧缩略图。解码是异步的，加载中显示中性底色占位，
 * 避免网格滚动时出现闪白或撑开布局。
 *
 * 时长不套黑胶囊：胶囊会在画面上多出一块几何形状，
 * 底部一道渐变就够压住白字，画面本身不被切碎。
 */
@Composable
fun VideoThumbnail(
    path: String,
    modifier: Modifier = Modifier,
    durationMs: Long? = null,
) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) { bitmap = ThumbnailStore.load(context, path) }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (durationMs != null && durationMs > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(MediaTokens.bottomScrim),
            )
            Text(
                DailyLogStats.formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MediaTokens.onMedia,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
