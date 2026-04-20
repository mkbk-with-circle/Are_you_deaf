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
interface WakeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WakeDayEntity)

    /** 多取一些行，便于「本月早起」图表跨月边界仍有数据 */
    @Query("SELECT * FROM wake_days ORDER BY dayEpoch DESC LIMIT 400")
    fun observeRecent(): Flow<List<WakeDayEntity>>

    @Query("SELECT * FROM wake_days WHERE dayEpoch = :day")
    suspend fun getForDay(day: Long): WakeDayEntity?

    @Query("DELETE FROM wake_days WHERE dayEpoch = :dayEpoch")
    suspend fun deleteForDay(dayEpoch: Long)
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

    @Query("SELECT * FROM birthday_reminders WHERE birthdayId = :id")
    suspend fun remindersFor(id: Long): List<BirthdayReminderEntity>

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
interface MicroTaskDao {
    @Query("SELECT * FROM micro_task_days WHERE dayEpoch = :dayEpoch")
    suspend fun getDay(dayEpoch: Long): MicroTaskDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(row: MicroTaskDayEntity)

    @Query("SELECT * FROM micro_task_custom ORDER BY id DESC")
    fun observeCustom(): Flow<List<MicroTaskCustomEntity>>

    @Query("SELECT * FROM micro_task_custom ORDER BY id DESC")
    suspend fun allCustom(): List<MicroTaskCustomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustom(row: MicroTaskCustomEntity): Long

    @Query("DELETE FROM micro_task_custom WHERE id = :id")
    suspend fun deleteCustom(id: Long)
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
