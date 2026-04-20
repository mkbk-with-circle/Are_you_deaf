package com.nierduolong.morningbell.alarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nierduolong.morningbell.MainActivity
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.R
import com.nierduolong.morningbell.unlock.WakeTrackStarter
import com.nierduolong.morningbell.ui.theme.MorningBellTheme
import kotlinx.coroutines.launch

/** 锁屏上展示；连锁闹钟可「完成」以截断当日后续响铃 */
class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WakeTrackStarter.ensureRunning(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val isChainStep = intent.getBooleanExtra(EXTRA_IS_CHAIN_STEP, false)
        val app = application as MorningBellApp
        setContent {
            MorningBellTheme {
                val scope = rememberCoroutineScope()
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.alarm_ring_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    stopRinging()
                                    app.repository.scheduleSnoozeFiveMinutes(alarmId, isChainStep)
                                    finish()
                                }
                            },
                            modifier = Modifier.padding(8.dp),
                        ) {
                            Text(stringResource(R.string.alarm_ring_snooze))
                        }
                        if (isChainStep) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        stopRinging()
                                        app.repository.onChainStepDoneEarly(alarmId)
                                        finish()
                                    }
                                },
                                modifier = Modifier.padding(8.dp),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    ),
                            ) {
                                Text(stringResource(R.string.alarm_ring_chain_done))
                            }
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    stopRinging()
                                    MainActivity.openDismissFlow(this@AlarmRingActivity, alarmId)
                                    finish()
                                }
                            },
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            modifier = Modifier.padding(8.dp),
                        ) {
                            Text(stringResource(R.string.alarm_ring_dismiss_flow))
                        }
                    }
                }
            }
        }
    }

    private fun stopRinging() {
        stopService(Intent(this, AlarmRingService::class.java))
    }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id_ring"
        const val EXTRA_IS_CHAIN_STEP = "extra_is_chain_step"

        fun start(
            context: Context,
            alarmId: Long,
            isChainStep: Boolean,
        ) {
            val i =
                Intent(context, AlarmRingActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_ALARM_ID, alarmId)
                    putExtra(EXTRA_IS_CHAIN_STEP, isChainStep)
                }
            context.startActivity(i)
        }
    }
}
