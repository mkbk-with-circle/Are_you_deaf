package com.nierduolong.morningbell.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nierduolong.morningbell.MorningBellApp
import com.nierduolong.morningbell.unlock.WakeTrackStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != ACTION_FIRE) return
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId < 0) return
        val isChainStep = intent.getBooleanExtra(EXTRA_IS_CHAIN_STEP, false)
        val snoozeOneShot = intent.getBooleanExtra(EXTRA_SNOOZE_ONE_SHOT, false)
        WakeTrackStarter.ensureRunning(context)
        val app = context.applicationContext as MorningBellApp

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isChainStep && app.repository.shouldSkipChainStepRing(alarmId)) {
                    app.repository.scheduleFollowingChainStep(alarmId)
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    AlarmRingService.start(context, alarmId, isChainStep)
                    AlarmRingActivity.start(context, alarmId, isChainStep)
                }
                if (!snoozeOneShot) {
                    if (isChainStep) {
                        app.repository.scheduleFollowingChainStep(alarmId)
                    } else {
                        app.repository.scheduleFollowingFromDatabase(alarmId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.nierduolong.morningbell.ACTION_ALARM_FIRE"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_SNOOZE_ONE_SHOT = "extra_snooze_one_shot"
        const val EXTRA_IS_CHAIN_STEP = "extra_is_chain_step"
    }
}
