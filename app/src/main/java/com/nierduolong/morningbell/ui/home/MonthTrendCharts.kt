package com.nierduolong.morningbell.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.data.db.MoodEntity
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

/** 当月心情折线（扁平分区，不套卡片） */
@Composable
fun MonthlyMoodTrendSection(moods: List<MoodEntity>) {
    val ym = YearMonth.now()
    val byDay = moodsInMonth(moods, ym)
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.home_chart_mood_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (byDay.isEmpty()) {
            Text(
                stringResource(R.string.home_chart_mood_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.home_chart_mood_axis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            MoodLineCanvas(
                byDay,
                ym.lengthOfMonth(),
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

private fun moodsInMonth(
    moods: List<MoodEntity>,
    ym: YearMonth,
): Map<Int, Int> {
    val start = ym.atDay(1).toEpochDay()
    val end = ym.atEndOfMonth().toEpochDay()
    val map = LinkedHashMap<Int, Int>()
    moods
        .filter { it.dayEpoch in start..end }
        .sortedBy { it.dayEpoch }
        .forEach { m ->
            val day = LocalDate.ofEpochDay(m.dayEpoch).dayOfMonth
            map[day] = m.score
        }
    return map
}

@Composable
private fun MoodLineCanvas(
    byDay: Map<Int, Int>,
    daysInMonth: Int,
    lineColor: Color,
    gridColor: Color,
) {
    val stroke = Stroke(width = 3.5f, cap = StrokeCap.Round)
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(top = 4.dp),
    ) {
        val padL = 8f
        val padR = 8f
        val padT = 12f
        val padB = 16f
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        val stepX = w / max(1, daysInMonth - 1).toFloat()
        fun xForDay(d: Int) = padL + (d - 1).coerceIn(0, daysInMonth - 1) * stepX
        fun yForScore(s: Int) = padT + h * (1f - (s - 1) / 4f)

        for (s in 1..5) {
            val y = yForScore(s)
            drawLine(
                color = gridColor,
                start = Offset(padL, y),
                end = Offset(size.width - padR, y),
                strokeWidth = 1f,
            )
        }

        val sorted = byDay.entries.sortedBy { it.key }
        if (sorted.isEmpty()) return@Canvas
        val path = Path()
        sorted.forEachIndexed { i, e ->
            val ox = xForDay(e.key)
            val oy = yForScore(e.value.coerceIn(1, 5))
            if (i == 0) {
                path.moveTo(ox, oy)
            } else {
                path.lineTo(ox, oy)
            }
        }
        drawPath(path, color = lineColor, style = stroke)
        sorted.forEach { e ->
            val ox = xForDay(e.key)
            val oy = yForScore(e.value.coerceIn(1, 5))
            drawCircle(color = lineColor, radius = 5f, center = Offset(ox, oy))
            drawCircle(color = Color.White, radius = 2.5f, center = Offset(ox, oy))
        }
    }
}
