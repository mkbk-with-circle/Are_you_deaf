package com.nierduolong.morningbell.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 压在视频画面上的颜色不能跟着明暗主题走：缩略图深浅不定，
 * 文字必须永远是白的，衬底必须永远是黑的。
 *
 * 单独成一套 token 是因为这些值原本散在五个页面里，
 * 同一个「压住画面的黑」被写成 0.40 / 0.42 / 0.45 三种。
 */
object MediaTokens {
    /** 画面上的主文字与图标 */
    val onMedia = Color.White

    /** 次级文字：时间、时长 */
    val onMediaMuted = Color.White.copy(alpha = 0.72f)

    /** 不可用状态（录制中不许切镜头） */
    val onMediaDisabled = Color.White.copy(alpha = 0.30f)

    /** 圆形播放键、录制计时胶囊等实心衬底 */
    val scrim = Color.Black.copy(alpha = 0.42f)

    /** 缩略图底部渐变：只压住文字那一条，不糊整张画面 */
    val bottomScrim =
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
        )

    /** 录制红，对齐系统录屏指示灯 */
    val record = Color(0xFFFF3B30)

    /** 播放进度条底槽 */
    val trackIdle = Color.White.copy(alpha = 0.25f)
}
