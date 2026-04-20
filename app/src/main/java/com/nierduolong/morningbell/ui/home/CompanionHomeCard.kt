package com.nierduolong.morningbell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.core.CompanionLogic
import com.nierduolong.morningbell.data.db.MoodEntity
import com.nierduolong.morningbell.data.db.WakeDayEntity
import java.time.LocalDate

/** 首页轻陪伴：渐变背景随心情档位略变，形象用 emoji + 字号表达「长大」 */
@Composable
fun CompanionHomeCard(
    wakes: List<WakeDayEntity>,
    moods: List<MoodEntity>,
) {
    val today = LocalDate.now().toEpochDay()
    val p =
        remember(wakes, moods, today) {
            CompanionLogic.computeToday(today, wakes, moods)
        }
    val base = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    val scrim = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val brush =
        remember(p.moodBackgroundTier, base, primary, tertiary) {
            when (p.moodBackgroundTier) {
                0 ->
                    Brush.verticalGradient(
                        listOf(base, scrim),
                    )
                1 ->
                    Brush.verticalGradient(
                        listOf(
                            base,
                            primary.copy(alpha = 0.45f),
                            scrim,
                        ),
                    )
                2 ->
                    Brush.verticalGradient(
                        listOf(
                            tertiary.copy(alpha = 0.55f),
                            primary.copy(alpha = 0.35f),
                            scrim,
                        ),
                    )
                else ->
                    Brush.verticalGradient(
                        listOf(
                            primary.copy(alpha = 0.65f),
                            tertiary.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.25f),
                            scrim,
                        ),
                    )
            }
        }
    val emojiScale = (1f + p.stageIndex * 0.07f).coerceIn(1f, 1.35f)
    val emojiSp = (40 * emojiScale).sp

    val shape = MaterialTheme.shapes.extraLarge
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(brush)
                    .padding(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        p.primaryEmoji,
                        fontSize = emojiSp,
                        textAlign = TextAlign.Center,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(R.string.companion_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        p.mainLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    p.subLine?.let { s ->
                        Text(
                            s,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(
                            R.string.companion_card_footer,
                            p.moodBackgroundTier,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
