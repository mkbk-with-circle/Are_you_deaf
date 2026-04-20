package com.nierduolong.morningbell.ui.dismiss

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.nierduolong.morningbell.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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

    /** 今日小任务：文案 + 完成后的反馈句（仅内存展示） */
    data class MicroTask(
        val slot: AppRepository.MicroTaskFlowSlot,
        val praiseLine: String?,
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

    var microSlot by remember(alarmId) { mutableStateOf<AppRepository.MicroTaskFlowSlot?>(null) }
    var microPraise by remember(alarmId) { mutableStateOf<String?>(null) }
    LaunchedEffect(m, alarmId) {
        microSlot = m.microTask
        microPraise = null
    }
    val micro = microSlot ?: m.microTask

    val pages =
        remember(m.birthdayCards, m.sticky, micro, microPraise) {
            buildList {
                m.birthdayCards.forEach { add(Page.Birthday(it)) }
                add(Page.Sticky(m.sticky))
                add(Page.MicroTask(micro, microPraise))
                add(Page.Mood)
            }
        }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "闹钟已关闭 · 上下滑动浏览卡片（共 ${pages.size} 张）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        VerticalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp),
            pageSpacing = 12.dp,
        ) { index ->
            when (val page = pages[index]) {
                is Page.Birthday ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 4 },
                    ) {
                        BirthdayReminderCard(page.card)
                    }

                is Page.Sticky ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 4 },
                    ) {
                        StickyPayloadCard(
                            payload = page.payload,
                            onMarkGoalComplete = { goalId ->
                                scope.launch {
                                    repo.markGoalCompleted(goalId)
                                }
                            },
                        )
                    }

                is Page.MicroTask ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 4 },
                    ) {
                        MorningMicroTaskCard(
                            slot = page.slot,
                            praiseLine = page.praiseLine,
                            onComplete = {
                                scope.launch {
                                    val r = repo.completeTodayMicroTask()
                                    microSlot = r.slot
                                    microPraise = r.praise
                                }
                            },
                            onSwap = {
                                scope.launch {
                                    microSlot = repo.swapTodayMicroTask()
                                    microPraise = null
                                }
                            },
                        )
                    }

                is Page.Mood ->
                    MoodPickCard(
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
            text = "进度 ${pagerState.currentPage + 1} / ${pages.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun MorningMicroTaskCard(
    slot: AppRepository.MicroTaskFlowSlot,
    praiseLine: String?,
    onComplete: () -> Unit,
    onSwap: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.micro_task_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                slot.taskText,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (slot.completed) {
                Text(
                    praiseLine ?: stringResource(R.string.micro_task_already_done_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.micro_task_done_one_tap))
                    }
                    TextButton(onClick = onSwap) {
                        Text(stringResource(R.string.micro_task_swap))
                    }
                }
                Text(
                    stringResource(R.string.micro_task_card_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun BirthdayReminderCard(card: BirthdayReminderLogic.DueCard) {
    val highlight = card.isBirthDay
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (highlight) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (highlight) "今天生日 · ${card.name}" else "生日提醒 · ${card.name}",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(card.todoText, style = MaterialTheme.typography.bodyLarge)
            if (!highlight) {
                Text("提前 ${card.daysBefore} 天", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun StickyPayloadCard(
    payload: AppRepository.StickyPayload,
    onMarkGoalComplete: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.dismiss_sticky_title),
                style = MaterialTheme.typography.titleMedium,
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
                            color = MaterialTheme.colorScheme.secondary,
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
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        payload.packTagline,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(payload.text, style = MaterialTheme.typography.bodyLarge)
                }

                is AppRepository.StickyPayload.WeatherSticky ->
                    Text(payload.line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MoodPickCard(onPick: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("今天几分？", style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dismiss_mood_chart_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RowButtonsRow((1..3).toList(), onPick)
                RowButtonsRow((4..5).toList(), onPick)
            }
        }
    }
}

@Composable
private fun RowButtonsRow(
    scores: List<Int>,
    onPick: (Int) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        scores.forEach { s ->
            Button(
                onClick = { onPick(s) },
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp)),
            ) {
                Text(s.toString())
            }
        }
    }
}
