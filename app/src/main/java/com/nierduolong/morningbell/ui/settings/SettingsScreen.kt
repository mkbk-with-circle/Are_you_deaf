@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.nierduolong.morningbell.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.core.CrashLogger
import com.nierduolong.morningbell.core.DailyLogStats
import com.nierduolong.morningbell.core.RetentionPolicy
import com.nierduolong.morningbell.core.StickyThemeRegistry
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.dailylog.DailyLogStorage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    repo: AppRepository,
    onOpenGoals: () -> Unit,
    onOpenBirthdays: () -> Unit,
    onOpenNearbyTransfer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stickyThemePackId by repo.stickyThemePackIdFlow.collectAsState()
    val nickname by repo.nicknameFlow.collectAsState()

    // 从系统设置返回或权限弹窗结束后重算「是否仍需要权限区块」
    var permissionStateEpoch by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val permissionAttentionNeeded =
        remember(permissionStateEpoch, context) {
            permissionAttentionNeededImpl(context)
        }
    val bumpPermissionState: () -> Unit = {
        permissionStateEpoch += 1
    }
    var crashStateEpoch by remember { mutableIntStateOf(0) }
    val crashReportCount =
        remember(crashStateEpoch, context) { CrashLogger.recentReports(context).size }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs =
            LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    permissionStateEpoch += 1
                }
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 「我的」是底部根 Tab，没有上一页可退，所以顶栏不放返回
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
        },
    ) { padding ->
        // 原来每个分区都是一张同款圆角卡片，六张摞在一起像模板；
        // 改成分区标签 + 扁平内容 + 发丝分隔线，靠留白分层
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item { SectionLabel(stringResource(R.string.settings_section_shortcuts)) }
            item { NavRow(stringResource(R.string.goals_title_short), onOpenGoals) }
            item { Hairline() }
            item { NavRow(stringResource(R.string.birthday_nav_short), onOpenBirthdays) }
            item { Hairline() }
            item { NavRow("附近快传", onOpenNearbyTransfer) }

            item { SectionLabel(stringResource(R.string.settings_section_profile)) }
            item {
                NicknameSection(
                    nickname = nickname,
                    onSave = { name -> scope.launch { repo.setNickname(name) } },
                )
            }

            item { SectionLabel(stringResource(R.string.dailylog_storage_title)) }
            item { DailyLogStorageSection(repo = repo) }

            // 只有真的崩过才出现，平时不占版面
            if (crashReportCount > 0) {
                item { SectionLabel(stringResource(R.string.settings_section_diagnostics)) }
                item {
                    DiagnosticsSection(
                        reportCount = crashReportCount,
                        onCleared = { crashStateEpoch += 1 },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.settings_section_theme)) }
            item {
                StickyThemePackSection(
                    selectedPackId = stickyThemePackId,
                    onSelectPack = { id ->
                        scope.launch { repo.setStickyThemePack(id) }
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.home_flow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            // 仅当精确闹钟 / 定位 中仍有未处理项时展示（避免常驻打扰）
            if (permissionAttentionNeeded) {
                item { SectionLabel(stringResource(R.string.settings_section_permissions)) }
                item { ExactAlarmSection() }
                item { LocationWeatherSection(onAttentionMaybeChanged = bumpPermissionState) }
            }
        }
    }
}

/** 分区标签：灰字小号 + 大段留白。彩色小标题一多，页面就像调色板 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
    )
}

@Composable
private fun Hairline() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 跳转行：整行可点，右侧留一个箭头表明「还有下一页」 */
@Composable
private fun NavRow(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 与下方提示卡一致：任一项仍「需要用户处理」则为 true */
private fun permissionAttentionNeededImpl(context: Context): Boolean {
    val locOk =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    val am = context.getSystemService(AlarmManager::class.java)
    val needsExactAlarm =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            am?.canScheduleExactAlarms() == false
    return needsExactAlarm || !locOk
}

/** 崩溃自查：没有服务端上报，只能把本机崩溃栈交到用户手上（分享出去给我看） */
@Composable
private fun DiagnosticsSection(
    reportCount: Int,
    onCleared: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.settings_crash_count_fmt, reportCount),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.settings_crash_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val latest = CrashLogger.recentReports(context).firstOrNull() ?: return@TextButton
                val text = runCatching { latest.readText() }.getOrNull() ?: return@TextButton
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, latest.name)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                runCatching { context.startActivity(Intent.createChooser(intent, null)) }
            }) {
                Text(stringResource(R.string.settings_crash_share))
            }
            TextButton(onClick = {
                CrashLogger.clear(context)
                onCleared()
            }) {
                Text(stringResource(R.string.settings_crash_clear))
            }
        }
    }
}

