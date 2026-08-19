package com.nierduolong.morningbell.ui.dailylog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.dailylog.CompilePreflightReport

@Composable
internal fun CompilePreflightDialog(
    checking: Boolean,
    report: CompilePreflightReport?,
    onRetry: () -> Unit,
    onCompile: (skipUnavailable: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text(if (checking) "正在检查素材" else "合成前检查") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (checking) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("仅检查文件是否在线，不下载原片。")
                    }
                } else if (report != null) {
                    Text(
                        if (report.allAvailable) {
                            "${report.totalClips} 条素材都可以读取，将按拍摄时间顺序合成。"
                        } else {
                            "可读取 ${report.availableClips}/${report.totalClips} 条；${report.unavailableClips} 条所在设备当前离线。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    report.members.forEach { member ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(member.nickname, style = MaterialTheme.typography.bodySmall)
                            Text(
                                when {
                                    member.totalClips == 0 -> "今天未记录"
                                    member.unavailableClips == 0 -> "${member.totalClips} 条可用"
                                    else -> "${member.availableClips}/${member.totalClips} 条可用"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (member.unavailableClips == 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                            )
                        }
                    }
                    if (!report.allAvailable) {
                        Text(
                            "跳过后只影响本次成片，原记录不会删除；成员重新上线后仍可重新生成完整版。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text("成员已上线，重新检查")
                        }
                    }
                }
            }
        },
        confirmButton = {
            val current = report
            if (!checking && current?.canCompile == true) {
                Button(onClick = { onCompile(!current.allAvailable) }) {
                    Text(if (current.allAvailable) "开始合成" else "跳过 ${current.unavailableClips} 条并合成")
                }
            }
        },
        dismissButton = {
            if (!checking) {
                TextButton(onClick = onDismiss, modifier = Modifier.padding(end = 4.dp)) {
                    Text(if (report?.allAvailable == false) "等待成员上线" else "取消")
                }
            }
        },
    )
}
