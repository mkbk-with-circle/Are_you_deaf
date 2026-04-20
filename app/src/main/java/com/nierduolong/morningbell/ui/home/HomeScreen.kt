@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.nierduolong.morningbell.ui.home

import android.content.Context
import android.text.format.DateFormat
import android.widget.NumberPicker
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nierduolong.morningbell.core.AlarmTimeCalculator
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.data.db.AlarmEntity
import com.nierduolong.morningbell.data.db.ChainAlarmGroupEntity
import com.nierduolong.morningbell.data.db.ChainAlarmStepEntity
import com.nierduolong.morningbell.ui.common.RowFields
import com.nierduolong.morningbell.util.AudioPickerUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeRoute(
    repo: AppRepository,
    onOpenSettings: () -> Unit,
    onOpenMood: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val alarms by repo.alarmFlow.collectAsState(initial = emptyList())
    val chainGroups by repo.chainGroupFlow.collectAsState(initial = emptyList())
    val chainSteps by repo.chainStepFlow.collectAsState(initial = emptyList())
    val chainUi =
        remember(chainGroups, chainSteps) {
            chainGroups.map { g ->
                ChainGroupListRow(
                    group = g,
                    steps = chainSteps.filter { it.groupId == g.id }.sortedBy { it.stepIndex },
                )
            }
        }

    var showTypePicker by remember { mutableStateOf(false) }
    var showSingleEditor by remember { mutableStateOf(false) }
    var showChainEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AlarmEntity?>(null) }
    var editingChainGroupId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.home_title_alarm_focus),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                actions = {
                    TextButton(onClick = onOpenMood) { Text(stringResource(R.string.nav_mood)) }
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.nav_settings)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    showTypePicker = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 5.dp),
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (alarms.isEmpty() && chainUi.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.home_alarm_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(alarms, key = { it.id }) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    defaultRingtoneLabel = stringResource(R.string.ringtone_default),
                    onEdit = {
                        editing = alarm
                        showSingleEditor = true
                    },
                    onDelete = {
                        scope.launch { repo.deleteAlarm(alarm.id) }
                    },
                    onToggle = { enabled ->
                        scope.launch {
                            repo.upsertAlarm(alarm.copy(enabled = enabled))
                        }
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.chain_alarm_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
            }
            if (chainUi.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.chain_alarm_list_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            } else {
                items(chainUi, key = { it.group.id }) { row ->
                    ChainAlarmCard(
                        row = row,
                        defaultRingtoneLabel = stringResource(R.string.ringtone_default),
                        onEdit = {
                            editingChainGroupId = row.group.id
                            showChainEditor = true
                        },
                        onDelete = {
                            scope.launch { repo.deleteChainGroup(row.group.id) }
                        },
                        onToggle = { en ->
                            scope.launch {
                                repo.setChainGroupEnabled(row.group.id, en)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showTypePicker) {
        AlertDialog(
            onDismissRequest = { showTypePicker = false },
            title = { Text(stringResource(R.string.home_new_alarm_menu_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showTypePicker = false
                            editing = null
                            showSingleEditor = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_new_alarm_single))
                    }
                    TextButton(
                        onClick = {
                            showTypePicker = false
                            editingChainGroupId = null
                            showChainEditor = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_new_alarm_chain))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTypePicker = false }) {
                    Text(stringResource(R.string.alarm_delete_cancel))
                }
            },
        )
    }

    if (showSingleEditor) {
        AlarmEditorDialog(
            initial = editing,
            onDismiss = { showSingleEditor = false },
            onSave = { entity ->
                scope.launch {
                    repo.upsertAlarm(entity)
                    showSingleEditor = false
                }
            },
        )
    }

    if (showChainEditor) {
        ChainAlarmEditorDialog(
            repo = repo,
            editingGroupId = editingChainGroupId,
            onDismiss = {
                showChainEditor = false
                editingChainGroupId = null
            },
            onSaved = {
                showChainEditor = false
                editingChainGroupId = null
            },
        )
    }
}

private data class ChainGroupListRow(
    val group: ChainAlarmGroupEntity,
    val steps: List<ChainAlarmStepEntity>,
)

@Composable
private fun ChainAlarmCard(
    row: ChainGroupListRow,
    defaultRingtoneLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val g = row.group
    var showDeleteConfirm by remember(g.id) { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.alarm_delete_confirm_title)) },
            text = { Text(stringResource(R.string.chain_alarm_delete)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.alarm_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.alarm_delete_cancel))
                }
            },
        )
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onEdit,
                    onLongClick = { showDeleteConfirm = true },
                ),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                stringResource(
                    R.string.chain_alarm_steps_label,
                    row.steps.size,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "铃声：" +
                    AudioPickerUtils.ringtoneSummary(ctx, g.soundUri, defaultRingtoneLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            row.steps.forEach { s ->
                Text(
                    stringResource(
                        R.string.chain_alarm_step_row,
                        s.stepIndex + 1,
                        s.hour,
                        s.minute,
                        if (s.silent) {
                            stringResource(R.string.chain_alarm_silent_yes)
                        } else {
                            stringResource(R.string.chain_alarm_silent_no)
                        },
                        if (s.vibrate) {
                            stringResource(R.string.chain_alarm_vibrate_yes)
                        } else {
                            stringResource(R.string.chain_alarm_vibrate_no)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (g.note.isNotBlank()) {
                Text(g.note, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_alarm_enable), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = g.enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: AlarmEntity,
    defaultRingtoneLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var showDeleteConfirm by remember(alarm.id) { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.alarm_delete_confirm_title)) },
            text = { Text(stringResource(R.string.alarm_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.alarm_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.alarm_delete_cancel))
                }
            },
        )
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onEdit,
                    onLongClick = { showDeleteConfirm = true },
                ),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "%02d:%02d · %s".format(alarm.hour, alarm.minute, if (alarm.silent) "无声" else "有声"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "铃声：" +
                    AudioPickerUtils.ringtoneSummary(ctx, alarm.soundUri, defaultRingtoneLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (alarm.note.isNotBlank()) {
                Text(alarm.note, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_alarm_enable), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun AlarmEditorDialog(
    initial: AlarmEntity?,
    onDismiss: () -> Unit,
    onSave: (AlarmEntity) -> Unit,
) {
    val context = LocalContext.current
    val defaultRingtoneLabel = stringResource(R.string.ringtone_default)

    val key = initial?.id ?: -1L
    // 用字符串保存输入，允许临时清空；勿用 toIntOrNull()?.let 否则删空时无法更新状态
    var hourText by remember(key) { mutableStateOf((initial?.hour ?: 7).toString()) }
    var minuteText by remember(key) { mutableStateOf((initial?.minute ?: 0).toString()) }
    var silent by remember(key) { mutableStateOf(initial?.silent ?: false) }
    var note by remember(key) { mutableStateOf(initial?.note ?: "") }
    var soundUri by remember(key) { mutableStateOf(initial?.soundUri) }
    var daysSet by remember(key) {
        mutableStateOf(AlarmTimeCalculator.parseRepeatDays(initial?.repeatDays ?: "0,1,2,3,4,5,6"))
    }
    // 与系统「使用 24 小时格式」一致作为默认；可随时在弹窗内切换仅影响展示
    var use24Hour by remember(key) {
        mutableStateOf(DateFormat.is24HourFormat(context))
    }

    val pickAudio =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri ->
                if (uri != null) {
                    AudioPickerUtils.takePersistableReadPermission(context.contentResolver, uri)
                    soundUri = uri.toString()
                }
            },
        )

    val audioMimeTypes =
        remember {
            arrayOf(
                "audio/mpeg",
                "audio/mp3",
                "audio/mp4",
                "audio/x-wav",
                "audio/x-ms-wma",
                "audio/m4a",
                "audio/flac",
                "audio/*",
            )
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = canSaveAlarmClockInput(hourText, minuteText),
                onClick = {
                    // 空白视为 0；合法输入已在启用保存时保证，此处再钳制一层
                    val hourNum = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val minuteNum = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val entity =
                        AlarmEntity(
                            id = initial?.id ?: 0L,
                            hour = hourNum,
                            minute = minuteNum,
                            enabled = true,
                            silent = silent,
                            note = note.trim(),
                            soundUri = soundUri,
                            repeatDays =
                                if (daysSet.isEmpty()) {
                                    "0,1,2,3,4,5,6"
                                } else {
                                    AlarmTimeCalculator.formatRepeatDays(daysSet)
                                },
                        )
                    onSave(entity)
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(if (initial == null) "新建闹钟" else "编辑闹钟") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val hourErr =
                    hourText.isNotEmpty() && !isClockHourInputValid(hourText)
                val minuteErr =
                    minuteText.isNotEmpty() && !isClockMinuteInputValid(minuteText)
                Text(
                    stringResource(R.string.alarm_time_wheel_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = use24Hour,
                        onClick = { use24Hour = true },
                        label = { Text(stringResource(R.string.alarm_time_mode_24)) },
                    )
                    FilterChip(
                        selected = !use24Hour,
                        onClick = { use24Hour = false },
                        label = { Text(stringResource(R.string.alarm_time_mode_12)) },
                    )
                }
                if (!use24Hour) {
                    val h24s = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val mns = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    Text(
                        stringResource(R.string.alarm_time_equiv_24, h24s, mns),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                AlarmTimePickers(
                    use24Hour = use24Hour,
                    hourText = hourText,
                    minuteText = minuteText,
                    onHourTextChange = { hourText = it },
                    onMinuteTextChange = { minuteText = it },
                )
                if (!use24Hour) {
                    val h24p = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val (h12, isPm) = AlarmTime12h.hour12AndPeriod(h24p)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !isPm,
                            onClick = {
                                hourText = AlarmTime12h.to24h(h12, false).toString()
                            },
                            label = { Text(stringResource(R.string.alarm_period_am)) },
                        )
                        FilterChip(
                            selected = isPm,
                            onClick = {
                                hourText = AlarmTime12h.to24h(h12, true).toString()
                            },
                            label = { Text(stringResource(R.string.alarm_period_pm)) },
                        )
                    }
                }
                RowFields(
                    hourText = hourText,
                    minuteText = minuteText,
                    onHourTextChange = { hourText = it },
                    onMinuteTextChange = { minuteText = it },
                    hourError = hourErr,
                    minuteError = minuteErr,
                    hourSupportingText =
                        if (hourErr) stringResource(R.string.alarm_time_hour_error) else null,
                    minuteSupportingText =
                        if (minuteErr) stringResource(R.string.alarm_time_minute_error) else null,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("无声闹钟")
                    Switch(checked = silent, onCheckedChange = { silent = it })
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        AudioPickerUtils.ringtoneSummary(context, soundUri, defaultRingtoneLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { pickAudio.launch(audioMimeTypes) },
                            enabled = !silent,
                        ) {
                            Text(stringResource(R.string.ringtone_pick))
                        }
                        TextButton(
                            onClick = { soundUri = null },
                            enabled = !silent && soundUri != null,
                        ) {
                            Text(stringResource(R.string.ringtone_clear))
                        }
                    }
                    if (silent) {
                        Text(
                            "无声模式下不会播放铃声；铃声音频仍会保存以便关闭无声后使用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") })
                Text("重复（周日→周六）", style = MaterialTheme.typography.labelMedium)
                WeekdayPicker(daysSet) {
                    daysSet = it
                }
            }
        },
    )
}

private fun isClockHourInputValid(text: String): Boolean =
    text.isEmpty() || (text.toIntOrNull()?.let { it in 0..23 } == true)

private fun isClockMinuteInputValid(text: String): Boolean =
    text.isEmpty() || (text.toIntOrNull()?.let { it in 0..59 } == true)

private fun canSaveAlarmClockInput(hourText: String, minuteText: String): Boolean =
    isClockHourInputValid(hourText) && isClockMinuteInputValid(minuteText)

/** 12 小时制（1–12 + 上/下午）与 24 小时制互转，存库仍为 0–23 */
private object AlarmTime12h {
    fun hour12AndPeriod(hour24: Int): Pair<Int, Boolean> {
        val h = hour24.coerceIn(0, 23)
        val isPm = h >= 12
        val h12 =
            when {
                h == 0 -> 12
                h <= 11 -> h
                h == 12 -> 12
                else -> h - 12
            }
        return h12 to isPm
    }

    fun to24h(hour12: Int, isPm: Boolean): Int {
        val h = hour12.coerceIn(1, 12)
        return when {
            !isPm && h == 12 -> 0
            !isPm -> h
            isPm && h == 12 -> 12
            else -> h + 12
        }
    }
}

/** 时、分滚轮：24 小时为 0–23 时；12 小时为 1–12 时 + 下方上午/下午芯片 */
@Composable
private fun AlarmTimePickers(
    use24Hour: Boolean,
    hourText: String,
    minuteText: String,
    onHourTextChange: (String) -> Unit,
    onMinuteTextChange: (String) -> Unit,
) {
    val onHour by rememberUpdatedState(onHourTextChange)
    val onMinute by rememberUpdatedState(onMinuteTextChange)
    val hourTextSync by rememberUpdatedState(hourText)
    val use24Sync by rememberUpdatedState(use24Hour)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        AndroidView(
            modifier =
                Modifier
                    .weight(1f)
                    .height(180.dp),
            factory = { ctx ->
                NumberPicker(ctx).apply {
                    minValue = 0
                    maxValue = 23
                    wrapSelectorWheel = true
                    setOnValueChangedListener { _, _, newVal ->
                        if (use24Sync) {
                            onHour(newVal.toString())
                        } else {
                            val h24cur = hourTextSync.toIntOrNull()?.coerceIn(0, 23) ?: 0
                            val (_, isPm) = AlarmTime12h.hour12AndPeriod(h24cur)
                            onHour(AlarmTime12h.to24h(newVal, isPm).toString())
                        }
                    }
                }
            },
            update = { picker ->
                if (use24Hour) {
                    if (picker.minValue != 0 || picker.maxValue != 23) {
                        picker.minValue = 0
                        picker.maxValue = 23
                    }
                    hourText.toIntOrNull()?.takeIf { it in 0..23 }?.let { v ->
                        if (picker.value != v) picker.value = v
                    }
                } else {
                    if (picker.minValue != 1 || picker.maxValue != 12) {
                        picker.minValue = 1
                        picker.maxValue = 12
                    }
                    val h24 = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val (h12, _) = AlarmTime12h.hour12AndPeriod(h24)
                    if (picker.value != h12) picker.value = h12
                }
            },
        )
        Text(
            ":",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        AndroidView(
            modifier =
                Modifier
                    .weight(1f)
                    .height(180.dp),
            factory = { ctx ->
                NumberPicker(ctx).apply {
                    minValue = 0
                    maxValue = 59
                    wrapSelectorWheel = true
                    setOnValueChangedListener { _, _, newVal ->
                        onMinute(newVal.toString())
                    }
                }
            },
            update = { picker ->
                minuteText.toIntOrNull()?.takeIf { it in 0..59 }?.let { v ->
                    if (picker.value != v) picker.value = v
                }
            },
        )
    }
}

private val weekdayLabels = listOf("日", "一", "二", "三", "四", "五", "六")

@Composable
private fun WeekdayPicker(
    selected: Set<Int>,
    onChange: (Set<Int>) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        weekdayLabels.forEachIndexed { index, label ->
            val on = selected.contains(index)
            FilterChip(
                selected = on,
                onClick = {
                    val next = selected.toMutableSet()
                    if (on) next.remove(index) else next.add(index)
                    onChange(next)
                },
                label = { Text(label) },
            )
        }
    }
}
