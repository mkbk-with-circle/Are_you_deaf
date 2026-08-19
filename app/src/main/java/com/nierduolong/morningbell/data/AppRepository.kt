package com.nierduolong.morningbell.data

import android.content.Context
import com.nierduolong.morningbell.alarm.AlarmScheduler
import com.nierduolong.morningbell.alarm.BirthdayAlarmScheduler
import com.nierduolong.morningbell.core.AlarmTimeCalculator
import com.nierduolong.morningbell.core.BirthdayReminderLogic
import com.nierduolong.morningbell.core.LunarBirthdayCalendar
import com.nierduolong.morningbell.core.RetentionPolicy
import com.nierduolong.morningbell.core.StickyThemeRegistry
import com.nierduolong.morningbell.dailylog.DailyLogStorage
import com.nierduolong.morningbell.dailylog.lan.DeviceIdentity
import com.nierduolong.morningbell.dailylog.lan.SyncRetryPolicy
import com.nierduolong.morningbell.data.db.AlarmEntity
import com.nierduolong.morningbell.data.db.AppDatabase
import com.nierduolong.morningbell.data.db.ChainAlarmGroupEntity
import com.nierduolong.morningbell.data.db.ChainAlarmStepEntity
import com.nierduolong.morningbell.data.db.ChainDoneDayEntity
import com.nierduolong.morningbell.data.db.BirthdayEntity
import com.nierduolong.morningbell.data.db.BirthdayReminderEntity
import com.nierduolong.morningbell.data.db.ReminderTemplateEntity
import com.nierduolong.morningbell.data.db.GoalEntity
import com.nierduolong.morningbell.data.db.MoodEntity
import com.nierduolong.morningbell.data.db.DailyLogEntity
import com.nierduolong.morningbell.data.db.DayLogSummary
import com.nierduolong.morningbell.data.db.LogClipEntity
import com.nierduolong.morningbell.data.db.DailyCompilationEntity
import com.nierduolong.morningbell.data.db.LogCommentEntity
import com.nierduolong.morningbell.data.db.LogMemberEntity
import com.nierduolong.morningbell.data.db.SyncEventEntity
import com.nierduolong.morningbell.data.db.SyncOutboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nierduolong.morningbell.weather.OpenMeteoWeather
import com.nierduolong.morningbell.R
import java.time.Instant
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.json.JSONObject
import kotlin.random.Random

