package com.nierduolong.morningbell.data

import android.content.Context
import com.nierduolong.morningbell.alarm.AlarmScheduler
import com.nierduolong.morningbell.core.AlarmTimeCalculator
import com.nierduolong.morningbell.core.BirthdayReminderLogic
import com.nierduolong.morningbell.core.MorningMicroTaskDefaults
import com.nierduolong.morningbell.core.StickyThemeRegistry
import com.nierduolong.morningbell.data.db.AlarmEntity
import com.nierduolong.morningbell.data.db.AppDatabase
import com.nierduolong.morningbell.data.db.ChainAlarmGroupEntity
import com.nierduolong.morningbell.data.db.ChainAlarmStepEntity
import com.nierduolong.morningbell.data.db.ChainDoneDayEntity
import com.nierduolong.morningbell.data.db.BirthdayEntity
import com.nierduolong.morningbell.data.db.BirthdayReminderEntity
import com.nierduolong.morningbell.data.db.GoalEntity
import com.nierduolong.morningbell.data.db.MicroTaskCustomEntity
import com.nierduolong.morningbell.data.db.MicroTaskDayEntity
import com.nierduolong.morningbell.data.db.MoodEntity
import com.nierduolong.morningbell.data.db.WakeDayEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.nierduolong.morningbell.weather.OpenMeteoWeather
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class AppRepository(
    private val context: Context,
    db: AppDatabase,
) {
    private val alarms = db.alarmDao()
    private val chainAlarms = db.chainAlarmDao()
    private val moods = db.moodDao()
    private val wakes = db.wakeDao()
    private val goals = db.goalDao()
    private val birthdays = db.birthdayDao()
    private val microTasks = db.microTaskDao()
    private val wakeSettings = WakeSettings(context)
    private val stickyThemeSettings = StickyThemeSettings(context)
    private val wakeThresholdMinuteOfDayState = MutableStateFlow(wakeSettings.getMinuteOfDay())
    private val stickyThemePackIdState =
        MutableStateFlow(stickyThemeSettings.getUserSelectedThemePack())

    val alarmFlow: Flow<List<AlarmEntity>> = alarms.observeAlarms()
    val chainGroupFlow: Flow<List<ChainAlarmGroupEntity>> = chainAlarms.observeGroups()
    val chainStepFlow: Flow<List<ChainAlarmStepEntity>> = chainAlarms.observeAllSteps()
    val moodFlow: Flow<List<MoodEntity>> = moods.observeRecent()
    val wakeFlow: Flow<List<WakeDayEntity>> = wakes.observeRecent()
    val goalFlow: Flow<List<GoalEntity>> = goals.observeGoals()
    val birthdayFlow: Flow<List<BirthdayEntity>> = birthdays.observeBirthdays()
    val microTaskCustomFlow: Flow<List<MicroTaskCustomEntity>> = microTasks.observeCustom()

    /** 早起统计起始时间（0 点起算分钟），用于界面与解锁判断同步 */
    val wakeThresholdMinuteOfDayFlow: StateFlow<Int> = wakeThresholdMinuteOfDayState.asStateFlow()

    /** 便利贴「语录」主题包 id，与 [StickyThemeSettings] 同步 */
    val stickyThemePackIdFlow: StateFlow<String> = stickyThemePackIdState.asStateFlow()

    suspend fun setWakeThresholdMinuteOfDay(minutes: Int) =
        withContext(Dispatchers.IO) {
            wakeSettings.setMinuteOfDay(minutes)
            val applied = wakeSettings.getMinuteOfDay()
            wakeThresholdMinuteOfDayState.value = applied
            // 起始时间上调后，当日已存解锁若早于新阈值则失效，需清除以免界面矛盾
            reconcileTodayWakeIfInvalid(applied)
        }

    suspend fun setStickyThemePack(id: String) =
        withContext(Dispatchers.IO) {
            stickyThemeSettings.setUserSelectedThemePack(id)
            stickyThemePackIdState.value = stickyThemeSettings.getUserSelectedThemePack()
        }

    /** 校验「今日」已记录的首次解锁是否仍不早于当前起始时刻；否则删除当日记录 */
    private suspend fun reconcileTodayWakeIfInvalid(thresholdMinuteOfDay: Int) {
        val today = LocalDate.now().toEpochDay()
        val row = wakes.getForDay(today) ?: return
        val zone = ZoneId.systemDefault()
        val unlockLocal =
            Instant.ofEpochMilli(row.firstUnlockMillis).atZone(zone).toLocalTime()
        val unlockMinuteOfDay = unlockLocal.hour * 60 + unlockLocal.minute
        if (unlockMinuteOfDay < thresholdMinuteOfDay) {
            wakes.deleteForDay(today)
        }
    }

    suspend fun getAlarm(id: Long): AlarmEntity? =
        withContext(Dispatchers.IO) { alarms.getById(id) }

    /** 连锁编辑弹窗用：读取整组 */
    suspend fun getChainGroup(groupId: Long): ChainAlarmGroupEntity? =
        withContext(Dispatchers.IO) { chainAlarms.getGroup(groupId) }

    suspend fun getChainSteps(groupId: Long): List<ChainAlarmStepEntity> =
        withContext(Dispatchers.IO) { chainAlarms.stepsForGroup(groupId) }

    /** 响铃界面与前台服务用；单闸闹钟无 vibrate 字段时默认震动开启 */
    data class AlarmRingProfile(
        val silent: Boolean,
        val vibrate: Boolean,
        val soundUri: String?,
    )

    suspend fun getAlarmRingProfile(
        scheduleId: Long,
        isChainStep: Boolean,
    ): AlarmRingProfile? =
        withContext(Dispatchers.IO) {
            if (isChainStep) {
                val step = chainAlarms.getStepById(scheduleId) ?: return@withContext null
                val g = chainAlarms.getGroup(step.groupId) ?: return@withContext null
                AlarmRingProfile(
                    silent = step.silent,
                    vibrate = step.vibrate,
                    soundUri = g.soundUri,
                )
            } else {
                val a = alarms.getById(scheduleId) ?: return@withContext null
                AlarmRingProfile(silent = a.silent, vibrate = true, soundUri = a.soundUri)
            }
        }

    suspend fun shouldSkipChainStepRing(stepId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val step = chainAlarms.getStepById(stepId) ?: return@withContext false
            val today = LocalDate.now().toEpochDay()
            val done = chainAlarms.getDoneForDay(step.groupId, today) ?: return@withContext false
            step.stepIndex > done.doneAfterStepIndex
        }

    /** 连锁某步点了「完成」：记录当日截断，取消并重排当日更晚的步骤 */
    suspend fun onChainStepDoneEarly(stepId: Long) =
        withContext(Dispatchers.IO) {
            val step = chainAlarms.getStepById(stepId) ?: return@withContext
            val g = chainAlarms.getGroup(step.groupId) ?: return@withContext
            val today = LocalDate.now().toEpochDay()
            chainAlarms.upsertDoneDay(
                ChainDoneDayEntity(
                    groupId = g.id,
                    dayEpoch = today,
                    doneAfterStepIndex = step.stepIndex,
                ),
            )
            val zone = ZoneId.systemDefault()
            val startTomorrow =
                LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val minExclusive = startTomorrow - 1
            val later = chainAlarms.stepsForGroup(g.id).filter { it.stepIndex > step.stepIndex }
            for (s in later) {
                AlarmScheduler.cancel(context, s.id, isChainStep = true)
                val next =
                    AlarmTimeCalculator.nextTriggerMillisAfter(
                        s.hour,
                        s.minute,
                        g.repeatDays,
                        minExclusive,
                    ) ?: continue
                AlarmScheduler.scheduleNext(context, s.id, next, snoozeOneShot = false, isChainStep = true)
            }
        }

    suspend fun deleteChainGroup(groupId: Long) =
        withContext(Dispatchers.IO) {
            val steps = chainAlarms.stepsForGroup(groupId)
            for (s in steps) {
                AlarmScheduler.cancel(context, s.id, isChainStep = true)
            }
            chainAlarms.deleteDoneForGroup(groupId)
            chainAlarms.deleteStepsForGroup(groupId)
            chainAlarms.deleteGroup(groupId)
        }

    suspend fun setChainGroupEnabled(
        groupId: Long,
        enabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        val g = chainAlarms.getGroup(groupId) ?: return@withContext
        val next = g.copy(enabled = enabled)
        chainAlarms.upsertGroup(next)
        if (enabled) {
            scheduleAllChainSteps(groupId)
        } else {
            chainAlarms.stepsForGroup(groupId).forEach {
                AlarmScheduler.cancel(context, it.id, isChainStep = true)
            }
        }
    }

    data class ChainStepDef(
        val hour: Int,
        val minute: Int,
        val silent: Boolean,
        val vibrate: Boolean,
    )

    /**
     * 保存连锁组：至少 2 步；按时间先后重排 stepIndex。
     */
    suspend fun upsertChainAlarm(
        group: ChainAlarmGroupEntity,
        stepDefs: List<ChainStepDef>,
    ): Long =
        withContext(Dispatchers.IO) {
            require(stepDefs.size >= 2) { "至少两个时间点" }
            val finalGid: Long =
                if (group.id == 0L) {
                    chainAlarms.upsertGroup(group)
                } else {
                    chainAlarms.stepsForGroup(group.id).forEach {
                        AlarmScheduler.cancel(context, it.id, isChainStep = true)
                    }
                    chainAlarms.deleteStepsForGroup(group.id)
                    chainAlarms.upsertGroup(group)
                    group.id
                }
            val sortedDefs = stepDefs.sortedBy { it.hour * 60 + it.minute }
            sortedDefs.forEachIndexed { idx, d ->
                chainAlarms.insertStep(
                    ChainAlarmStepEntity(
                        groupId = finalGid,
                        stepIndex = idx,
                        hour = d.hour,
                        minute = d.minute,
                        silent = d.silent,
                        vibrate = d.vibrate,
                    ),
                )
            }
            val saved = chainAlarms.getGroup(finalGid) ?: return@withContext finalGid
            if (saved.enabled) {
                scheduleAllChainSteps(finalGid)
            }
            finalGid
        }

    private suspend fun scheduleAllChainSteps(groupId: Long) {
        val g = chainAlarms.getGroup(groupId) ?: return
        if (!g.enabled) return
        chainAlarms.stepsForGroup(groupId).forEach { scheduleOneChainStep(it, g) }
    }

    private suspend fun scheduleOneChainStep(
        step: ChainAlarmStepEntity,
        g: ChainAlarmGroupEntity,
    ) {
        val next =
            AlarmTimeCalculator.nextTriggerMillis(
                step.hour,
                step.minute,
                g.repeatDays,
            ) ?: return
        AlarmScheduler.scheduleNext(
            context,
            step.id,
            next,
            snoozeOneShot = false,
            isChainStep = true,
        )
    }

    suspend fun scheduleFollowingChainStep(stepId: Long) =
        withContext(Dispatchers.IO) {
            val step = chainAlarms.getStepById(stepId) ?: return@withContext
            val g = chainAlarms.getGroup(step.groupId) ?: return@withContext
            if (!g.enabled) return@withContext
            val next =
                AlarmTimeCalculator.nextTriggerMillis(
                    step.hour,
                    step.minute,
                    g.repeatDays,
                ) ?: return@withContext
            AlarmScheduler.scheduleNext(
                context,
                stepId,
                next,
                snoozeOneShot = false,
                isChainStep = true,
            )
        }

    /** 当日首次解锁且不早于「早起统计起始」时写入起床时间（起始时刻可在设置里改，便于测试） */
    suspend fun recordUnlockIfMorning() =
        withContext(Dispatchers.IO) {
            val threshold = wakeSettings.getMinuteOfDay()
            val nowTime = java.time.LocalTime.now()
            val nowMinuteOfDay = nowTime.hour * 60 + nowTime.minute
            if (nowMinuteOfDay < threshold) return@withContext
            val today = LocalDate.now().toEpochDay()
            if (wakes.getForDay(today) != null) return@withContext
            wakes.upsert(WakeDayEntity(dayEpoch = today, firstUnlockMillis = System.currentTimeMillis()))
        }

    suspend fun seedIfEmpty() =
        withContext(Dispatchers.IO) {
            if (goals.activeGoals().isEmpty()) {
                goals.upsert(
                    GoalEntity(
                        title = "晨间散步 10 分钟",
                        deadlineEpochDay = LocalDate.now().plusWeeks(2).toEpochDay(),
                    ),
                )
            }
        }

    suspend fun upsertAlarm(entity: AlarmEntity): Long =
        withContext(Dispatchers.IO) {
            val id = alarms.upsert(entity)
            val saved = alarms.getById(id) ?: return@withContext id
            if (saved.enabled) {
                scheduleFollowingFromDatabase(saved.id)
            } else {
                AlarmScheduler.cancel(context, saved.id, isChainStep = false)
            }
            id
        }

    suspend fun deleteAlarm(id: Long) =
        withContext(Dispatchers.IO) {
            AlarmScheduler.cancel(context, id, isChainStep = false)
            alarms.delete(id)
        }

    suspend fun rescheduleAllEnabled() =
        withContext(Dispatchers.IO) {
            alarms.enabledAlarms().forEach { scheduleFollowingFromDatabase(it.id) }
            chainAlarms.enabledGroups().forEach { g ->
                scheduleAllChainSteps(g.id)
            }
        }

    /** 系统触发响铃（非贪睡）后，预排下一次合法触发点 */
    suspend fun scheduleFollowingFromDatabase(alarmId: Long) =
        withContext(Dispatchers.IO) {
            val alarm = alarms.getById(alarmId) ?: return@withContext
            if (!alarm.enabled) return@withContext
            val next =
                AlarmTimeCalculator.nextTriggerMillis(
                    alarm.hour,
                    alarm.minute,
                    alarm.repeatDays,
                ) ?: return@withContext
            AlarmScheduler.scheduleNext(
                context,
                alarmId,
                next,
                snoozeOneShot = false,
                isChainStep = false,
            )
        }

    suspend fun scheduleSnoozeFiveMinutes(
        alarmId: Long,
        isChainStep: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val at = AlarmTimeCalculator.snoozeEpochMillis(5)
        AlarmScheduler.scheduleNext(
            context,
            alarmId,
            at,
            snoozeOneShot = true,
            isChainStep = isChainStep,
        )
    }

    suspend fun insertMood(score: Int) =
        withContext(Dispatchers.IO) {
            val day = LocalDate.now().toEpochDay()
            val existing = moods.getForDay(day)
            if (existing != null) {
                moods.insert(MoodEntity(id = existing.id, dayEpoch = day, score = score))
            } else {
                moods.insert(MoodEntity(dayEpoch = day, score = score))
            }
        }

    suspend fun upsertBirthday(b: BirthdayEntity): Long =
        withContext(Dispatchers.IO) { birthdays.upsertBirthday(b) }

    suspend fun upsertReminder(r: BirthdayReminderEntity): Long =
        withContext(Dispatchers.IO) { birthdays.upsertReminder(r) }

    suspend fun deleteBirthday(id: Long) =
        withContext(Dispatchers.IO) {
            birthdays.deleteRemindersForBirthday(id)
            birthdays.deleteBirthday(id)
        }

    suspend fun deleteReminder(id: Long) =
        withContext(Dispatchers.IO) {
            birthdays.deleteReminder(id)
        }

    suspend fun upsertGoal(goal: GoalEntity): Long =
        withContext(Dispatchers.IO) { goals.upsert(goal) }

    suspend fun markGoalCompleted(id: Long) =
        withContext(Dispatchers.IO) {
            val g = goals.getById(id) ?: return@withContext
            if (!g.completed) {
                goals.update(g.copy(completed = true))
            }
        }

    suspend fun setGoalCompleted(
        id: Long,
        completed: Boolean,
    ) = withContext(Dispatchers.IO) {
        val g = goals.getById(id) ?: return@withContext
        if (g.completed != completed) {
            goals.update(g.copy(completed = completed))
        }
    }

    suspend fun deleteGoal(id: Long) =
        withContext(Dispatchers.IO) {
            goals.delete(id)
        }

    suspend fun loadReminders(birthdayId: Long): List<BirthdayReminderEntity> =
        withContext(Dispatchers.IO) { birthdays.remindersFor(birthdayId) }

    suspend fun addCustomMicroTask(text: String) =
        withContext(Dispatchers.IO) {
            val t = text.trim()
            if (t.isNotEmpty()) {
                microTasks.insertCustom(MicroTaskCustomEntity(text = t))
            }
        }

    suspend fun deleteCustomMicroTask(id: Long) =
        withContext(Dispatchers.IO) {
            microTasks.deleteCustom(id)
        }

    /** 换一条今日小任务（未完成时） */
    suspend fun swapTodayMicroTask(): MicroTaskFlowSlot =
        withContext(Dispatchers.IO) {
            val dayEpoch = LocalDate.now().toEpochDay()
            var row = microTasks.getDay(dayEpoch) ?: ensureMicroTaskForDay(dayEpoch)
            if (row.completed) {
                return@withContext MicroTaskFlowSlot(taskText = row.taskText, completed = true)
            }
            val pool = poolForMicroTasks().distinct()
            val withoutCurrent = pool.filter { it != row.taskText }
            val usePool = if (withoutCurrent.isNotEmpty()) withoutCurrent else pool
            val seed = dayEpoch xor ((row.swapCount + 1).toLong() shl 17)
            val next = MorningMicroTaskDefaults.pickTaskFromPool(usePool, seed)
            row = row.copy(taskText = next, swapCount = row.swapCount + 1)
            microTasks.upsertDay(row)
            MicroTaskFlowSlot(taskText = row.taskText, completed = false)
        }

    /** 标记今日小任务完成并返回一句反馈文案 */
    suspend fun completeTodayMicroTask(): MicroTaskCompleteResult =
        withContext(Dispatchers.IO) {
            val dayEpoch = LocalDate.now().toEpochDay()
            var row = microTasks.getDay(dayEpoch) ?: ensureMicroTaskForDay(dayEpoch)
            val now = System.currentTimeMillis()
            if (row.completed) {
                val seed = dayEpoch xor (row.completedAtMillis ?: now)
                return@withContext MicroTaskCompleteResult(
                    slot = MicroTaskFlowSlot(row.taskText, true),
                    praise = MorningMicroTaskDefaults.pickPraise(seed),
                )
            }
            row = row.copy(completed = true, completedAtMillis = now)
            microTasks.upsertDay(row)
            val seed = dayEpoch xor now xor row.taskText.hashCode().toLong()
            MicroTaskCompleteResult(
                slot = MicroTaskFlowSlot(row.taskText, true),
                praise = MorningMicroTaskDefaults.pickPraise(seed),
            )
        }

    /** 组装关闭闹钟后的卡片内容 */
    suspend fun buildDismissFlowCards(): DismissFlowModel =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            val dayEpoch = today.toEpochDay()
            val bList = birthdays.allBirthdays()
            val rList = birthdays.allReminders()
            val due =
                BirthdayReminderLogic.collectDueCards(
                    today = today,
                    birthdays = bList,
                    reminders = rList,
                )
            val gs = goals.activeGoals()
            val sticky = buildSticky(gs, today)
            val microRow = ensureMicroTaskForDay(dayEpoch)
            val microTask =
                MicroTaskFlowSlot(
                    taskText = microRow.taskText,
                    completed = microRow.completed,
                )
            DismissFlowModel(
                birthdayCards = due,
                sticky = sticky,
                microTask = microTask,
            )
        }

    private suspend fun poolForMicroTasks(): List<String> {
        val custom =
            microTasks.allCustom().map { it.text.trim() }.filter { it.isNotBlank() }
        return MorningMicroTaskDefaults.builtinTasks + custom
    }

    /** 若无当日行则抽一条并落库 */
    private suspend fun ensureMicroTaskForDay(dayEpoch: Long): MicroTaskDayEntity {
        microTasks.getDay(dayEpoch)?.let { return it }
        val pool = poolForMicroTasks()
        val text = MorningMicroTaskDefaults.pickTaskFromPool(pool, dayEpoch)
        val row =
            MicroTaskDayEntity(
                dayEpoch = dayEpoch,
                taskText = text,
                completed = false,
                completedAtMillis = null,
                swapCount = 0,
            )
        microTasks.upsertDay(row)
        return row
    }

    private suspend fun buildSticky(
        activeGoals: List<GoalEntity>,
        today: LocalDate,
    ): StickyPayload {
        val roll = Random.nextInt(3)
        return when {
            roll == 0 && activeGoals.isNotEmpty() -> {
                val g = activeGoals.random()
                val days =
                    BirthdayReminderLogic.daysUntilDeadline(
                        today,
                        g.deadlineEpochDay,
                    )
                StickyPayload.GoalSticky(goalId = g.id, title = g.title, daysUntil = days)
            }
            roll == 1 -> {
                val packId = stickyThemeSettings.getUserSelectedThemePack()
                val pack = StickyThemeRegistry.packOrDefault(packId)
                val line = pack.randomQuote()
                StickyPayload.QuoteSticky(
                    text = line,
                    quoteCategory = pack.quoteCategory,
                    cardTheme = pack.cardTheme,
                    userSelectedThemePack = pack.id,
                    packTagline = pack.tagline,
                )
            }
            else ->
                StickyPayload.WeatherSticky(
                    OpenMeteoWeather.fetchTodayLine(context),
                )
        }
    }

    sealed interface StickyPayload {
        data class GoalSticky(
            val goalId: Long,
            val title: String,
            val daysUntil: Long?,
        ) : StickyPayload

        data class QuoteSticky(
            val text: String,
            /** 语录大类（与目标/天气区分） */
            val quoteCategory: String,
            /** 主题包展示名 */
            val cardTheme: String,
            /** 当前选中的主题包 id，与设置一致 */
            val userSelectedThemePack: String,
            /** 主题包一句话定位 */
            val packTagline: String,
        ) : StickyPayload

        data class WeatherSticky(
            val line: String,
        ) : StickyPayload
    }

    data class DismissFlowModel(
        val birthdayCards: List<BirthdayReminderLogic.DueCard>,
        val sticky: StickyPayload,
        val microTask: MicroTaskFlowSlot,
    )

    data class MicroTaskFlowSlot(
        val taskText: String,
        val completed: Boolean,
    )

    data class MicroTaskCompleteResult(
        val slot: MicroTaskFlowSlot,
        val praise: String,
    )
}
