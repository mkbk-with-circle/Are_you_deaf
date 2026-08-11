package com.nierduolong.morningbell.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.GoalEntity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsRoute(
    repo: AppRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val goals by repo.goalFlow.collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.goals_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.goals_back)) }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.goals_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            itemsIndexed(goals, key = { _, g -> g.id }) { index, g ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                GoalRow(
                    goal = g,
                    onToggleDone = { done ->
                        scope.launch { repo.setGoalCompleted(g.id, done) }
                    },
                    onDelete = {
                        scope.launch { repo.deleteGoal(g.id) }
                    },
                )
            }
        }
    }

    if (showAdd) {
        AddGoalDialog(
            onDismiss = { showAdd = false },
            onSave = { title, deadlineEpoch ->
                scope.launch {
                    repo.upsertGoal(
                        GoalEntity(
                            title = title.trim(),
                            deadlineEpochDay = deadlineEpoch,
                            completed = false,
                        ),
                    )
                    showAdd = false
                }
            },
        )
    }
}

@Composable
private fun GoalRow(
    goal: GoalEntity,
    onToggleDone: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(goal.id) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.goals_delete_title)) },
            text = { Text(stringResource(R.string.goals_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.goals_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.goals_delete_cancel))
                }
            },
        )
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                goal.title,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (goal.completed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                textDecoration = if (goal.completed) TextDecoration.LineThrough else null,
            )
            val deadlineStr =
                goal.deadlineEpochDay?.let { ed ->
                    val d = LocalDate.ofEpochDay(ed)
                    DateTimeFormatter.ofPattern("yyyy-MM-dd").format(d)
                } ?: stringResource(R.string.goals_no_deadline)
            Text(
                stringResource(R.string.goals_deadline_label, deadlineStr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(stringResource(R.string.goals_delete))
            }
        }
        Switch(checked = goal.completed, onCheckedChange = onToggleDone)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGoalDialog(
    onDismiss: () -> Unit,
    onSave: (String, Long?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var useDeadline by remember { mutableStateOf(false) }
    var pickedEpochDay by remember {
        mutableStateOf<Long?>(LocalDate.now().plusWeeks(1).toEpochDay())
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val pickerState =
        rememberDatePickerState(
            initialSelectedDateMillis =
                pickedEpochDay?.let { ed ->
                    LocalDate.ofEpochDay(ed)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()
                },
        )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            val day =
                                Instant.ofEpochMilli(ms)
                                    .atZone(zone)
                                    .toLocalDate()
                            pickedEpochDay = day.toEpochDay()
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.goals_date_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.goals_date_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goals_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.goals_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.goals_use_deadline))
                    Switch(
                        checked = useDeadline,
                        onCheckedChange = { useDeadline = it },
                    )
                }
                if (useDeadline) {
                    val label =
                        pickedEpochDay?.let { ed ->
                            DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                .format(LocalDate.ofEpochDay(ed))
                        } ?: "—"
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(stringResource(R.string.goals_pick_date, label))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(title, if (useDeadline) pickedEpochDay else null)
                    }
                },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.goals_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.goals_cancel))
            }
        },
    )
}
