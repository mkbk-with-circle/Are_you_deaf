package com.nierduolong.morningbell.ui.videodiary

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.VideoDiaryEntryEntity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDiaryRoute(
    repo: AppRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val entries by repo.videoDiaryEntryFlow.collectAsState(initial = emptyList())
    var targetDay by remember { mutableStateOf(LocalDate.now()) }
    val targetEpoch = targetDay.toEpochDay()
    var pendingDelete by remember { mutableStateOf<VideoDiaryEntryEntity?>(null) }

    val pickVideo =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        repo.importVideoDiary(uri, targetEpoch)
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.video_diary_import_fail) + (e.message ?: ""),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    val videoMimes = remember { arrayOf("video/*", "application/mp4") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_diary_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.video_diary_back))
                    }
                },
            )
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
                    stringResource(R.string.video_diary_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    stringResource(R.string.video_diary_root_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    repo.getVideoDiaryRootAbsolutePath(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                Text(
                    stringResource(R.string.video_diary_target_day),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { targetDay = targetDay.minusDays(1) },
                    ) {
                        Text(stringResource(R.string.video_diary_prev_day))
                    }
                    Text(
                        formatDayLabel(targetDay),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(
                        onClick = { targetDay = targetDay.plusDays(1) },
                    ) {
                        Text(stringResource(R.string.video_diary_next_day))
                    }
                }
                TextButton(
                    onClick = { targetDay = LocalDate.now() },
                ) {
                    Text(stringResource(R.string.video_diary_today))
                }
                FilledTonalButton(
                    onClick = { pickVideo.launch(videoMimes) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.video_diary_add))
                }
            }
            item {
                Text(
                    stringResource(R.string.video_diary_list_header),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            val forDay = entries.filter { it.dayEpoch == targetEpoch }
            if (forDay.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.video_diary_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                items(forDay, key = { it.id }) { e ->
                    VideoEntryRow(
                        entry = e,
                        onDelete = { pendingDelete = e },
                    )
                }
            }
        }
    }

    val del = pendingDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.video_diary_delete)) },
            text = { Text(stringResource(R.string.video_diary_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repo.deleteVideoDiaryEntry(del.id)
                            pendingDelete = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.video_diary_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.alarm_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun VideoEntryRow(
    entry: VideoDiaryEntryEntity,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    formatSize(entry.sizeBytes) + " · " + formatAdded(entry.addedAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.video_diary_delete))
            }
        }
    }
}

private val dayLabelFormatter =
    DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 (EEEE)", Locale.CHINA)

private fun formatDayLabel(d: LocalDate): String = dayLabelFormatter.format(d)

private fun formatAdded(addedAtMillis: Long): String {
    val t = Instant.ofEpochMilli(addedAtMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d 导入".format(t.hour, t.minute)
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
