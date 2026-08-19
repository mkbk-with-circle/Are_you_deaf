package com.nierduolong.morningbell.alarm

import android.app.KeyguardManager
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nierduolong.morningbell.ui.theme.MorningBellTheme
import kotlinx.coroutines.launch

/** 锁屏上展示；连锁闹钟可「完成」以截断当日后续响铃 */
class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // 尽量顶起锁屏并点亮，便于直接在本页关闹钟
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val isChainStep = intent.getBooleanExtra(EXTRA_IS_CHAIN_STEP, false)
        val isBirthdayReminder = intent.getBooleanExtra(EXTRA_IS_BIRTHDAY_REMINDER, false)
        val ringTitle = intent.getStringExtra(EXTRA_RING_TITLE)
        val ringSubtitle = intent.getStringExtra(EXTRA_RING_SUBTITLE)
        val eventEpochForAck = intent.getLongExtra(AlarmReceiver.EXTRA_BIRTHDAY_EVENT_EPOCH_DAY, Long.MIN_VALUE)
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
                                .padding(horizontal = 28.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val displayTitle =
                            if (!ringTitle.isNullOrBlank()) {
                                ringTitle
                            } else {
                                stringResource(R.string.alarm_ring_title)
                            }
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        if (!ringSubtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = ringSubtitle,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(36.dp))
                        // 主操作：关闹钟进入流；次要操作用描边按钮，不用糖果色实心
                        Button(
                            onClick = {
                                scope.launch {
                                    stopRinging()
                                    if (isBirthdayReminder && eventEpochForAck != Long.MIN_VALUE) {
                                        app.repository.ackBirthdayReminderForEventCycle(
                                            alarmId,
                                            eventEpochForAck,
                                        )
                                    }
                                    val flowAlarmId = if (isBirthdayReminder) -1L else alarmId
                                    MainActivity.openDismissFlow(this@AlarmRingActivity, flowAlarmId)
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.alarm_ring_dismiss_flow))
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    stopRinging()
                                    if (isBirthdayReminder) {
                                        app.repository.scheduleBirthdayReminderSnooze(alarmId)
                                    } else {
                                        app.repository.scheduleSnoozeFiveMinutes(alarmId, isChainStep)
                                    }
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.alarm_ring_snooze))
                        }
                        if (isBirthdayReminder && eventEpochForAck != Long.MIN_VALUE) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        stopRinging()
                                        app.repository.ackBirthdayReminderForEventCycle(
                                            alarmId,
                                            eventEpochForAck,
                                        )
                                        finish()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("本周期已处理")
                            }
                        }
                        if (isChainStep) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        stopRinging()
                                        app.repository.onChainStepDoneEarly(alarmId)
                                        finish()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.alarm_ring_chain_done))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        km.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissError() {}

                override fun onDismissSucceeded() {}

                override fun onDismissCancelled() {}
            },
        )
    }

    private fun stopRinging() {
        stopService(Intent(this, AlarmRingService::class.java))
    }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id_ring"
        const val EXTRA_IS_CHAIN_STEP = "extra_is_chain_step"
        const val EXTRA_IS_BIRTHDAY_REMINDER = "extra_is_birthday_reminder"
        const val EXTRA_RING_TITLE = "extra_ring_title"
        const val EXTRA_RING_SUBTITLE = "extra_ring_subtitle"

        fun start(
            context: Context,
            alarmId: Long,
            isChainStep: Boolean,
            isBirthdayReminder: Boolean = false,
            ringTitle: String? = null,
            ringSubtitle: String? = null,
            eventEpochDayForAck: Long? = null,
        ) {
            val i =
                Intent(context, AlarmRingActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                    putExtra(EXTRA_ALARM_ID, alarmId)
                    putExtra(EXTRA_IS_CHAIN_STEP, isChainStep)
                    putExtra(EXTRA_IS_BIRTHDAY_REMINDER, isBirthdayReminder)
                    if (ringTitle != null) putExtra(EXTRA_RING_TITLE, ringTitle)
                    if (ringSubtitle != null) putExtra(EXTRA_RING_SUBTITLE, ringSubtitle)
                    if (eventEpochDayForAck != null) {
                        putExtra(AlarmReceiver.EXTRA_BIRTHDAY_EVENT_EPOCH_DAY, eventEpochDayForAck)
                    }
                }
            context.startActivity(i)
        }
    }
}
