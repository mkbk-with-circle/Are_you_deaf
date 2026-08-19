package com.nierduolong.morningbell.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun observeAlarms(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun enabledAlarms(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: AlarmEntity): Long

    @Update
    suspend fun update(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MoodDao {
    @Query("SELECT * FROM moods ORDER BY dayEpoch DESC LIMIT 120")
    fun observeRecent(): Flow<List<MoodEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mood: MoodEntity): Long

    @Query("SELECT * FROM moods WHERE dayEpoch = :day")
    suspend fun getForDay(day: Long): MoodEntity?
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE completed = 0 ORDER BY deadlineEpochDay ASC")
    suspend fun activeGoals(): List<GoalEntity>

    @Query("SELECT * FROM goals ORDER BY completed ASC, id DESC")
    fun observeGoals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: Long): GoalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BirthdayDao {
    @Query("SELECT * FROM birthdays ORDER BY month, day")
    fun observeBirthdays(): Flow<List<BirthdayEntity>>

    @Query("SELECT * FROM birthdays ORDER BY month, day")
    suspend fun allBirthdays(): List<BirthdayEntity>

    @Query("SELECT * FROM birthdays WHERE id = :id")
    suspend fun getBirthdayById(id: Long): BirthdayEntity?

    @Query("SELECT * FROM birthday_reminders WHERE id = :id")
    suspend fun getReminderById(id: Long): BirthdayReminderEntity?

    @Query("SELECT * FROM birthday_reminders WHERE birthdayId = :id")
    suspend fun remindersFor(id: Long): List<BirthdayReminderEntity>

    @Query("SELECT * FROM birthday_reminders WHERE birthdayId = :id ORDER BY id ASC")
    fun observeRemindersForBirthday(id: Long): Flow<List<BirthdayReminderEntity>>

    @Query("SELECT * FROM birthday_reminders")
    suspend fun allReminders(): List<BirthdayReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBirthday(b: BirthdayEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReminder(r: BirthdayReminderEntity): Long

    @Query("DELETE FROM birthdays WHERE id = :id")
    suspend fun deleteBirthday(id: Long)

    @Query("DELETE FROM birthday_reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("DELETE FROM birthday_reminders WHERE birthdayId = :birthdayId")
    suspend fun deleteRemindersForBirthday(birthdayId: Long)
}

@Dao
interface DailyLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLog(log: DailyLogEntity): Long

    @Query("SELECT * FROM daily_logs WHERE isPersonal = 1 LIMIT 1")
    suspend fun getPersonalLog(): DailyLogEntity?

    @Query("SELECT * FROM daily_logs ORDER BY id ASC")
    fun observeLogs(): Flow<List<DailyLogEntity>>

    @Query("SELECT * FROM daily_logs WHERE id = :id LIMIT 1")
    suspend fun getLog(id: Long): DailyLogEntity?

    @Query("SELECT * FROM daily_logs WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getLogByRemoteId(remoteId: String): DailyLogEntity?

    @Query("SELECT * FROM daily_logs WHERE role = 'member' AND remoteId IS NOT NULL AND inviteCode IS NOT NULL ORDER BY lastSyncedAt DESC, id DESC")
    suspend fun memberLogs(): List<DailyLogEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertClip(clip: LogClipEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClip(clip: LogClipEntity): Long

    @Query("SELECT * FROM log_clips WHERE logId = :logId AND transferState != 'deleted' ORDER BY dayEpoch DESC, id DESC")
    fun observeClips(logId: Long): Flow<List<LogClipEntity>>

    @Query("SELECT * FROM log_clips WHERE logId = :logId AND dayEpoch = :dayEpoch AND transferState != 'deleted' ORDER BY id ASC")
    suspend fun clipsForDay(
        logId: Long,
        dayEpoch: Long,
    ): List<LogClipEntity>

    @Query("SELECT * FROM log_clips WHERE logId = :logId AND dayEpoch = :dayEpoch AND transferState != 'deleted' ORDER BY createdAt ASC, id ASC")
    fun observeClipsForDay(
        logId: Long,
        dayEpoch: Long,
    ): Flow<List<LogClipEntity>>

    /** 归档页按日汇总（含封面路径），避免把全部 clip 拉进内存再分组 */
    @Query(
        "SELECT c.dayEpoch AS dayEpoch, COUNT(*) AS clipCount, SUM(c.durationMs) AS totalDurationMs, " +
            "MAX(c.createdAt) AS lastClipAt, SUM(c.sourceKept) AS keptCount, " +
            "(SELECT c2.filePath FROM log_clips c2 WHERE c2.logId = c.logId AND c2.dayEpoch = c.dayEpoch " +
            "AND c2.sourceKept = 1 AND c2.transferState != 'deleted' ORDER BY c2.createdAt ASC, c2.id ASC LIMIT 1) AS coverPath, " +
            "(SELECT c3.localThumbPath FROM log_clips c3 WHERE c3.logId = c.logId AND c3.dayEpoch = c.dayEpoch " +
            "AND c3.localThumbPath IS NOT NULL AND c3.transferState != 'deleted' ORDER BY c3.createdAt ASC, c3.id ASC LIMIT 1) AS coverThumbPath " +
            "FROM log_clips c WHERE c.logId = :logId AND c.transferState != 'deleted' " +
            "GROUP BY c.dayEpoch ORDER BY c.dayEpoch DESC",
    )
    fun observeDaySummaries(logId: Long): Flow<List<DayLogSummary>>

    @Query("SELECT MAX(createdAt) FROM log_clips WHERE logId = :logId AND dayEpoch = :dayEpoch AND transferState != 'deleted'")
    suspend fun lastClipCreatedAt(
        logId: Long,
        dayEpoch: Long,
    ): Long?

    /** 孤儿文件清理用：数据库里在册的全部素材路径 */
    @Query("SELECT filePath FROM log_clips")
    suspend fun allClipPaths(): List<String>

    /** 路径归一化用：需要逐条改写 filePath，所以要整行 */
    @Query("SELECT * FROM log_clips")
    suspend fun allClips(): List<LogClipEntity>

    @Query("SELECT * FROM daily_compilations")
    suspend fun allCompilations(): List<DailyCompilationEntity>

    @Query("UPDATE log_clips SET filePath = :filePath WHERE id = :id")
    suspend fun updateClipPath(
        id: Long,
        filePath: String,
    )

    @Query("UPDATE daily_compilations SET filePath = :filePath WHERE id = :id")
    suspend fun updateCompilationPath(
        id: Long,
        filePath: String,
    )

    /** 保留策略执行后只改标记，时间/说明/留言全部留下 */
    @Query("UPDATE log_clips SET sourceKept = 0 WHERE logId = :logId AND dayEpoch = :dayEpoch")
    suspend fun markDaySourceCleaned(
        logId: Long,
        dayEpoch: Long,
    )

    /**
     * 保留策略的候选日期：已经有合成结果、且仍有未清理原始素材、且不晚于 [maxDayEpoch]。
     * 用 INNER JOIN 保证「没合成过的日期绝不会被清」。
     */
    @Query(
        "SELECT DISTINCT c.dayEpoch FROM log_clips c " +
            "INNER JOIN daily_compilations p ON p.logId = c.logId AND p.dayEpoch = c.dayEpoch " +
            "WHERE c.logId = :logId AND c.sourceKept = 1 AND c.dayEpoch <= :maxDayEpoch " +
            "ORDER BY c.dayEpoch ASC",
    )
    suspend fun daysEligibleForCleanup(
        logId: Long,
        maxDayEpoch: Long,
    ): List<Long>

    @Query("SELECT * FROM log_clips WHERE id = :id")
    suspend fun getClip(id: Long): LogClipEntity?

    @Query("SELECT * FROM log_clips WHERE logId = :logId AND clientUuid = :clientUuid LIMIT 1")
    suspend fun getClipByClientUuid(
        logId: Long,
        clientUuid: String,
    ): LogClipEntity?

    @Update
    suspend fun updateClip(clip: LogClipEntity)

    @Query("UPDATE log_clips SET localThumbPath = :path WHERE logId = :logId AND clientUuid = :clientUuid")
    suspend fun updateClipThumbnail(
        logId: Long,
        clientUuid: String,
        path: String,
    ): Int

    @Query("UPDATE log_clips SET contentSha256 = :sha256 WHERE id = :id")
    suspend fun updateClipSha256(
        id: Long,
        sha256: String,
    )

    @Query("DELETE FROM log_clips WHERE id = :id")
    suspend fun deleteClip(id: Long)

    @Query("DELETE FROM log_comments WHERE clipId = :clipId")
    suspend fun deleteCommentsForClip(clipId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompilation(c: DailyCompilationEntity): Long

    @Query("DELETE FROM daily_compilations WHERE logId = :logId AND dayEpoch = :dayEpoch")
    suspend fun deleteCompilationForDay(
        logId: Long,
        dayEpoch: Long,
    )

    @Query("SELECT * FROM daily_compilations WHERE logId = :logId AND dayEpoch = :dayEpoch LIMIT 1")
    suspend fun compilationForDay(
        logId: Long,
        dayEpoch: Long,
    ): DailyCompilationEntity?

    @Query("SELECT * FROM daily_compilations WHERE logId = :logId ORDER BY dayEpoch DESC")
    fun observeCompilations(logId: Long): Flow<List<DailyCompilationEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertComment(c: LogCommentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComment(c: LogCommentEntity): Long

    @Query("SELECT * FROM log_comments WHERE clientUuid = :clientUuid LIMIT 1")
    suspend fun getCommentByClientUuid(clientUuid: String): LogCommentEntity?

    @Query("SELECT * FROM log_comments WHERE clipId = :clipId AND deleted = 0 ORDER BY id ASC")
    fun observeComments(clipId: Long): Flow<List<LogCommentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: LogMemberEntity): Long

    @Query("SELECT * FROM log_members WHERE logId = :logId ORDER BY joinedAt ASC")
    fun observeMembers(logId: Long): Flow<List<LogMemberEntity>>

    @Query("SELECT * FROM log_members WHERE logId = :logId ORDER BY joinedAt ASC")
    suspend fun members(logId: Long): List<LogMemberEntity>

    @Query("SELECT * FROM log_members WHERE logId = :logId AND authorId = :authorId LIMIT 1")
    suspend fun getMember(
        logId: Long,
        authorId: String,
    ): LogMemberEntity?

    @Query("UPDATE daily_logs SET memberCount = :count WHERE id = :logId")
    suspend fun updateMemberCount(
        logId: Long,
        count: Int,
    )

    @Query("UPDATE log_members SET sourceAddress = :address, sourcePort = :port, lastSeenAt = :seenAt WHERE logId = :logId AND authorId = :authorId")
    suspend fun updateMemberSource(
        logId: Long,
        authorId: String,
        address: String,
        port: Int,
        seenAt: Long,
    ): Int

    @Query("UPDATE log_members SET lastSeenAt = :seenAt WHERE logId = :logId AND authorId = :authorId")
    suspend fun touchMember(
        logId: Long,
        authorId: String,
        seenAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueOutbox(item: SyncOutboxEntity): Long

    @Query(
        "SELECT * FROM sync_outbox WHERE logId = :logId AND nextAttemptAt <= :now " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun pendingOutbox(
        logId: Long,
        now: Long,
        limit: Int,
    ): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE operationId = :operationId")
    suspend fun acknowledgeOutbox(operationId: String)

    @Query("UPDATE sync_outbox SET attempts = :attempts, nextAttemptAt = :nextAttemptAt WHERE operationId = :operationId")
    suspend fun postponeOutbox(
        operationId: String,
        attempts: Int,
        nextAttemptAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSyncEvent(event: SyncEventEntity): Long

    @Query("SELECT * FROM sync_events WHERE logId = :logId AND id > :after ORDER BY id ASC LIMIT :limit")
    suspend fun syncEventsAfter(
        logId: Long,
        after: Long,
        limit: Int,
    ): List<SyncEventEntity>

    @Query("SELECT * FROM sync_events WHERE logId = :logId AND operationId = :operationId LIMIT 1")
    suspend fun getSyncEventByOperationId(
        logId: Long,
        operationId: String,
    ): SyncEventEntity?

    @Query("UPDATE daily_logs SET lastSyncCursor = :cursor, lastSyncedAt = :syncedAt WHERE id = :logId")
    suspend fun updateSyncCursor(
        logId: Long,
        cursor: Long,
        syncedAt: Long,
    )
}

@Dao
interface ReminderTemplateDao {
    @Query("SELECT * FROM reminder_templates ORDER BY id DESC")
    fun observeTemplates(): Flow<List<ReminderTemplateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: ReminderTemplateEntity): Long

    @Query("DELETE FROM reminder_templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChainAlarmDao {
    @Query("SELECT * FROM chain_alarm_groups ORDER BY id DESC")
    fun observeGroups(): Flow<List<ChainAlarmGroupEntity>>

    @Query("SELECT * FROM chain_alarm_groups ORDER BY id DESC")
    suspend fun allGroups(): List<ChainAlarmGroupEntity>

    @Query("SELECT * FROM chain_alarm_groups WHERE enabled = 1")
    suspend fun enabledGroups(): List<ChainAlarmGroupEntity>

    @Query("SELECT * FROM chain_alarm_groups WHERE id = :id")
    suspend fun getGroup(id: Long): ChainAlarmGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(g: ChainAlarmGroupEntity): Long

    @Query("DELETE FROM chain_alarm_groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("SELECT * FROM chain_alarm_steps WHERE id = :id")
    suspend fun getStepById(id: Long): ChainAlarmStepEntity?

    @Query("SELECT * FROM chain_alarm_steps WHERE groupId = :groupId ORDER BY stepIndex ASC")
    suspend fun stepsForGroup(groupId: Long): List<ChainAlarmStepEntity>

    @Query("SELECT * FROM chain_alarm_steps ORDER BY groupId, stepIndex")
    fun observeAllSteps(): Flow<List<ChainAlarmStepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(s: ChainAlarmStepEntity): Long

    @Query("DELETE FROM chain_alarm_steps WHERE groupId = :groupId")
    suspend fun deleteStepsForGroup(groupId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDoneDay(row: ChainDoneDayEntity)

    @Query("SELECT * FROM chain_done_days WHERE groupId = :gid AND dayEpoch = :day")
    suspend fun getDoneForDay(
        gid: Long,
        day: Long,
    ): ChainDoneDayEntity?

    @Query("DELETE FROM chain_done_days WHERE dayEpoch < :beforeEpoch")
    suspend fun pruneDoneBefore(beforeEpoch: Long)

    @Query("DELETE FROM chain_done_days WHERE groupId = :groupId")
    suspend fun deleteDoneForGroup(groupId: Long)
}