class AppRepository(
    private val context: Context,
    private val db: AppDatabase,
) {
    private val alarms = db.alarmDao()
    private val chainAlarms = db.chainAlarmDao()
    private val moods = db.moodDao()
    private val goals = db.goalDao()
    private val birthdays = db.birthdayDao()
    private val reminderTemplates = db.reminderTemplateDao()
    private val dailyLog = db.dailyLogDao()
    private val stickyThemeSettings = StickyThemeSettings(context)
    private val dailyLogSettings = DailyLogSettings(context)
    private val stickyThemePackIdState =
        MutableStateFlow(stickyThemeSettings.getUserSelectedThemePack())
    private val personalLogIdState = MutableStateFlow<Long?>(null)
    private val currentLogIdState = MutableStateFlow<Long?>(null)
    private val syncOperationMutex = Mutex()

    val alarmFlow: Flow<List<AlarmEntity>> = alarms.observeAlarms()
    val chainGroupFlow: Flow<List<ChainAlarmGroupEntity>> = chainAlarms.observeGroups()
    val chainStepFlow: Flow<List<ChainAlarmStepEntity>> = chainAlarms.observeAllSteps()
    val moodFlow: Flow<List<MoodEntity>> = moods.observeRecent()
    val goalFlow: Flow<List<GoalEntity>> = goals.observeGoals()
    val birthdayFlow: Flow<List<BirthdayEntity>> = birthdays.observeBirthdays()
    val reminderTemplateFlow: Flow<List<ReminderTemplateEntity>> = reminderTemplates.observeTemplates()

    /** 便利贴「语录」主题包 id，与 [StickyThemeSettings] 同步 */
    val stickyThemePackIdFlow: StateFlow<String> = stickyThemePackIdState.asStateFlow()

    /** 本机唯一的个人日志 id；[ensurePersonalDailyLog] 完成前为 null */
    val personalLogIdFlow: StateFlow<Long?> = personalLogIdState.asStateFlow()

    /** 当前正在浏览/拍摄的 Log；默认是个人 Log，附近房间创建后可显式切换。 */
    val currentLogIdFlow: StateFlow<Long?> = currentLogIdState.asStateFlow()

    val dailyLogsFlow: Flow<List<DailyLogEntity>> = dailyLog.observeLogs()

    suspend fun setStickyThemePack(id: String) =
        withContext(Dispatchers.IO) {
            stickyThemeSettings.setUserSelectedThemePack(id)
            stickyThemePackIdState.value = stickyThemeSettings.getUserSelectedThemePack()
        }

    /** 响铃通知文案 + 当前周期公历生日 epochDay（用于 ack） */
    data class BirthdayNotifyBundle(
        val title: String,
        val body: String,
        val eventEpochDay: Long,
    )

    suspend fun getBirthdayReminderNotifyBundle(reminderId: Long): BirthdayNotifyBundle? =
        withContext(Dispatchers.IO) {
            val r = birthdays.getReminderById(reminderId) ?: return@withContext null
            val b = birthdays.getBirthdayById(r.birthdayId) ?: return@withContext null
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val eventDate =
                BirthdayReminderLogic.activeCycleEventDate(b, r, zone, now)
                    ?: LunarBirthdayCalendar.solarEventDateThisYear(b, LocalDate.now())
            val body =
                buildString {
                    if (r.daysBefore == 0) {
                        append("今天生日：")
                    } else {
                        append("提前 ${r.daysBefore} 天：")
                    }
                    append(r.todoText)
                }
            BirthdayNotifyBundle(
                title = "「${b.name}」生日提醒",
                body = body,
                eventEpochDay = eventDate.toEpochDay(),
            )
        }

    /** 用户标记本周期已处理：写入 ack 并重排闹钟 */
    suspend fun ackBirthdayReminderForEventCycle(
        reminderId: Long,
        eventEpochDay: Long,
    ) = withContext(Dispatchers.IO) {
        val r = birthdays.getReminderById(reminderId) ?: return@withContext
        birthdays.upsertReminder(r.copy(lastAcknowledgedEventEpochDay = eventEpochDay))
        rescheduleAllBirthdayReminders()
    }

    /** 所有生日条目重排下一次 0 点（数据变更、换日、开机后调用） */
    suspend fun rescheduleAllBirthdayReminders() =
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val bMap = birthdays.allBirthdays().associateBy { it.id }
            for (r in birthdays.allReminders()) {
                BirthdayAlarmScheduler.cancel(context, r.id)
                val birth = bMap[r.birthdayId] ?: continue
                val next =
                    BirthdayReminderLogic.nextBirthdayAlarmScheduleMillis(
                        birth,
                        r,
                        zone,
                        now,
                    ) ?: continue
                BirthdayAlarmScheduler.schedule(context, r.id, next, snoozeOneShot = false)
            }
        }

    /** 一次响铃结束后，预约下一年（或下一次）同一触发日 0 点 */
    suspend fun scheduleFollowingBirthdayReminder(reminderId: Long) =
        withContext(Dispatchers.IO) {
            val r = birthdays.getReminderById(reminderId) ?: return@withContext
            val b = birthdays.getBirthdayById(r.birthdayId) ?: return@withContext
            BirthdayAlarmScheduler.cancel(context, reminderId)
            val zone = ZoneId.systemDefault()
            val next =
                BirthdayReminderLogic.nextBirthdayAlarmScheduleMillis(
                    b,
                    r,
                    zone,
                    ZonedDateTime.now(zone),
                ) ?: return@withContext
            BirthdayAlarmScheduler.schedule(context, reminderId, next, snoozeOneShot = false)
        }

    suspend fun scheduleBirthdayReminderSnooze(reminderId: Long) =
        withContext(Dispatchers.IO) {
            val at = AlarmTimeCalculator.snoozeEpochMillis(5)
            BirthdayAlarmScheduler.schedule(context, reminderId, at, snoozeOneShot = true)
        }

    suspend fun getAlarm(id: Long): AlarmEntity? =
        withContext(Dispatchers.IO) { alarms.getById(id) }

    /** 连锁编辑弹窗用：读取整组 */
    suspend fun getChainGroup(groupId: Long): ChainAlarmGroupEntity? =
        withContext(Dispatchers.IO) { chainAlarms.getGroup(groupId) }

    suspend fun getChainSteps(groupId: Long): List<ChainAlarmStepEntity> =
        withContext(Dispatchers.IO) { chainAlarms.stepsForGroup(groupId) }

    /** 响铃页与通知：标题 + 副标题（优先展示用户备注） */
    data class AlarmRingUiLines(
        val title: String,
        val subtitle: String,
    )

    suspend fun getAlarmRingUiLines(scheduleId: Long, isChainStep: Boolean): AlarmRingUiLines? =
        withContext(Dispatchers.IO) {
            if (isChainStep) {
                val step = chainAlarms.getStepById(scheduleId) ?: return@withContext null
                val g = chainAlarms.getGroup(step.groupId) ?: return@withContext null
                val title = context.getString(R.string.alarm_ring_chain_title_fmt, step.stepIndex + 1)
                val subtitle =
                    if (g.note.isNotBlank()) {
                        g.note.trim()
                    } else {
                        context.getString(
                            R.string.alarm_ring_chain_no_group_note_fmt,
                            step.hour,
                            step.minute,
                        )
                    }
                AlarmRingUiLines(title, subtitle)
            } else {
                val a = alarms.getById(scheduleId) ?: return@withContext null
                val title = context.getString(R.string.alarm_ring_single_title_fmt, a.hour, a.minute)
                val subtitle =
                    if (a.note.isNotBlank()) {
                        a.note.trim()
                    } else {
                        context.getString(R.string.alarm_ring_no_note_hint)
                    }
                AlarmRingUiLines(title, subtitle)
            }
        }

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
            rescheduleAllBirthdayReminders()
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
        withContext(Dispatchers.IO) {
            val prev = if (b.id != 0L) birthdays.getBirthdayById(b.id) else null
            val id = birthdays.upsertBirthday(b)
            // 公历/农历或月日变更后，本年 ack 不再可信，清空以免影响新一轮提醒
            if (prev != null &&
                (prev.month != b.month || prev.day != b.day || prev.isLunar != b.isLunar)
            ) {
                birthdays.remindersFor(id).forEach { r ->
                    birthdays.upsertReminder(r.copy(lastAcknowledgedEventEpochDay = null))
                }
            }
            rescheduleAllBirthdayReminders()
            id
        }

    /** 某人生日下的提醒列表（随增删实时刷新） */
    fun remindersForBirthdayFlow(birthdayId: Long): Flow<List<BirthdayReminderEntity>> =
        birthdays.observeRemindersForBirthday(birthdayId)

    suspend fun upsertReminder(r: BirthdayReminderEntity): Long =
        withContext(Dispatchers.IO) {
            val merged =
                if (r.id != 0L) {
                    val previous = birthdays.getReminderById(r.id)
                    if (previous != null &&
                        previous.daysBefore == r.daysBefore &&
                        previous.todoText == r.todoText
                    ) {
                        r.copy(lastAcknowledgedEventEpochDay = previous.lastAcknowledgedEventEpochDay)
                    } else {
                        r.copy(lastAcknowledgedEventEpochDay = null)
                    }
                } else {
                    r
                }
            val id = birthdays.upsertReminder(merged)
            rescheduleAllBirthdayReminders()
            id
        }

    suspend fun insertReminderTemplate(text: String) =
        withContext(Dispatchers.IO) {
            val t = text.trim()
            if (t.isNotEmpty()) {
                reminderTemplates.insert(ReminderTemplateEntity(text = t))
            }
        }

    suspend fun deleteReminderTemplate(id: Long) =
        withContext(Dispatchers.IO) {
            reminderTemplates.delete(id)
        }

    suspend fun deleteBirthday(id: Long) =
        withContext(Dispatchers.IO) {
            birthdays.remindersFor(id).forEach { BirthdayAlarmScheduler.cancel(context, it.id) }
            birthdays.deleteRemindersForBirthday(id)
            birthdays.deleteBirthday(id)
        }

    suspend fun deleteReminder(id: Long) =
        withContext(Dispatchers.IO) {
            BirthdayAlarmScheduler.cancel(context, id)
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

    /** 组装关闭闹钟后的卡片内容（生日提醒已改为独立 0 点闹钟，此处不再插生日卡片） */
    suspend fun buildDismissFlowCards(): DismissFlowModel =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            val gs = goals.activeGoals()
            val sticky = buildSticky(gs, today)
            DismissFlowModel(
                birthdayCards = emptyList(),
                sticky = sticky,
            )
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
    )

    // ---------------------------------------------------------------------
    // Setlog 风格「每日日志」：Log / 拍摄素材 / 每日合成 / 留言
    // ---------------------------------------------------------------------

    /** 确保存在唯一的本机个人 Log，并把 id 发布到 [personalLogIdFlow]（功能 1，Phase 2 多人预留） */
    suspend fun ensurePersonalDailyLog(): Long =
        withContext(Dispatchers.IO) {
            val existing = dailyLog.getPersonalLog()
            val id =
                existing?.id ?: dailyLog.upsertLog(
                    DailyLogEntity(name = "我的日志", isPersonal = true),
                )
            personalLogIdState.value = id
            if (currentLogIdState.value == null) currentLogIdState.value = id
            id
        }

    suspend fun selectDailyLog(logId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (dailyLog.getLog(logId) == null) return@withContext false
            currentLogIdState.value = logId
            true
        }

    suspend fun getDailyLog(logId: Long): DailyLogEntity? =
        withContext(Dispatchers.IO) { dailyLog.getLog(logId) }

    suspend fun nearbyMemberLogs(): List<DailyLogEntity> =
        withContext(Dispatchers.IO) { dailyLog.memberLogs() }

    /** 创建由本机担任权威节点的附近 Log。热点服务关闭后记录仍会保留，方便下次重开。 */
    suspend fun createNearbyDailyLog(
        name: String,
        hostDeviceId: String,
        inviteCode: String,
    ): DailyLogEntity =
        withContext(Dispatchers.IO) {
            val draft =
                DailyLogEntity(
                    name = name.trim().ifEmpty { "附近日志" },
                    isPersonal = false,
                    inviteCode = inviteCode,
                    remoteId = UUID.randomUUID().toString(),
                    role = "owner",
                    hostDeviceId = hostDeviceId,
                )
            val id = dailyLog.upsertLog(draft)
            currentLogIdState.value = id
            draft.copy(id = id)
        }

    suspend fun joinNearbyDailyLog(
        remoteId: String,
        name: String,
        hostDeviceId: String?,
        inviteCode: String,
        memberCount: Int,
        hostAddress: String,
        hostPort: Int,
        hostServiceName: String,
    ): DailyLogEntity =
        withContext(Dispatchers.IO) {
            val existing = dailyLog.getLogByRemoteId(remoteId)
            val value =
                DailyLogEntity(
                    id = existing?.id ?: 0,
                    name = name,
                    isPersonal = false,
                    inviteCode = inviteCode,
                    remoteId = remoteId,
                    role = "member",
                    hostDeviceId = hostDeviceId,
                    memberCount = memberCount.coerceAtLeast(1),
                    lastSyncCursor = existing?.lastSyncCursor ?: 0,
                    lastSyncedAt = System.currentTimeMillis(),
                    lastHostAddress = hostAddress,
                    lastHostPort = hostPort,
                    lastHostServiceName = hostServiceName,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                )
            val id = dailyLog.upsertLog(value)
            currentLogIdState.value = id
            value.copy(id = id)
        }

    fun logMembersFlow(logId: Long): Flow<List<LogMemberEntity>> = dailyLog.observeMembers(logId)

    suspend fun upsertLogMember(member: LogMemberEntity): Long =
        withContext(Dispatchers.IO) {
            val existing = dailyLog.getMember(member.logId, member.authorId)
            val id =
                dailyLog.upsertMember(
                    member.copy(
                        id = existing?.id ?: member.id,
                        sourceAddress = member.sourceAddress ?: existing?.sourceAddress,
                        sourcePort = member.sourcePort ?: existing?.sourcePort,
                        joinedAt = existing?.joinedAt ?: member.joinedAt,
                    ),
                )
            dailyLog.updateMemberCount(member.logId, dailyLog.members(member.logId).size.coerceAtLeast(1))
            id
        }

    suspend fun logMembers(logId: Long): List<LogMemberEntity> =
        withContext(Dispatchers.IO) { dailyLog.members(logId) }

    suspend fun updateMemberSource(
        logId: Long,
        authorId: String,
        address: String,
        port: Int,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dailyLog.updateMemberSource(logId, authorId, address, port, System.currentTimeMillis()) > 0
        }

    suspend fun touchLogMember(
        logId: Long,
        authorId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dailyLog.touchMember(logId, authorId, System.currentTimeMillis()) > 0
        }

    suspend fun getClipByClientUuid(
        logId: Long,
        clientUuid: String,
    ): LogClipEntity? =
        withContext(Dispatchers.IO) { dailyLog.getClipByClientUuid(logId, clientUuid)?.resolved() }

    // -- 路径归一化的唯一切面 --------------------------------------------
    // 库里存相对路径，界面与播放器要的是绝对路径。转换只发生在这一层：
    // 上层拿到的永远是可直接打开的绝对路径，写入时永远被压回相对路径。

    private fun absolute(stored: String): String = if (stored.isBlank()) "" else DailyLogStorage.resolve(context, stored).absolutePath

    private fun LogClipEntity.resolved(): LogClipEntity =
        copy(
            filePath = absolute(filePath),
            localThumbPath = localThumbPath?.let(::absolute),
        )

    private fun DailyCompilationEntity.resolved(): DailyCompilationEntity = copy(filePath = absolute(filePath))

    fun clipsFlow(logId: Long): Flow<List<LogClipEntity>> =
        dailyLog.observeClips(logId).map { list -> list.map { it.resolved() } }

    fun clipsForDayFlow(
        logId: Long,
        dayEpoch: Long,
    ): Flow<List<LogClipEntity>> = dailyLog.observeClipsForDay(logId, dayEpoch).map { list -> list.map { it.resolved() } }

    /**
     * 归档页数据源：每天的素材条数、总时长、最后一条时间与封面。
     * 原始素材已按保留策略清理的日期没有 clip 封面，这里回落到当天的合成结果，
     * 否则那些天在归档里会变成一片空白灰格。
     */
    fun daySummariesFlow(logId: Long): Flow<List<DayLogSummary>> =
        dailyLog.observeDaySummaries(logId).combine(dailyLog.observeCompilations(logId)) { summaries, compilations ->
            val compiledPaths = compilations.associate { it.dayEpoch to it.filePath }
            summaries.map { summary ->
                val cover = summary.coverPath ?: compiledPaths[summary.dayEpoch]
                summary.copy(
                    coverPath = cover?.let { absolute(it) },
                    coverThumbPath = summary.coverThumbPath?.let { absolute(it) },
                )
            }
        }

    fun compilationsFlow(logId: Long): Flow<List<DailyCompilationEntity>> =
        dailyLog.observeCompilations(logId).map { list -> list.map { it.resolved() } }

    fun commentsFlow(clipId: Long): Flow<List<LogCommentEntity>> = dailyLog.observeComments(clipId)

    /**
     * 拍摄完成后落库（功能 3）。
     * [dayEpoch] 由调用方传入录制开始那一刻的日期，避免跨零点录制时素材被归到第二天、
     * 与文件所在的日期目录不一致。
     */
    suspend fun insertLogClip(
        logId: Long,
        filePath: String,
        durationMs: Long,
        caption: String?,
        dayEpoch: Long = LocalDate.now().toEpochDay(),
    ): Long =
        withContext(Dispatchers.IO) {
            val log = dailyLog.getLog(logId)
            val authorId =
                if (log?.isPersonal == false) {
                    runCatching { DeviceIdentity.getOrCreate().deviceId }.getOrNull()
                } else {
                    null
                }
            dailyLog.insertClip(
                LogClipEntity(
                    logId = logId,
                    dayEpoch = dayEpoch,
                    filePath = DailyLogStorage.relativize(context, filePath),
                    durationMs = durationMs,
                    caption = caption?.trim()?.takeIf { it.isNotEmpty() },
                    clientUuid = UUID.randomUUID().toString(),
                    authorId = authorId,
                ),
            )
        }

    /** 合并主机下发的素材元数据；若本机已有原片，绝不以空远端记录覆盖本地路径。 */
    suspend fun upsertRemoteClipMetadata(
        logId: Long,
        clientUuid: String,
        authorId: String?,
        dayEpoch: Long,
        durationMs: Long,
        caption: String?,
        createdAt: Long,
        contentSha256: String?,
    ): Long =
        withContext(Dispatchers.IO) {
            val existing = dailyLog.getClipByClientUuid(logId, clientUuid)
            if (existing?.transferState == "deleted") return@withContext existing.id
            val value =
                LogClipEntity(
                    id = existing?.id ?: 0,
                    logId = logId,
                    dayEpoch = dayEpoch,
                    filePath = existing?.filePath.orEmpty(),
                    durationMs = durationMs,
                    caption = caption,
                    createdAt = createdAt,
                    clientUuid = clientUuid,
                    remoteId = existing?.remoteId,
                    authorId = authorId,
                    localThumbPath = existing?.localThumbPath,
                    transferState = if (existing?.filePath.isNullOrBlank()) "available_remote" else "local",
                    contentSha256 = contentSha256,
                    sourceKept = existing?.sourceKept == true,
                )
            dailyLog.upsertClip(value)
        }

    suspend fun saveRemoteThumbnail(
        logId: Long,
        clientUuid: String,
        absolutePath: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val updated = dailyLog.updateClipThumbnail(
                logId,
                clientUuid,
                DailyLogStorage.relativize(context, absolutePath),
            ) > 0
            if (updated) DailyLogStorage.trimRemoteThumbnailCache(context)
            updated
        }

    /** 首次共享前以固定 64 KiB 缓冲计算内容摘要；只写 64 个十六进制字符，不复制视频。 */
    suspend fun ensureClipSha256(clip: LogClipEntity): LogClipEntity =
        withContext(Dispatchers.IO) {
            if (clip.contentSha256?.length == 64) return@withContext clip
            val file = File(clip.filePath)
            if (!file.isFile) return@withContext clip
            val sha = com.nierduolong.morningbell.dailylog.lan.LanStreamRelay.sha256(file)
            dailyLog.updateClipSha256(clip.id, sha)
            clip.copy(contentSha256 = sha)
        }

    suspend fun pendingSyncOperations(
        logId: Long,
        limit: Int = 50,
    ): List<SyncOutboxEntity> =
        withContext(Dispatchers.IO) { dailyLog.pendingOutbox(logId, System.currentTimeMillis(), limit) }

    suspend fun acknowledgeSyncOperation(operationId: String) =
        withContext(Dispatchers.IO) { dailyLog.acknowledgeOutbox(operationId) }

    suspend fun postponeSyncOperation(item: SyncOutboxEntity) =
        withContext(Dispatchers.IO) {
            val attempts = item.attempts + 1
            val delay = SyncRetryPolicy.nextDelayMs(attempts)
            dailyLog.postponeOutbox(item.operationId, attempts, System.currentTimeMillis() + delay)
        }

    suspend fun syncEventsAfter(
        logId: Long,
        after: Long,
        limit: Int = 200,
    ): List<SyncEventEntity> = withContext(Dispatchers.IO) { dailyLog.syncEventsAfter(logId, after, limit) }

    /** 主机的幂等写入口：同一个 operationId 无论重试多少次，都只应用并广播一次。 */
    suspend fun acceptSyncOperation(
        logId: Long,
        actorId: String,
        operationId: String,
        entityType: String,
        operation: String,
        payloadJson: String,
    ): Long =
        withContext(Dispatchers.IO) {
            syncOperationMutex.withLock {
                dailyLog.getSyncEventByOperationId(logId, operationId)?.id?.let { return@withLock it }
                applyOperationPayload(logId, entityType, operation, payloadJson, actorId)
                dailyLog.insertSyncEvent(
                    SyncEventEntity(
                        logId = logId,
                        operationId = operationId,
                        entityType = entityType,
                        operation = operation,
                        payloadJson = payloadJson,
                    ),
                )
            }
        }

    suspend fun applyRemoteSyncEvents(
        logId: Long,
        events: List<SyncEventEntity>,
        cursor: Long,
    ) = withContext(Dispatchers.IO) {
        syncOperationMutex.withLock {
            events.forEach { applyOperationPayload(logId, it.entityType, it.operation, it.payloadJson, actorId = null) }
            dailyLog.updateSyncCursor(logId, cursor, System.currentTimeMillis())
        }
    }

    private suspend fun applyOperationPayload(
        logId: Long,
        entityType: String,
        operation: String,
        payloadJson: String,
        actorId: String?,
    ) {
        val payload = JSONObject(payloadJson)
        when (entityType) {
            "comment" -> {
                val clipUuid = payload.optString("clipUuid").takeIf { it.isNotBlank() } ?: error("留言缺少素材 id")
                val clip = dailyLog.getClipByClientUuid(logId, clipUuid) ?: return
                val commentUuid = payload.optString("commentUuid").takeIf { it.isNotBlank() } ?: error("留言 id 无效")
                val existing = dailyLog.getCommentByClientUuid(commentUuid)
                dailyLog.upsertComment(
                    LogCommentEntity(
                        id = existing?.id ?: 0,
                        clipId = clip.id,
                        authorName = payload.optString("authorName").take(40).ifBlank { "成员" },
                        text = payload.optString("text").take(2_000),
                        createdAt = payload.optLong("createdAt", System.currentTimeMillis()),
                        clientUuid = commentUuid,
                        authorId = payload.optString("authorId").takeIf { it.isNotBlank() },
                        deleted = operation == "delete" || payload.optBoolean("deleted", false),
                    ),
                )
            }
            "clip" -> {
                val clipUuid = payload.optString("clipUuid").takeIf { it.isNotBlank() } ?: error("素材 id 无效")
                val clip = dailyLog.getClipByClientUuid(logId, clipUuid) ?: return
                if (actorId != null) {
                    val log = dailyLog.getLog(logId) ?: error("Log 不存在")
                    if (actorId != clip.authorId && actorId != log.hostDeviceId) error("无权修改别人的素材")
                }
                when (operation) {
                    "delete" -> {
                        if (clip.sourceKept && clip.filePath.isNotBlank()) {
                            runCatching { DailyLogStorage.resolve(context, clip.filePath).delete() }
                        }
                        dailyLog.updateClip(
                            clip.copy(filePath = "", sourceKept = false, transferState = "deleted", localThumbPath = null),
                        )
                    }
                    "caption" -> dailyLog.updateClip(
                        clip.copy(caption = payload.optString("caption").trim().take(500).takeIf(String::isNotEmpty)),
                    )
                    else -> error("不支持的素材操作")
                }
            }
            else -> error("不支持的同步实体")
        }
    }

    private suspend fun queueOrPublishOperation(
        log: DailyLogEntity,
        entityType: String,
        entityClientUuid: String,
        operation: String,
        payload: JSONObject,
    ) {
        if (log.isPersonal) return
        val operationId = UUID.randomUUID().toString()
        if (log.role == "owner") {
            dailyLog.insertSyncEvent(
                SyncEventEntity(
                    logId = log.id,
                    operationId = operationId,
                    entityType = entityType,
                    operation = operation,
                    payloadJson = payload.toString(),
                ),
            )
        } else {
            dailyLog.enqueueOutbox(
                SyncOutboxEntity(
                    logId = log.id,
                    operationId = operationId,
                    entityType = entityType,
                    entityClientUuid = entityClientUuid,
                    operation = operation,
                    payloadJson = payload.toString(),
                ),
            )
        }
    }

    suspend fun updateLogClipCaption(
        clipId: Long,
        caption: String,
    ) = withContext(Dispatchers.IO) {
        val clip = dailyLog.getClip(clipId) ?: return@withContext
        val value = caption.trim().take(500).takeIf { it.isNotEmpty() }
        dailyLog.updateClip(clip.copy(caption = value))
        val log = dailyLog.getLog(clip.logId) ?: return@withContext
        val uuid = clip.clientUuid ?: return@withContext
        queueOrPublishOperation(
            log,
            "clip",
            uuid,
            "caption",
            JSONObject().put("clipUuid", uuid).put("caption", value),
        )
    }

    /** 本地留言（功能 6；本机版仅自己留言，authorName 用本地昵称） */
    suspend fun addLogComment(
        clipId: Long,
        text: String,
    ) = withContext(Dispatchers.IO) {
        val t = text.trim()
        if (t.isEmpty()) return@withContext
        val clip = dailyLog.getClip(clipId) ?: return@withContext
        val log = dailyLog.getLog(clip.logId) ?: return@withContext
        val commentUuid = UUID.randomUUID().toString()
        val authorId = if (log.isPersonal) null else runCatching { DeviceIdentity.getOrCreate().deviceId }.getOrNull()
        val authorName = dailyLogSettings.getNickname()
        dailyLog.insertComment(
            LogCommentEntity(
                clipId = clipId,
                authorName = authorName,
                text = t,
                clientUuid = commentUuid,
                authorId = authorId,
            ),
        )
        val clipUuid = clip.clientUuid ?: return@withContext
        queueOrPublishOperation(
            log,
            "comment",
            commentUuid,
            "upsert",
            JSONObject()
                .put("commentUuid", commentUuid)
                .put("clipUuid", clipUuid)
                .put("authorId", authorId)
                .put("authorName", authorName)
                .put("text", t)
                .put("createdAt", System.currentTimeMillis()),
        )
    }

    /** 删除素材：文件 + 留言 + 记录一起清，并让当天已有的合成结果失效（下次进详情页会提示重合成） */
    suspend fun deleteLogClip(id: Long) =
        withContext(Dispatchers.IO) {
            val clip = dailyLog.getClip(id) ?: return@withContext
            val log = dailyLog.getLog(clip.logId)
            if (log?.isPersonal == false) {
                val uuid = clip.clientUuid ?: return@withContext
                if (clip.sourceKept && clip.filePath.isNotBlank()) {
                    runCatching { DailyLogStorage.resolve(context, clip.filePath).delete() }
                }
                dailyLog.updateClip(
                    clip.copy(filePath = "", sourceKept = false, transferState = "deleted", localThumbPath = null),
                )
                queueOrPublishOperation(log, "clip", uuid, "delete", JSONObject().put("clipUuid", uuid))
                deleteDailyCompilation(clip.logId, clip.dayEpoch)
                return@withContext
            }
            if (clip.sourceKept) {
                runCatching { DailyLogStorage.resolve(context, clip.filePath).delete() }
            }
            dailyLog.deleteCommentsForClip(id)
            dailyLog.deleteClip(id)
            // 已按保留策略精简的素材不能连带删合成：那时合成是这一天唯一的留存，
            // 删掉等于因为清理一条占位记录而抹掉整天，而且再也无法重新合成。
            if (clip.sourceKept) deleteDailyCompilation(clip.logId, clip.dayEpoch)
        }

    /** 删除某天的合成结果（文件 + 记录）。素材变动后调用，避免用户看到过期的一日日志 */
    suspend fun deleteDailyCompilation(
        logId: Long,
        dayEpoch: Long,
    ) = withContext(Dispatchers.IO) {
        val existing = dailyLog.compilationForDay(logId, dayEpoch)
        if (existing != null) {
            runCatching { DailyLogStorage.resolve(context, existing.filePath).delete() }
            dailyLog.deleteCompilationForDay(logId, dayEpoch)
        }
    }

    /** 某天最后一条素材的时间，用于判断合成结果是否已过期 */
    suspend fun lastClipCreatedAt(
        logId: Long,
        dayEpoch: Long,
    ): Long? = withContext(Dispatchers.IO) { dailyLog.lastClipCreatedAt(logId, dayEpoch) }

    /** 日志占用的磁盘字节数（素材 + 合成 + 缩略图） */
    suspend fun dailyLogOccupiedBytes(): Long =
        withContext(Dispatchers.IO) {
            com.nierduolong.morningbell.dailylog.DailyLogStorage.occupiedBytes(context)
        }

    suspend fun dailyLogStorageBreakdown(): DailyLogStorage.StorageBreakdown =
        withContext(Dispatchers.IO) { DailyLogStorage.storageBreakdown(context) }

    /**
     * 清理不在册的孤儿素材文件（录制中途崩溃、被外部删库等情况留下的半截 mp4），返回删除数。
     * 每次启动跑一次，属于自愈逻辑：不依赖用户手动清理就能避免垃圾无限堆积。
     */
    suspend fun pruneOrphanDailyLogFiles(): Int =
        withContext(Dispatchers.IO) {
            val known = dailyLog.allClipPaths().toSet()
            com.nierduolong.morningbell.dailylog.DailyLogStorage.pruneOrphanFiles(context, known)
        }

    /** 手动清缓存：缩略图可随时按需重建，清掉能立刻换回空间 */
    suspend fun clearDailyLogThumbnailCache() =
        withContext(Dispatchers.IO) {
            com.nierduolong.morningbell.dailylog.DailyLogStorage.clearThumbnailCache(context)
        }

    /** 安全清理只处理可重建文件和通过孤儿保险规则的残留，返回真实释放空间。 */
    suspend fun clearDailyLogSafeCache(): DailyLogStorage.SafeCleanupResult =
        withContext(Dispatchers.IO) {
            val before = DailyLogStorage.occupiedBytes(context)
            val rebuildable = DailyLogStorage.clearRebuildableFiles(context)
            val known = dailyLog.allClipPaths().toSet()
            val orphanCount = DailyLogStorage.pruneOrphanFiles(context, known)
            val after = DailyLogStorage.occupiedBytes(context)
            DailyLogStorage.SafeCleanupResult(
                freedBytes = (before - after).coerceAtLeast(rebuildable.freedBytes),
                deletedFiles = rebuildable.deletedFiles + orphanCount,
            )
        }

    suspend fun clipsForDay(
        logId: Long,
        dayEpoch: Long,
    ): List<LogClipEntity> = withContext(Dispatchers.IO) { dailyLog.clipsForDay(logId, dayEpoch).map { it.resolved() } }

    suspend fun getCompilationForDay(
        logId: Long,
        dayEpoch: Long,
    ): DailyCompilationEntity? = withContext(Dispatchers.IO) { dailyLog.compilationForDay(logId, dayEpoch)?.resolved() }

    /** 一日日志合成完成后落库（功能 4） */
    suspend fun saveDailyCompilation(
        logId: Long,
        dayEpoch: Long,
        filePath: String,
    ): Long =
        withContext(Dispatchers.IO) {
            dailyLog.upsertCompilation(
                DailyCompilationEntity(
                    logId = logId,
                    dayEpoch = dayEpoch,
                    filePath = DailyLogStorage.relativize(context, filePath),
                ),
            )
        }

    // ---------------------------------------------------------------------
    // 保留策略与路径自愈
    // ---------------------------------------------------------------------

    private val retentionDaysState = MutableStateFlow(dailyLogSettings.getRetentionDays())

    /** 原始素材保留天数：0 = 永久保留 */
    val retentionDaysFlow: StateFlow<Int> = retentionDaysState.asStateFlow()

    /** 改保留策略并立刻执行一轮，返回被精简的天数（用于给用户即时反馈） */
    suspend fun setRetentionDays(days: Int): Int =
        withContext(Dispatchers.IO) {
            dailyLogSettings.setRetentionDays(days)
            retentionDaysState.value = dailyLogSettings.getRetentionDays()
            // 不立刻清一轮的话，用户改完设置看不到占用下降，会以为设置没生效
            runCatching { applyRetentionPolicy() }.getOrDefault(0)
        }

    /**
     * 清理某天的原始素材，只保留一日合成。返回删掉的文件数。
     *
     * 前置条件很硬：当天必须已有**存在且非空**的合成文件，否则直接放弃——
     * 没有合成还删素材等于把这一天彻底抹掉，是不可逆的数据丢失。
     * 记录与留言全部保留，只把 [LogClipEntity.sourceKept] 标成 false。
     */
    suspend fun cleanDaySources(
        logId: Long,
        dayEpoch: Long,
    ): Int =
        withContext(Dispatchers.IO) {
            val compilation = dailyLog.compilationForDay(logId, dayEpoch) ?: return@withContext 0
            val compiled = DailyLogStorage.resolve(context, compilation.filePath)
            if (!compiled.exists() || compiled.length() <= 0) return@withContext 0

            val clips = dailyLog.clipsForDay(logId, dayEpoch).filter { it.sourceKept }
            if (clips.isEmpty()) return@withContext 0
            var cleaned = 0
            clips.forEach { clip ->
                val file = DailyLogStorage.resolve(context, clip.filePath)
                // 合成文件本身绝不能被当成素材删掉
                if (file.absolutePath == compiled.absolutePath) return@forEach
                // 文件本来就不在（早先被外部清掉）也算清理完成，否则这一天会永远卡在待清理
                val gone = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
                if (gone) cleaned++
            }
            dailyLog.markDaySourceCleaned(logId, dayEpoch)
            DailyLogStorage.deleteDayDirIfEmpty(context, logId, dayEpoch)
            cleaned
        }

    /** 按当前保留天数清理所有到期日期，返回被清理的天数 */
    suspend fun applyRetentionPolicy(): Int =
        withContext(Dispatchers.IO) {
            val days = dailyLogSettings.getRetentionDays()
            val cutoff = RetentionPolicy.cutoffDay(LocalDate.now().toEpochDay(), days) ?: return@withContext 0
            val logId = personalLogIdState.value ?: dailyLog.getPersonalLog()?.id ?: return@withContext 0
            dailyLog.daysEligibleForCleanup(logId, cutoff).count { day ->
                cleanDaySources(logId, day) > 0
            }
        }

    /**
     * 把历史记录里的绝对路径改写成相对路径，顺带修复指向不存在文件的记录。
     *
     * 早期版本存的是绝对路径，一旦外置私有目录挂载点变化（换机、恢复备份、分区调整）
     * 全部记录会集体变成死链。这里做一次性归一化：能压成相对路径的压掉，压不掉但文件
     * 确实还在目录里的按文件名重定位，两者都失败的**保持原样**——宁可留一条暂时打不开
     * 的记录，也不能凭猜测改写用户数据。
     */
    suspend fun normalizeDailyLogPaths(): Int =
        withContext(Dispatchers.IO) {
            val index = DailyLogStorage.fileIndexByName(context)
            var fixed = 0
            var unresolved = 0

            dailyLog.allClips().forEach { clip ->
                when (val outcome = normalizePath(clip.filePath, index)) {
                    is PathFix.Rewrite -> {
                        dailyLog.updateClipPath(clip.id, outcome.path)
                        fixed++
                    }

                    PathFix.Unresolved -> unresolved++
                    PathFix.AlreadyGood -> Unit
                }
            }
            dailyLog.allCompilations().forEach { compilation ->
                when (val outcome = normalizePath(compilation.filePath, index)) {
                    is PathFix.Rewrite -> {
                        dailyLog.updateCompilationPath(compilation.id, outcome.path)
                        fixed++
                    }

                    // 合成结果丢了不算问题：素材还在的话下次会重新合成
                    PathFix.Unresolved -> Unit
                    PathFix.AlreadyGood -> Unit
                }
            }

            // 还有素材没落实到具体文件时不打标记，下次启动再试一次。
            // 首启时外置私有目录偶尔尚未挂载，这种瞬时失败不该被永久固化。
            if (unresolved == 0) dailyLogSettings.setPathsNormalized(true)
            fixed
        }

    private sealed interface PathFix {
        /** 已经是可用的相对路径，不需要改 */
        data object AlreadyGood : PathFix

        /** 文件找不到，保持原样等下次再试 */
        data object Unresolved : PathFix

        data class Rewrite(val path: String) : PathFix
    }

    private fun normalizePath(
        stored: String,
        index: Map<String, String>,
    ): PathFix {
        val relative = DailyLogStorage.relativize(context, stored)
        if (DailyLogStorage.resolve(context, relative).exists()) {
            return if (relative == stored) PathFix.AlreadyGood else PathFix.Rewrite(relative)
        }
        val relocated = DailyLogStorage.relocate(context, stored, index)
        return when {
            relocated == null -> PathFix.Unresolved
            relocated == stored -> PathFix.AlreadyGood
            else -> PathFix.Rewrite(relocated)
        }
    }

    suspend fun hasNormalizedDailyLogPaths(): Boolean = withContext(Dispatchers.IO) { dailyLogSettings.hasNormalizedPaths() }

    // ---------------------------------------------------------------------
    // 拍摄提醒周期与本地昵称（功能 2 / 7）
    // ---------------------------------------------------------------------

    private val reminderCadenceState = MutableStateFlow(dailyLogSettings.getReminderCadence())
    val reminderCadenceFlow: StateFlow<DailyLogSettings.ReminderCadence> = reminderCadenceState.asStateFlow()

    /** 活跃时段 [起始小时, 结束小时)，提醒只在这个区间内响 */
    private val reminderWindowState =
        MutableStateFlow(dailyLogSettings.getActiveStartHour() to dailyLogSettings.getActiveEndHour())
    val reminderWindowFlow: StateFlow<Pair<Int, Int>> = reminderWindowState.asStateFlow()

    suspend fun setReminderCadence(cadence: DailyLogSettings.ReminderCadence) =
        withContext(Dispatchers.IO) {
            dailyLogSettings.setReminderCadence(cadence)
            reminderCadenceState.value = cadence
            com.nierduolong.morningbell.dailylog.ReminderScheduler.apply(context, cadence)
        }

    suspend fun setReminderWindow(
        startHour: Int,
        endHour: Int,
    ) = withContext(Dispatchers.IO) {
        dailyLogSettings.setActiveWindow(startHour, endHour)
        reminderWindowState.value = dailyLogSettings.getActiveStartHour() to dailyLogSettings.getActiveEndHour()
        // 时段变了要按新窗口重排，否则下一次仍会落在旧时段里
        com.nierduolong.morningbell.dailylog.ReminderScheduler.apply(context, reminderCadenceState.value)
    }

    private val nicknameState = MutableStateFlow(dailyLogSettings.getNickname())
    val nicknameFlow: StateFlow<String> = nicknameState.asStateFlow()
    private val onboardedState = MutableStateFlow(dailyLogSettings.hasOnboarded())
    val hasOnboardedFlow: StateFlow<Boolean> = onboardedState.asStateFlow()

    suspend fun setNickname(name: String) =
        withContext(Dispatchers.IO) {
            val t = name.trim().ifEmpty { "我" }
            dailyLogSettings.setNickname(t)
            dailyLogSettings.setOnboarded(true)
            nicknameState.value = t
            onboardedState.value = true
        }
}
