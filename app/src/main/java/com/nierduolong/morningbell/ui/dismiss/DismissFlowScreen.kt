package com.nierduolong.morningbell.ui.dismiss

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.core.BirthdayReminderLogic
import com.nierduolong.morningbell.data.AppRepository
import kotlinx.coroutines.launch

private sealed interface Page {
    data class Birthday(
        val card: BirthdayReminderLogic.DueCard,
    ) : Page

    data class Sticky(
        val payload: AppRepository.StickyPayload,
    ) : Page

    data object Mood : Page
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DismissFlowRoute(
    repo: AppRepository,
    alarmId: Long,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf<AppRepository.DismissFlowModel?>(null) }

    LaunchedEffect(alarmId) {
        model = repo.buildDismissFlowCards()
    }

    val m = model
    if (m == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val pages =
        remember(m.birthdayCards, m.sticky) {
            buildList {
                m.birthdayCards.forEach { add(Page.Birthday(it)) }
                add(Page.Sticky(m.sticky))
                add(Page.Mood)
            }
        }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "闹钟已关闭 · 上下滑动浏览",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VerticalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            pageSpacing = 16.dp,
        ) { index ->
            when (val page = pages[index]) {
                is Page.Birthday ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 4 },
                    ) {
                        BirthdayReminderPanel(page.card)
                    }

                is Page.Sticky ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 4 },
                    ) {
                        StickyPayloadPanel(
                            payload = page.payload,
                            onMarkGoalComplete = { goalId ->
                                scope.launch {
                                    repo.markGoalCompleted(goalId)
                                }
                            },
                        )
                    }

                is Page.Mood ->
                    MoodPickPanel(
                        onPick = { score ->
                            scope.launch {
                                repo.insertMood(score)
                                onDone()
                            }
                        },
                    )
            }
        }
        Text(
            text = "${pagerState.currentPage + 1} / ${pages.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 发丝描边面板：取代抬升彩色卡，生日当天只在标题色上强调 */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun BirthdayReminderPanel(card: BirthdayReminderLogic.DueCard) {
    val highlight = card.isBirthDay
    Panel {
        Text(
            if (highlight) "今天生日" else "生日提醒",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            card.name,
            style = MaterialTheme.typography.headlineSmall,
            color =
                if (highlight) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        Text(card.todoText, style = MaterialTheme.typography.bodyLarge)
        if (!highlight) {
            Text(
                "提前 ${card.daysBefore} 天",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StickyPayloadPanel(
    payload: AppRepository.StickyPayload,
    onMarkGoalComplete: (Long) -> Unit,
) {
    Panel {
        Text(
            stringResource(R.string.dismiss_sticky_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (payload) {
            is AppRepository.StickyPayload.GoalSticky -> {
                var marked by remember(payload.goalId) { mutableStateOf(false) }
                Text(payload.title, style = MaterialTheme.typography.headlineSmall)
                val days = payload.daysUntil
                if (days != null) {
                    Text(
                        when {
                            days > 0 -> "距离截止还有 ${days} 天"
                            days == 0L -> "今天是截止日"
                            else -> "已超过截止 ${-days} 天"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (marked) {
                    Text(
                        stringResource(R.string.dismiss_goal_done_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(
                        onClick = {
                            onMarkGoalComplete(payload.goalId)
                            marked = true
                        },
                    ) {
                        Text(stringResource(R.string.dismiss_goal_done))
                    }
                }
            }

            is AppRepository.StickyPayload.QuoteSticky -> {
                Text(
                    stringResource(R.string.sticky_pack_badge, payload.cardTheme),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    payload.packTagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(payload.text, style = MaterialTheme.typography.bodyLarge)
            }

            is AppRepository.StickyPayload.WeatherSticky ->
                Text(payload.line, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun MoodPickPanel(onPick: (Int) -> Unit) {
    Panel {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("今天几分？", style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dismiss_mood_chart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..5).forEach { s ->
                    OutlinedButton(
                        onClick = { onPick(s) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(s.toString())
                    }
                }
            }
        }
    }
}
