@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nierduolong.morningbell.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.core.AlarmTimeCalculator
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.db.ChainAlarmGroupEntity
import com.nierduolong.morningbell.util.AudioPickerUtils
import kotlinx.coroutines.launch

private val weekdayLa = listOf("日", "一", "二", "三", "四", "五", "六")

private data class StepRowDraft(
    val hourText: String,
    val minuteText: String,
    val silent: Boolean,
    val vibrate: Boolean,
)

@Composable
fun ChainAlarmEditorDialog(
    repo: AppRepository,
    editingGroupId: Long?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultRing = stringResource(R.string.ringtone_default)

    var note by remember(editingGroupId) { mutableStateOf("") }
    var daysSet by remember(editingGroupId) {
        mutableStateOf(AlarmTimeCalculator.parseRepeatDays("0,1,2,3,4,5,6"))
    }
    var soundUri by remember(editingGroupId) { mutableStateOf<String?>(null) }
    val steps = remember(editingGroupId) {
        mutableStateListOf(
            StepRowDraft("7", "0", silent = false, vibrate = true),
            StepRowDraft("7", "15", silent = false, vibrate = true),
        )
    }

    LaunchedEffect(editingGroupId) {
        if (editingGroupId != null) {
            val g = repo.getChainGroup(editingGroupId) ?: return@LaunchedEffect
            val st = repo.getChainSteps(editingGroupId)
            note = g.note
            daysSet = AlarmTimeCalculator.parseRepeatDays(g.repeatDays)
            soundUri = g.soundUri
            steps.clear()
            st.forEach { s ->
                steps.add(
                    StepRowDraft(
                        s.hour.toString(),
                        s.minute.toString(),
                        s.silent,
                        s.vibrate,
                    ),
                )
            }
        }
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
    val audioMime =
        remember {
            arrayOf(
                "audio/mpeg",
                "audio/mp3",
                "audio/mp4",
                "audio/x-wav",
                "audio/*",
            )
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (editingGroupId == null) {
                    stringResource(R.string.chain_alarm_new_title)
                } else {
                    stringResource(R.string.chain_alarm_edit_title)
                },
            )
        },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("重复（周日→周六）", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    weekdayLa.forEachIndexed { index, label ->
                        val on = daysSet.contains(index)
                        FilterChip(
                            selected = on,
                            onClick = {
                                val next = daysSet.toMutableSet()
                                if (on) next.remove(index) else next.add(index)
                                daysSet = next
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    AudioPickerUtils.ringtoneSummary(context, soundUri, defaultRing),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickAudio.launch(audioMime) }) {
                        Text(stringResource(R.string.chain_alarm_pick_ring))
                    }
                    TextButton(
                        onClick = { soundUri = null },
                        enabled = soundUri != null,
                    ) {
                        Text(stringResource(R.string.ringtone_clear))
                    }
                }
                steps.forEachIndexed { idx, s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "第 ${idx + 1} 响",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(0.22f),
                        )
                        OutlinedTextField(
                            value = s.hourText,
                            onValueChange = { raw ->
                                steps[idx] =
                                    s.copy(
                                        hourText = raw.filter { it.isDigit() }.take(2),
                                    )
                            },
                            label = { Text("时") },
                            modifier = Modifier.weight(0.28f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = s.minuteText,
                            onValueChange = { raw ->
                                steps[idx] =
                                    s.copy(
                                        minuteText = raw.filter { it.isDigit() }.take(2),
                                    )
                            },
                            label = { Text("分") },
                            modifier = Modifier.weight(0.28f),
                            singleLine = true,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("无声", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = s.silent,
                            onCheckedChange = { steps[idx] = s.copy(silent = it) },
                        )
                        Text("震动", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = s.vibrate,
                            onCheckedChange = { steps[idx] = s.copy(vibrate = it) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                TextButton(
                    onClick = {
                        steps.add(StepRowDraft("8", "0", false, true))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.chain_alarm_add_step))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val defs = mutableListOf<AppRepository.ChainStepDef>()
                    for (s in steps) {
                        val h = s.hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                        val m = s.minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                        defs.add(
                            AppRepository.ChainStepDef(
                                hour = h,
                                minute = m,
                                silent = s.silent,
                                vibrate = s.vibrate,
                            ),
                        )
                    }
                    if (defs.size < 2) return@TextButton
                    scope.launch {
                        repo.upsertChainAlarm(
                            ChainAlarmGroupEntity(
                                id = editingGroupId ?: 0L,
                                enabled = true,
                                repeatDays =
                                    if (daysSet.isEmpty()) {
                                        "0,1,2,3,4,5,6"
                                    } else {
                                        AlarmTimeCalculator.formatRepeatDays(daysSet)
                                    },
                                note = note.trim(),
                                soundUri = soundUri,
                            ),
                            defs,
                        )
                        onSaved()
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
