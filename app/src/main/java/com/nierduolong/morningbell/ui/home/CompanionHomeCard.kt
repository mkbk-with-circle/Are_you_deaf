package com.nierduolong.morningbell.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.core.CompanionLogic
import com.nierduolong.morningbell.data.db.MoodEntity
import java.time.LocalDate

/**
 * 轻陪伴：去掉抬升卡片与多彩渐变底。
 * 情绪档位只体现在副文案，emoji 字号仍随「长大」略变。
 */
@Composable
fun CompanionHomeCard(
    moods: List<MoodEntity>,
) {
    val today = LocalDate.now().toEpochDay()
    val p =
        remember(moods, today) {
            CompanionLogic.computeToday(today, moods)
        }
    val emojiScale = (1f + p.stageIndex * 0.07f).coerceIn(1f, 1.35f)
    val emojiSp = (40 * emojiScale).sp

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                p.primaryEmoji,
                fontSize = emojiSp,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(R.string.companion_card_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                p.mainLine,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            p.subLine?.let { s ->
                Text(
                    s,
                    style = MaterialTheme.typography.bodySmall,
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