/** 日志占用与缓存清理：视频是本机最大的存储消耗项，用户需要看得见、清得掉 */
@Composable
private fun DailyLogStorageSection(repo: AppRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var usage by remember { mutableStateOf<DailyLogStorage.StorageBreakdown?>(null) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    val retentionDays by repo.retentionDaysFlow.collectAsState()

    LaunchedEffect(refreshEpoch) {
        usage = repo.dailyLogStorageBreakdown()
    }

    Column {
        // 占用大小是这一段的主角，用大字号；清缓存降为一条右对齐的文字操作
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.dailylog_storage_usage_fmt,
                    usage?.let { DailyLogStats.formatBytes(it.totalBytes) } ?: "…",
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                scope.launch {
                    val result = repo.clearDailyLogSafeCache()
                    refreshEpoch += 1
                    Toast.makeText(
                        context,
                        if (result.freedBytes > 0) {
                            "已安全释放 ${DailyLogStats.formatBytes(result.freedBytes)}"
                        } else {
                            "没有可安全清理的文件"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }) {
                Text("安全清理")
            }
        }

        usage?.let { value ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StorageUsageRow("本机原始视频", value.originalBytes)
                StorageUsageRow("每日合成", value.compilationBytes)
                StorageUsageRow("可重建缩略图", value.thumbnailBytes)
                StorageUsageRow("临时与其他文件", value.temporaryBytes + value.otherBytes)
            }
            Text(
                "可用空间 ${DailyLogStats.formatBytes(value.usableBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (value.usableBytes <= DailyLogStorage.MIN_FREE_BYTES) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.padding(bottom = 10.dp),
            )
            if (value.usableBytes <= DailyLogStorage.MIN_FREE_BYTES) {
                Text(
                    "空间不足，拍摄已暂停。请先清理缓存，或确认已有合成后清理往期原片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        Hairline()

        Text(
            stringResource(R.string.dailylog_retention_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            stringResource(R.string.dailylog_retention_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RetentionPolicy.OPTION_DAYS.forEach { days ->
                FilterChip(
                    selected = retentionDays == days,
                    onClick = {
                        scope.launch {
                            val slimmed = repo.setRetentionDays(days)
                            refreshEpoch += 1
                            if (days > 0) {
                                Toast.makeText(
                                    context,
                                    if (slimmed > 0) {
                                        context.getString(R.string.dailylog_retention_applied_fmt, slimmed)
                                    } else {
                                        context.getString(R.string.dailylog_retention_nothing)
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    label = {
                        Text(
                            if (days == 0) {
                                stringResource(R.string.dailylog_retention_forever)
                            } else {
                                stringResource(R.string.dailylog_retention_days_fmt, days)
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageUsageRow(
    label: String,
    bytes: Long,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(DailyLogStats.formatBytes(bytes), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NicknameSection(
    nickname: String,
    onSave: (String) -> Unit,
) {
    var text by remember(nickname) { mutableStateOf(nickname) }
    // 分区标签已经写了「昵称」，输入框自带 label，再加一行卡片标题就是三重重复
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.settings_nickname_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TextButton(
            onClick = { onSave(text) },
            enabled = text.isNotBlank() && text != nickname,
        ) {
            Text(stringResource(R.string.settings_nickname_save))
        }
    }
}

@Composable
private fun StickyThemePackSection(
    selectedPackId: String,
    onSelectPack: (String) -> Unit,
) {
    val selectedPack = remember(selectedPackId) { StickyThemeRegistry.packOrDefault(selectedPackId) }
    // 原来是薄荷底的容器卡；同屏还有另外两块彩色卡，三种糖果色一起上太吵
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.sticky_theme_card_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StickyThemeRegistry.allPacks().forEach { pack ->
                FilterChip(
                    selected = pack.id == selectedPackId,
                    onClick = { onSelectPack(pack.id) },
                    label = { Text(pack.cardTheme) },
                )
            }
        }
        Text(
            stringResource(R.string.sticky_theme_pack_current_tagline, selectedPack.tagline),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationWeatherSection(
    onAttentionMaybeChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { ok ->
                granted = ok
                onAttentionMaybeChanged()
            },
        )
    if (granted) return
    PermissionRow(
        hint = stringResource(R.string.weather_permission_hint),
        action = stringResource(R.string.weather_permission_grant),
        onAction = { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
    )
}

@Composable
private fun ExactAlarmSection() {
    val context = LocalContext.current
    val am = context.getSystemService(AlarmManager::class.java)
    val needsExact =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am?.canScheduleExactAlarms() == false
    if (!needsExact) return
    PermissionRow(
        hint = stringResource(R.string.home_exact_alarm_hint),
        action = stringResource(R.string.home_exact_alarm_action),
        onAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        },
    )
}

/**
 * 待处理的权限：说明在左、动作在右，一行一件事。
 * 不再用彩色容器卡——这两块本来一个是杏色一个是桃色，摞在一起像警告牌。
 */
@Composable
private fun PermissionRow(
    hint: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}
