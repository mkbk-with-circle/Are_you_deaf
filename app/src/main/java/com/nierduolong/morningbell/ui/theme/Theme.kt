package com.nierduolong.morningbell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Hallmark · 结构: Photographic（满幅媒体网格 + 安静排版，不用卡片）
 * 语气: 简约社交 / 照片流 · 强调色: 薄荷 #17A398 · 画面上的颜色见 MediaTokens
 * 下一次改 UI 时换一种结构，不要再堆同一套格子
 */
// 简约社交风：白底 + 近黑文字 + 发丝级描边，颜色只留一点薄荷做强调（保留小芽的身份色）
private val Mint = Color(0xFF17A398)
private val MintContainer = Color(0xFFE7F6F4)
private val Peach = Color(0xFFF2765F)
private val PeachContainer = Color(0xFFFDEAE5)
private val Lilac = Color(0xFF8B7BE8)
private val LilacContainer = Color(0xFFEFECFC)

// 中性色阶对齐主流社交产品：纯白底、#737373 次级文字、#DBDBDB 描边
private val Paper = Color(0xFFFFFFFF)
private val Ink = Color(0xFF14161A)
private val Muted = Color(0xFF737373)
private val Hairline = Color(0xFFDBDBDB)
private val Divider = Color(0xFFEFEFEF)
private val Fill = Color(0xFFF5F5F5)

private val Light =
    lightColorScheme(
        primary = Mint,
        onPrimary = Color.White,
        primaryContainer = MintContainer,
        onPrimaryContainer = Color(0xFF063D37),
        secondary = Peach,
        onSecondary = Color.White,
        secondaryContainer = PeachContainer,
        onSecondaryContainer = Color(0xFF5C2118),
        tertiary = Lilac,
        onTertiary = Color.White,
        tertiaryContainer = LilacContainer,
        onTertiaryContainer = Color(0xFF2F2654),
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = Fill,
        onSurfaceVariant = Muted,
        outline = Hairline,
        outlineVariant = Divider,
        error = Color(0xFFD32F2F),
        onError = Color.White,
    )

// 深色用纯黑底，照片和视频缩略图在纯黑上最干净
private val Dark =
    darkColorScheme(
        primary = Color(0xFF4ED8C8),
        onPrimary = Color(0xFF00312B),
        primaryContainer = Color(0xFF16403A),
        onPrimaryContainer = Color(0xFFA6F2E7),
        secondary = Color(0xFFFF9E8B),
        onSecondary = Color(0xFF5C1F18),
        secondaryContainer = Color(0xFF5E2A22),
        onSecondaryContainer = Color(0xFFFFDAD4),
        tertiary = Color(0xFFBEB2FF),
        onTertiary = Color(0xFF2F2654),
        tertiaryContainer = Color(0xFF3B3460),
        onTertiaryContainer = Color(0xFFE8DEFF),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFAFAFA),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFAFAFA),
        surfaceVariant = Color(0xFF1A1A1A),
        onSurfaceVariant = Color(0xFFA8A8A8),
        outline = Color(0xFF363636),
        outlineVariant = Color(0xFF262626),
        error = Color(0xFFFF6B6B),
        onError = Color(0xFF3B0000),
    )

@Composable
fun MorningBellTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography,
        shapes = MorningBellShapes,
        content = content,
    )
}
