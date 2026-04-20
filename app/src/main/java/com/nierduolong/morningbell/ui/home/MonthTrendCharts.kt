package com.nierduolong.morningbell.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.nierduolong.morningbell.data.db.WakeDayEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max

/** 当月心情折线 */
@Composable
fun MonthlyMoodTrendCard(moods: List<MoodEntity>) {
    val ym = YearMonth.now()
    val zone = ZoneId.systemDefault()
    val byDay = moodsInMonth(moods, ym, zone)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                stringResource(R.string.home_chart_mood_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (byDay.isEmpty()) {
                Text(
                    stringResource(R.string.home_chart_mood_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.home_chart_mood_axis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(6.dp))
                MoodLineCanvas(byDay, ym.lengthOfMonth(), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 当月早起散点 */
@Composable
fun MonthlyWakeTrendCard(wakes: List<WakeDayEntity>) {
    val ym = YearMonth.now()
    val zone = ZoneId.systemDefault()
    val byDay = wakesInMonth(wakes, ym, zone)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(stringResource(R.string.home_chart_wake_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (byDay.isEmpty()) {
                Text(
                    stringResource(R.string.home_chart_wake_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.home_chart_axis_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(6.dp))
                WakeScatterCanvas(byDay, ym.lengthOfMonth(), MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

private fun moodsInMonth(
    moods: List<MoodEntity>,
    ym: YearMonth,
    zone: ZoneId,
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

private fun wakesInMonth(
    wakes: List<WakeDayEntity>,
    ym: YearMonth,
    zone: ZoneId,
): Map<Int, Int> {
    val start = ym.atDay(1).toEpochDay()
    val end = ym.atEndOfMonth().toEpochDay()
    val map = LinkedHashMap<Int, Int>()
    wakes
        .filter { it.dayEpoch in start..end }
        .sortedBy { it.dayEpoch }
        .forEach { w ->
            val day = LocalDate.ofEpochDay(w.dayEpoch).dayOfMonth
            val minute =
                Instant.ofEpochMilli(w.firstUnlockMillis)
                    .atZone(zone)
                    .toLocalTime()
                    .let { it.hour * 60 + it.minute }
            map[day] = minute
        }
    return map
}

@Composable
private fun MoodLineCanvas(
    byDay: Map<Int, Int>,
    daysInMonth: Int,
    lineColor: Color,
) {
    val stroke = Stroke(width = 4f, cap = StrokeCap.Round)
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(160.dp),
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
                color = Color.Gray.copy(alpha = 0.25f),
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
            drawCircle(color = lineColor, radius = 6f, center = Offset(ox, oy))
            drawCircle(color = Color.White, radius = 3f, center = Offset(ox, oy))
        }
    }
}

@Composable
private fun WakeScatterCanvas(
    byDay: Map<Int, Int>,
    daysInMonth: Int,
    dotColor: Color,
) {
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(180.dp),
    ) {
        val padL = 8f
        val padR = 8f
        val padT = 8f
        val padB = 8f
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        val stepX = w / max(1, daysInMonth - 1).toFloat()
        val minM = 4 * 60
        val maxM = 12 * 60
        fun xForDay(d: Int) = padL + (d - 1).coerceIn(0, daysInMonth - 1) * stepX
        fun yForMinute(m: Int): Float {
            val t = ((m - minM).toFloat() / (maxM - minM).toFloat()).coerceIn(0f, 1f)
            return padT + h * t
        }

        drawLine(
            color = Color.Gray.copy(alpha = 0.35f),
            start = Offset(padL, yForMinute(6 * 60)),
            end = Offset(size.width - padR, yForMinute(6 * 60)),
            strokeWidth = 1f,
        )

        byDay.forEach { (day, minute) ->
            val ox = xForDay(day)
            val oy = yForMinute(minute)
            drawCircle(color = dotColor.copy(alpha = 0.9f), radius = 7f, center = Offset(ox, oy))
        }
    }
}
