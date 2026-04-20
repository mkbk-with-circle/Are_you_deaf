package com.nierduolong.morningbell.ui.birthday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            FloatingActionButton(onClick = { showBirthdayDialog = true }) {
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
                    "为每位朋友添加生日与多条「提前几天要做什么」。触发日早上关闭闹钟后，卡片流里会出现对应提醒。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(birthdays, key = { it.id }) { b ->
                BirthdayCard(
                    birthday = b,
                    onAddReminder = {
                        reminderTarget = b
                    },
                    onDelete = {
                        scope.launch { repo.deleteBirthday(b.id) }
                    },
                    repo = repo,
                )
            }
        }
    }

    if (showBirthdayDialog) {
        BirthdayEditorDialog(
            initial = null,
            onDismiss = { showBirthdayDialog = false },
            onSave = { entity ->
                scope.launch {
                    repo.upsertBirthday(entity)
                    showBirthdayDialog = false
                }
            },
        )
    }

    val rt = reminderTarget
    if (rt != null) {
        ReminderEditorDialog(
            birthdayId = rt.id,
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
    onAddReminder: () -> Unit,
    onDelete: () -> Unit,
    repo: AppRepository,
) {
    val scope = rememberCoroutineScope()
    var reminders by remember(birthday.id) { mutableStateOf<List<BirthdayReminderEntity>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(birthday.id) {
        reminders = repo.loadReminders(birthday.id)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${birthday.name} · ${birthday.month} 月 ${birthday.day} 日",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            reminders.forEach { r ->
                Text(
                    "提前 ${r.daysBefore} 天 · ${r.todoText}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onAddReminder) { Text("添加提醒条目") }
            TextButton(
                onClick = {
                    scope.launch { onDelete() }
                },
            ) {
                Text("删除此人生日")
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
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var month by remember { mutableStateOf(initial?.month?.toString() ?: "1") }
    var day by remember { mutableStateOf(initial?.day?.toString() ?: "1") }

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
        title = { Text("新建生日") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("姓名") })
                OutlinedTextField(value = month, onValueChange = { month = it }, label = { Text("月（1-12）") })
                OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("日（1-31）") })
            }
        },
    )
}

@Composable
private fun ReminderEditorDialog(
    birthdayId: Long,
    onDismiss: () -> Unit,
    onSave: (BirthdayReminderEntity) -> Unit,
) {
    var daysBefore by remember { mutableStateOf("1") }
    var todo by remember { mutableStateOf("") }

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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = daysBefore,
                    onValueChange = { daysBefore = it },
                    label = { Text("提前几天（0 为生日当天早上）") },
                )
                OutlinedTextField(value = todo, onValueChange = { todo = it }, label = { Text("要做什么") })
            }
        },
    )
}
