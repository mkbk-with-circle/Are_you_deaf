package com.nierduolong.morningbell

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nierduolong.morningbell.ui.birthday.BirthdayRoute
import com.nierduolong.morningbell.ui.dailylog.CaptureRoute
import com.nierduolong.morningbell.ui.dailylog.DailyLogRoute
import com.nierduolong.morningbell.ui.dailylog.NearbyLogRoute
import com.nierduolong.morningbell.ui.dailylog.DayDetailRoute
import com.nierduolong.morningbell.ui.dailylog.OnboardingRoute
import com.nierduolong.morningbell.ui.dailylog.VideoPlayerRoute
import com.nierduolong.morningbell.ui.dismiss.DismissFlowRoute
import com.nierduolong.morningbell.ui.goals.GoalsRoute
import com.nierduolong.morningbell.ui.home.HomeRoute
import com.nierduolong.morningbell.ui.mood.MoodRoute
import com.nierduolong.morningbell.ui.settings.SettingsRoute
import com.nierduolong.morningbell.ui.theme.MorningBellTheme
import com.nierduolong.morningbell.ui.transfer.NearbyTransferRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.nierduolong.morningbell.dailylog.lan.NearbyAutoConnector
import com.nierduolong.morningbell.dailylog.lan.NearbyInvite
import com.nierduolong.morningbell.dailylog.lan.NearbyPendingInvite
import com.nierduolong.morningbell.transfer.TransferSelectionStore

/**
 * 底部三 Tab：日志（默认，Setlog 主功能）/ 闹钟（含连锁闹钟）/ 我的（设置）
 *
 * 选中态用「实心图标」表示，未选中用描边图标，而不是 Material 默认的药丸指示器：
 * 药丸是 Material 样例的标志性长相，实心/描边切换才是照片类 App 的做法。
 */
