@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nierduolong.morningbell.ui.birthday

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.core.BirthdayReminderBuiltinTemplates
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.BirthdayEntity
import com.nierduolong.morningbell.data.db.BirthdayReminderEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayRoute(
    repo: AppRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val birthdays by repo.birthdayFlow.collectAsState(initial = emptyList())
    var showBirthdayDialog by remember { mutableStateOf(false) }
    var editingBirthday by remember { mutableStateOf<BirthdayEntity?>(null) }
    var reminderTarget by remember { mutableStateOf<BirthdayEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生日与提醒") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingBirthday = null
                    showBirthdayDialog = true
                },
            ) {
                Text("+")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "点某一行展开，查看并管理该人的提醒。关闭闹钟后的卡片流里会在触发日显示对应任务。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(birthdays, key = { it.id }) { b ->
                BirthdayCard(
                    birthday = b,
                    repo = repo,
                    onAddReminder = { reminderTarget = b },
                    onEditBirthday = {
                        editingBirthday = b
                        showBirthdayDialog = true
                    },
                    onDeleteBirthday = {
                        scope.launch { repo.deleteBirthday(b.id) }
                    },
                )
            }
        }
    }

    if (showBirthdayDialog) {
        BirthdayEditorDialog(
            initial = editingBirthday,
            onDismiss = {
                showBirthdayDialog = false
                editingBirthday = null
            },
            onSave = { entity ->
                scope.launch {
                    repo.upsertBirthday(entity)
                    showBirthdayDialog = false
                    editingBirthday = null
                }
            },
        )
    }

    val rt = reminderTarget
    if (rt != null) {
        ReminderEditorDialog(
            birthdayId = rt.id,
            repo = repo,
            builtinTemplates = BirthdayReminderBuiltinTemplates.defaults,
            onDismiss = { reminderTarget = null },
            onSave = { rem ->
                scope.launch {
                    repo.upsertReminder(rem)
                    reminderTarget = null
                }
            },
        )
    }
}

@Composable
private fun BirthdayCard(
    birthday: BirthdayEntity,
    repo: AppRepository,
    onAddReminder: () -> Unit,
    onEditBirthday: () -> Unit,
    onDeleteBirthday: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember(birthday.id) { mutableStateOf(false) }
    val reminders by repo.remindersForBirthdayFlow(birthday.id).collectAsState(initial = emptyList())

    val dateLabel =
        if (birthday.isLunar) {
            "农历 ${birthday.month} 月 ${birthday.day} 日（按年换算公历提醒）"
        } else {
            "公历 ${birthday.month} 月 ${birthday.day} 日"
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        birthday.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        dateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (expanded) "▼" else "▶",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (reminders.isEmpty()) {
                        Text(
                            "暂无提醒，点击下方添加",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        reminders.forEach { r ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "提前 ${r.daysBefore} 天 · ${r.todoText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        scope.launch { repo.deleteReminder(r.id) }
                                    },
                                ) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                    TextButton(onClick = onAddReminder) { Text("添加提醒条目") }
                    TextButton(onClick = onEditBirthday) { Text("编辑生日") }
                    TextButton(onClick = onDeleteBirthday) { Text("删除此人生日") }
                }
            }
        }
    }
}

@Composable
private fun BirthdayEditorDialog(
    initial: BirthdayEntity?,
    onDismiss: () -> Unit,
    onSave: (BirthdayEntity) -> Unit,
) {
    val key = initial?.id ?: -1L
    var name by remember(key) { mutableStateOf(initial?.name ?: "") }
    var month by remember(key) { mutableStateOf(initial?.month?.toString() ?: "1") }
    var day by remember(key) { mutableStateOf(initial?.day?.toString() ?: "1") }
    var isLunar by remember(key) { mutableStateOf(initial?.isLunar ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val m = month.toIntOrNull()?.coerceIn(1, 12) ?: 1
                    val d = day.toIntOrNull()?.coerceIn(1, 31) ?: 1
                    onSave(
                        BirthdayEntity(
                            id = initial?.id ?: 0L,
                            name = name.trim().ifBlank { "朋友" },
                            month = m,
                            day = d,
                            isLunar = isLunar,
                        ),
                    )
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(if (initial == null) "新建生日" else "编辑生日") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("姓名") })
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("按农历生日", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isLunar, onCheckedChange = { isLunar = it })
                }
                Text(
                    if (isLunar) "下面填写农历月、日（闰月暂用相邻月代替）" else "下面填写公历月、日",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                OutlinedTextField(value = month, onValueChange = { month = it }, label = { Text("月（1–12）") })
                OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("日（1–31）") })
            }
        },
    )
}

@Composable
private fun ReminderEditorDialog(
    birthdayId: Long,
    repo: AppRepository,
    builtinTemplates: List<String>,
    onDismiss: () -> Unit,
    onSave: (BirthdayReminderEntity) -> Unit,
) {
    var daysBefore by remember { mutableStateOf("1") }
    var todo by remember { mutableStateOf("") }
    var newTemplateText by remember { mutableStateOf("") }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }
    val customRows by repo.reminderTemplateFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val allTemplateLabels = remember(builtinTemplates, customRows) {
        builtinTemplates + customRows.map { it.text }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val d = daysBefore.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    onSave(
                        BirthdayReminderEntity(
                            birthdayId = birthdayId,
                            daysBefore = d,
                            todoText = todo.ifBlank { "提醒自己" },
                        ),
                    )
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("添加提醒") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = daysBefore,
                    onValueChange = { daysBefore = it },
                    label = { Text("提前几天（0 为生日当天早上）") },
                )
                Text("快速填入", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    allTemplateLabels.distinct().forEach { label ->
                        FilterChip(
                            selected = false,
                            onClick = { todo = label },
                            label = { Text(label) },
                        )
                    }
                }
                if (customRows.isNotEmpty()) {
                    Text("我的模版", style = MaterialTheme.typography.labelMedium)
                    customRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    scope.launch { repo.deleteReminderTemplate(row.id) }
                                },
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = todo,
                    onValueChange = { todo = it },
                    label = { Text("要做什么") },
                )
                TextButton(
                    onClick = { showSaveTemplateDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存当前文案为常用模版…")
                }
            }
        },
    )

    if (showSaveTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showSaveTemplateDialog = false },
            title = { Text("新增常用模版") },
            text = {
                OutlinedTextField(
                    value = newTemplateText,
                    onValueChange = { newTemplateText = it },
                    label = { Text("模版文案") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = newTemplateText.trim()
                        if (t.isNotEmpty()) {
                            scope.launch {
                                repo.insertReminderTemplate(t)
                                newTemplateText = ""
                                showSaveTemplateDialog = false
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTemplateDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}
