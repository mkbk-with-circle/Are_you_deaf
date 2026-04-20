package com.nierduolong.morningbell

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nierduolong.morningbell.ui.birthday.BirthdayRoute
import com.nierduolong.morningbell.ui.dismiss.DismissFlowRoute
import com.nierduolong.morningbell.ui.goals.GoalsRoute
import com.nierduolong.morningbell.ui.home.HomeRoute
import com.nierduolong.morningbell.ui.microtask.MicroTaskPoolRoute
import com.nierduolong.morningbell.ui.mood.MoodRoute
import com.nierduolong.morningbell.ui.settings.SettingsRoute
import com.nierduolong.morningbell.ui.theme.MorningBellTheme
import com.nierduolong.morningbell.unlock.WakeTrackStarter

class MainActivity : ComponentActivity() {
    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as MorningBellApp
        setContent {
            MorningBellTheme {
                val nav = rememberNavController()
                val activity = LocalContext.current as MainActivity

                LaunchedEffect(activity.intent?.extras?.getLong(EXTRA_OPEN_FLOW)) {
                    val id = activity.intent.getLongExtra(EXTRA_OPEN_FLOW, -1L)
                    if (id >= 0L) {
                        nav.navigate("dismiss_flow/$id") {
                            launchSingleTop = true
                        }
                    }
                }

                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeRoute(
                            repo = app.repository,
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenMood = { nav.navigate("mood") },
                        )
                    }
                    composable("settings") {
                        SettingsRoute(
                            repo = app.repository,
                            onBack = { nav.popBackStack() },
                            onOpenGoals = { nav.navigate("goals") },
                            onOpenMicroTaskPool = { nav.navigate("micro_tasks") },
                            onOpenBirthdays = { nav.navigate("birthdays") },
                        )
                    }
                    composable("mood") {
                        MoodRoute(
                            repo = app.repository,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("micro_tasks") {
                        MicroTaskPoolRoute(
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

    override fun onStart() {
        super.onStart()
        // 用户可见时拉起前台服务，满足 Android 12+ 从「前台」启动 FGS 的限制
        WakeTrackStarter.ensureRunning(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_OPEN_FLOW = "extra_open_dismiss_flow"

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