private data class BottomTab(
    val route: String,
    val labelResId: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val bottomTabs =
    listOf(
        BottomTab("dailylog", R.string.nav_tab_dailylog, Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
        BottomTab("home", R.string.nav_tab_alarms, Icons.Outlined.Alarm, Icons.Filled.Alarm),
        BottomTab("settings", R.string.nav_tab_me, Icons.Outlined.Person, Icons.Filled.Person),
    )

class MainActivity : ComponentActivity() {
    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val nearbyPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                startNearbyAutoConnect()
            } else {
                NearbyAutoConnector.markFailed("缺少附近设备权限，无法自动发现房主热点")
            }
        }
    private var nearbyPermissionRequested = false
    private var autoConnectJob: Job? = null

    /**
     * 待处理的跳转意图。必须是 Compose 可观察的状态：activity.intent 换了对象不会触发重组，
     * 于是「app 还活着时点通知」这条路径原本会静默失效。
     */
    private val routingIntent = mutableStateOf<Intent?>(null)
    private val processedRoutingIntent = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MorningBellApp
        routingIntent.value = intent
        // 首启由引导页统一要权限，这里只兜住「装完就用了很久才更新到带通知功能的版本」的情况
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && app.repository.hasOnboardedFlow.value) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MorningBellTheme {
                val nav = rememberNavController()
                val hasOnboarded by app.repository.hasOnboardedFlow.collectAsState()
                val currentLogId by app.repository.currentLogIdFlow.collectAsState()
                val pending by routingIntent

                // 引导没走完时先攒着，等 NavHost 真的挂上去再跳
                LaunchedEffect(pending, hasOnboarded) {
                    val current = pending?.takeIf { hasOnboarded } ?: return@LaunchedEffect
                    processedRoutingIntent.value = true
                    when {
                        current.action == Intent.ACTION_SEND || current.action == Intent.ACTION_SEND_MULTIPLE -> {
                            if (TransferSelectionStore.acceptShareIntent(this@MainActivity, current)) {
                                nav.navigate("nearby_transfer") { launchSingleTop = true }
                            }
                        }

                        current.getBooleanExtra(EXTRA_OPEN_TRANSFER, false) -> {
                            nav.navigate("nearby_transfer") { launchSingleTop = true }
                        }

                        NearbyInvite.parse(current.dataString) != null -> {
                            val invite = requireNotNull(NearbyInvite.parse(current.dataString))
                            NearbyPendingInvite.save(this@MainActivity, invite)
                            nav.navigate("nearby_log") { launchSingleTop = true }
                        }

                        current.hasExtra(EXTRA_OPEN_FLOW) -> {
                            val id = current.getLongExtra(EXTRA_OPEN_FLOW, -1L)
                            nav.navigate("dismiss_flow/$id") { launchSingleTop = true }
                        }

                        current.getBooleanExtra(EXTRA_OPEN_CAPTURE, false) -> {
                            nav.navigate("capture") { launchSingleTop = true }
                        }
                    }
                    current.removeExtra(EXTRA_OPEN_FLOW)
                    current.removeExtra(EXTRA_OPEN_CAPTURE)
                    current.removeExtra(EXTRA_OPEN_TRANSFER)
                    routingIntent.value = null
                }

                // 扫码后去系统 Wi-Fi 面板时进程可能被回收；恢复后继续打开加入页，不让邀请码丢在后台。
                LaunchedEffect(hasOnboarded) {
                    if (!hasOnboarded) return@LaunchedEffect
                    if (processedRoutingIntent.value) return@LaunchedEffect
                    val current = routingIntent.value
                    val hasExplicitRoute =
                        current?.hasExtra(EXTRA_OPEN_FLOW) == true ||
                            current?.getBooleanExtra(EXTRA_OPEN_CAPTURE, false) == true ||
                            current?.getBooleanExtra(EXTRA_OPEN_TRANSFER, false) == true ||
                            current?.action == Intent.ACTION_SEND ||
                            current?.action == Intent.ACTION_SEND_MULTIPLE ||
                            NearbyInvite.parse(current?.dataString) != null
                    if (!hasExplicitRoute && NearbyPendingInvite.peek(this@MainActivity) != null) {
                        nav.navigate("nearby_log") { launchSingleTop = true }
                    }
                }

                if (!hasOnboarded) {
                    OnboardingRoute(
                        repo = app.repository,
                        onDone = {},
                    )
                } else {
                    val backStackEntry by nav.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route
                    val showBottomBar = bottomTabs.any { it.route == currentRoute }

                    Scaffold(
                        // 这一层只负责给底部导航让位：拍摄页与播放页要真正全屏，
                        // 系统栏内边距交给各页面自己处理，避免相机预览被切出白边
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            if (showBottomBar) {
                                Column {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    NavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                    ) {
                                        bottomTabs.forEach { tab ->
                                            val selected =
                                                backStackEntry?.destination?.hierarchy?.any {
                                                    it.route == tab.route
                                                } == true
                                            NavigationBarItem(
                                                selected = selected,
                                                onClick = {
                                                    nav.navigate(tab.route) {
                                                        popUpTo(nav.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                },
                                                icon = {
                                                    Icon(
                                                        if (selected) tab.selectedIcon else tab.icon,
                                                        contentDescription = null,
                                                    )
                                                },
                                                label = { Text(stringResource(tab.labelResId), maxLines = 1) },
                                                colors =
                                                    NavigationBarItemDefaults.colors(
                                                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        indicatorColor = Color.Transparent,
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    ) { padding ->
                        NavHost(
                            navController = nav,
                            startDestination = "dailylog",
                            modifier = Modifier.padding(padding),
                        ) {
                            composable("dailylog") {
                                DailyLogRoute(
                                    repo = app.repository,
                                    onOpenCapture = { nav.navigate("capture") },
                                    onOpenDay = { day -> nav.navigate("day/$day") },
                                    onOpenPlayer = { nav.navigate("player") },
                                    onOpenNearby = { nav.navigate("nearby_log") },
                                )
                            }
                            composable("nearby_log") {
                                NearbyLogRoute(
                                    repo = app.repository,
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable("capture") {
                                CaptureRoute(
                                    repo = app.repository,
                                    logId = currentLogId ?: -1L,
                                    onDone = { nav.popBackStack() },
                                )
                            }
                            composable("player") {
                                VideoPlayerRoute(onBack = { nav.popBackStack() })
                            }
                            composable(
                                route = "day/{dayEpoch}",
                                arguments = listOf(navArgument("dayEpoch") { type = NavType.LongType }),
                            ) { entry ->
                                DayDetailRoute(
                                    repo = app.repository,
                                    dayEpoch = entry.arguments?.getLong("dayEpoch") ?: 0L,
                                    onBack = { nav.popBackStack() },
                                    onOpenPlayer = { nav.navigate("player") },
                                )
                            }
                            composable("home") {
                                HomeRoute(
                                    repo = app.repository,
                                    onOpenMood = { nav.navigate("mood") },
                                )
                            }
                            composable("settings") {
                                SettingsRoute(
                                    repo = app.repository,
                                    onOpenGoals = { nav.navigate("goals") },
                                    onOpenBirthdays = { nav.navigate("birthdays") },
                                    onOpenNearbyTransfer = { nav.navigate("nearby_transfer") },
                                )
                            }
                            composable("nearby_transfer") {
                                NearbyTransferRoute(onBack = { nav.popBackStack() })
                            }
                            composable("mood") {
                                MoodRoute(
                                    repo = app.repository,
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable("goals") {
                                GoalsRoute(
                                    repo = app.repository,
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable("birthdays") {
                                BirthdayRoute(
                                    repo = app.repository,
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable(
                                route = "dismiss_flow/{alarmId}",
                                arguments =
                                    listOf(
                                        navArgument("alarmId") { type = NavType.LongType },
                                    ),
                            ) { entry ->
                                val alarmId = entry.arguments?.getLong("alarmId") ?: -1L
                                DismissFlowRoute(
                                    repo = app.repository,
                                    alarmId = alarmId,
                                    onDone = {
                                        nav.popBackStack()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as MorningBellApp
        lifecycleScope.launch(Dispatchers.IO) {
            app.repository.rescheduleAllBirthdayReminders()
        }
        startNearbyAutoConnect()
    }

    private fun startNearbyAutoConnect() {
        autoConnectJob?.cancel()
        autoConnectJob =
            lifecycleScope.launch {
                val app = application as MorningBellApp
                if (!app.repository.hasOnboardedFlow.value) return@launch
                val permission =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    } else {
                        Manifest.permission.ACCESS_FINE_LOCATION
                    }
                if (ContextCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                    if (!nearbyPermissionRequested) {
                        nearbyPermissionRequested = true
                        nearbyPermission.launch(arrayOf(permission))
                    }
                    return@launch
                }
                NearbyAutoConnector.supervise(this@MainActivity, app.repository)
            }
    }

    override fun onStop() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processedRoutingIntent.value = false
        routingIntent.value = intent
    }

    companion object {
        const val EXTRA_OPEN_FLOW = "extra_open_dismiss_flow"
        const val EXTRA_OPEN_CAPTURE = "extra_open_capture"
        const val EXTRA_OPEN_TRANSFER = "extra_open_nearby_transfer"

        fun openNearbyTransferIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TRANSFER, true)
            }

        fun openDismissFlow(
            context: Context,
            alarmId: Long,
        ) {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_OPEN_FLOW, alarmId)
                },
            )
        }
    }
}
