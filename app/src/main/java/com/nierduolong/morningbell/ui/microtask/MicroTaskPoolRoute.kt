package com.nierduolong.morningbell.ui.microtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.MicroTaskCustomEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicroTaskPoolRoute(
    repo: AppRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val custom by repo.microTaskCustomFlow.collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.micro_task_pool_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.micro_task_pool_back)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.micro_task_pool_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(custom, key = { it.id }) { row ->
                CustomMicroTaskRow(
                    row = row,
                    onDelete = { scope.launch { repo.deleteCustomMicroTask(row.id) } },
                )
            }
        }
    }

    if (showAdd) {
        AddMicroTaskDialog(
            onDismiss = { showAdd = false },
            onSave = { line ->
                scope.launch {
                    repo.addCustomMicroTask(line)
                    showAdd = false
                }
            },
        )
    }
}

@Composable
private fun CustomMicroTaskRow(
    row: MicroTaskCustomEntity,
    onDelete: () -> Unit,
) {
    var confirm by remember(row.id) { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.micro_task_delete_title)) },
            text = { Text(stringResource(R.string.micro_task_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.micro_task_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.micro_task_delete_cancel))
                }
            },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(row.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirm = true }) {
                Text(stringResource(R.string.micro_task_delete))
            }
        }
    }
}

@Composable
private fun AddMicroTaskDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.micro_task_add_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.micro_task_add_hint)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onSave(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.micro_task_add_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.micro_task_add_cancel))
            }
        },
    )
}
