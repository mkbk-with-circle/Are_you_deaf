package com.nierduolong.morningbell.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

/**
 * Material 默认字距是按拉丁文调的，中文按它排会显得松散，像样例工程。
 * 这里统一收紧字距、把标题压重一档；正文行距放松一点，中文才透气。
 *
 * 会出现数字列的样式一律开 tnum（等宽数字），
 * 否则合成进度从 9% 跳到 10%、时长从 0:09 跳到 0:10 时整行都在抖。
 */
val Typography =
    Base.copy(
        headlineMedium =
            Base.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
        headlineSmall =
            Base.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                lineHeight = 30.sp,
            ),
        titleLarge =
            Base.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            ),
        titleMedium =
            Base.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                fontFeatureSettings = "tnum",
            ),
        titleSmall =
            Base.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            ),
        bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.1.sp),
        bodyMedium = Base.bodyMedium.copy(lineHeight = 22.sp, letterSpacing = 0.1.sp),
        // 12sp 的中文在正文位置偏小，抬到 13sp
        bodySmall = Base.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.1.sp),
        labelLarge =
            Base.labelLarge.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
                fontFeatureSettings = "tnum",
            ),
        labelMedium =
            Base.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                fontFeatureSettings = "tnum",
            ),
        labelSmall =
            Base.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                fontFeatureSettings = "tnum",
            ),
    )
