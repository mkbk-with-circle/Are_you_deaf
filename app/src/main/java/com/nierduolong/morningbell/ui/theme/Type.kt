package com.nierduolong.morningbell.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

/** 在默认 Material 排版上微调：标题略重、正文行距略松 */
val Typography =
    Base.copy(
        titleLarge =
            Base.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            ),
        titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = Base.bodyMedium.copy(lineHeight = 22.sp),
        bodySmall = Base.bodySmall.copy(lineHeight = 19.sp),
        labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    )
