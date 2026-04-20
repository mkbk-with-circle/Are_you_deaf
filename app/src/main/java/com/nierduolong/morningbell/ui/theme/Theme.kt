package com.nierduolong.morningbell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 薄荷主色 + 蜜桃点缀 + 奶油底，偏活力小清新
private val Mint = Color(0xFF2DB8A8)
private val MintContainer = Color(0xFFC8F5EE)
private val Peach = Color(0xFFFF8A7A)
private val PeachContainer = Color(0xFFFFE8E3)
private val Lilac = Color(0xFFB8A9FF)
private val LilacContainer = Color(0xFFEDE9FF)
private val Cream = Color(0xFFFFFBF7)
private val Paper = Color(0xFFFFFEFE)
private val Ink = Color(0xFF2C3138)
private val Muted = Color(0xFF6B7280)

private val Light =
    lightColorScheme(
        primary = Mint,
        onPrimary = Color.White,
        primaryContainer = MintContainer,
        onPrimaryContainer = Color(0xFF004239),
        secondary = Peach,
        onSecondary = Color.White,
        secondaryContainer = PeachContainer,
        onSecondaryContainer = Color(0xFF5C2118),
        tertiary = Lilac,
        onTertiary = Color.White,
        tertiaryContainer = LilacContainer,
        onTertiaryContainer = Color(0xFF2F2654),
        background = Cream,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = Color(0xFFEFF4F3),
        onSurfaceVariant = Muted,
        outline = Color(0xFFC8D0CE),
        outlineVariant = Color(0xFFE2E8E6),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
    )

private val Dark =
    darkColorScheme(
        primary = Color(0xFF5EEAD4),
        onPrimary = Color(0xFF003731),
        primaryContainer = Color(0xFF005048),
        onPrimaryContainer = Color(0xFF8FF5E6),
        secondary = Color(0xFFFFB4A8),
        onSecondary = Color(0xFF5C1F18),
        secondaryContainer = Color(0xFF7C2D24),
        onSecondaryContainer = Color(0xFFFFDAD4),
        tertiary = Color(0xFFCBBEFF),
        onTertiary = Color(0xFF2F2654),
        tertiaryContainer = Color(0xFF463F69),
        onTertiaryContainer = Color(0xFFE8DEFF),
        background = Color(0xFF121816),
        onBackground = Color(0xFFE6EFEC),
        surface = Color(0xFF121816),
        onSurface = Color(0xFFE6EFEC),
        surfaceVariant = Color(0xFF3F4946),
        onSurfaceVariant = Color(0xFFBFC9C6),
        outline = Color(0xFF899390),
        outlineVariant = Color(0xFF3F4946),
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
