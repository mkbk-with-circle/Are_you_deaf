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
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.core.StickyThemeRegistry
import com.nierduolong.morningbell.data.AppRepository
import com.nierduolong.morningbell.data.WakeSettings
import com.nierduolong.morningbell.data.db.WakeDayEntity
import com.nierduolong.morningbell.ui.common.RowFields
import com.nierduolong.morningbell.ui.home.MonthlyWakeTrendCard
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    repo: AppRepository,
    onBack: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenBirthdays: () -> Unit,
    onOpenVideoDiary: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val wakes by repo.wakeFlow.collectAsState(initial = emptyList())
    val thresholdMinutes by repo.wakeThresholdMinuteOfDayFlow.collectAsState()
    val stickyThemePackId by repo.stickyThemePackIdFlow.collectAsState()

    val todayEpoch = LocalDate.now().toEpochDay()
    val todayWake = wakes.find { it.dayEpoch == todayEpoch }
    var showWakeThresholdEditor by remember { mutableStateOf(false) }
    // 从系统设置返回或权限弹窗结束后重算「是否仍需要权限区块」
    var permissionStateEpoch by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val permissionAttentionNeeded =
        remember(permissionStateEpoch, context) {
            permissionAttentionNeededImpl(context)
        }
    val bumpPermissionState: () -> Unit = {
        permissionStateEpoch += 1
    }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.settings_back))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
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
            item {
                Text(
                    stringResource(R.string.settings_section_shortcuts),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = onOpenGoals) {
                                Text(stringResource(R.string.goals_title_short))
                            }
                            TextButton(onClick = onOpenBirthdays) {
                                Text(stringResource(R.string.birthday_nav_short))
                            }
                            TextButton(onClick = onOpenVideoDiary) {
                                Text(stringResource(R.string.video_diary_nav_short))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_section_theme),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                StickyThemePackCard(
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
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_section_wake),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                TodayWakeCard(
                    todayWake = todayWake,
                    thresholdMinutes = thresholdMinutes,
                    onEditThreshold = { showWakeThresholdEditor = true },
                )
            }
            item {
                WakeHistoryCard(wakes = wakes)
            }
            item {
                MonthlyWakeTrendCard(wakes = wakes)
            }
            // 仅当精确闹钟 / 定位 / 电池优化 中仍有未处理项时展示（避免常驻打扰）
            if (permissionAttentionNeeded) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.settings_section_permissions),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        PermissionHintCard()
                        LocationWeatherPermissionCard(onAttentionMaybeChanged = bumpPermissionState)
                        BatteryOptimizationHintCard(onAttentionMaybeChanged = bumpPermissionState)
                    }
                }
            }
        }
    }

    if (showWakeThresholdEditor) {
        WakeThresholdEditorDialog(
            initialMinuteOfDay = thresholdMinutes,
            onDismiss = { showWakeThresholdEditor = false },
            onSave = { minuteOfDay ->
                scope.launch {
                    repo.setWakeThresholdMinuteOfDay(minuteOfDay)
                    showWakeThresholdEditor = false
                }
            },
        )
    }
}

/** 与下方三张提示卡一致：任一项仍「需要用户处理」则为 true */
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
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val batteryOk =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    return needsExactAlarm || !locOk || !batteryOk
}

private fun formatWakeMillis(millis: Long): String {
    val t =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
    return DateTimeFormatter.ofPattern("HH:mm").format(t)
}

@Composable
private fun TodayWakeCard(
    todayWake: WakeDayEntity?,
    thresholdMinutes: Int,
    onEditThreshold: () -> Unit,
) {
    val recorded = todayWake != null
    val thresholdLabel = WakeSettings.formatMinuteOfDay(thresholdMinutes)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            if (recorded) {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            } else {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                )
            },
        elevation = CardDefaults.cardElevation(defaultElevation = if (recorded) 3.dp else 0.dp),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.home_today_wake_title),
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (recorded) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            if (todayWake != null) {
                Text(
                    formatWakeMillis(todayWake.firstUnlockMillis),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.home_today_wake_sub, thresholdLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
                )
            } else {
                Text(
                    stringResource(R.string.home_today_wake_empty, thresholdLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.home_today_wake_fg_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            TextButton(onClick = onEditThreshold) {
                Text(stringResource(R.string.home_wake_threshold_btn))
            }
        }
    }
}

@Composable
private fun StickyThemePackCard(
    selectedPackId: String,
    onSelectPack: (String) -> Unit,
) {
    val selectedPack = remember(selectedPackId) { StickyThemeRegistry.packOrDefault(selectedPackId) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.sticky_theme_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.sticky_theme_card_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
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
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun WakeThresholdEditorDialog(
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val key = initialMinuteOfDay
    var hourText by remember(key) { mutableStateOf((initialMinuteOfDay / 60).toString()) }
    var minuteText by remember(key) { mutableStateOf((initialMinuteOfDay % 60).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val h = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val mi = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    onSave((h * 60 + mi).coerceIn(0, 23 * 60 + 59))
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(stringResource(R.string.wake_edit_threshold_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.wake_edit_threshold_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowFields(
                    hourText = hourText,
                    minuteText = minuteText,
                    onHourTextChange = { hourText = it },
                    onMinuteTextChange = { minuteText = it },
                )
            }
        },
    )
}

@Composable
private fun WakeHistoryCard(wakes: List<WakeDayEntity>) {
    val sorted = wakes.sortedByDescending { it.dayEpoch }.take(7)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.home_wake_history_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (sorted.isEmpty()) {
                Text(
                    stringResource(R.string.home_wake_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sorted.forEach { row ->
                    val dateStr =
                        DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA).format(
                            LocalDate.ofEpochDay(row.dayEpoch),
                        )
                    Text(
                        "$dateStr · ${formatWakeMillis(row.firstUnlockMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationWeatherPermissionCard(
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                stringResource(R.string.weather_permission_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                },
            ) {
                Text(stringResource(R.string.weather_permission_grant))
            }
        }
    }
}

@Composable
private fun BatteryOptimizationHintCard(
    onAttentionMaybeChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var checkSeq by remember { mutableStateOf(0) }
    val ignored =
        remember(checkSeq) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    if (ignored) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.wake_battery_opt_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                },
                            )
                        } catch (_: Exception) {
                            android.widget.Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.wake_battery_opt_open_failed),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                        }
                        onAttentionMaybeChanged()
                    },
                ) {
                    Text(stringResource(R.string.wake_battery_opt_action))
                }
                TextButton(
                    onClick = {
                        checkSeq++
                        onAttentionMaybeChanged()
                    },
                ) {
                    Text(stringResource(R.string.wake_battery_opt_refresh))
                }
            }
        }
    }
}

@Composable
private fun PermissionHintCard() {
    val context = LocalContext.current
    val am = context.getSystemService(AlarmManager::class.java)
    val needsExact =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am?.canScheduleExactAlarms() == false
    if (!needsExact) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                stringResource(R.string.home_exact_alarm_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                },
            ) {
                Text(stringResource(R.string.home_exact_alarm_action))
            }
        }
    }
}
