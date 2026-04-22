package com.nierduolong.morningbell.core

import com.nierduolong.morningbell.data.db.BirthdayEntity
import com.nlf.calendar.Lunar
import java.time.LocalDate
import java.time.YearMonth

/** 将生日（公历或农历月日）换算为「参考年」中的公历日期，用于提醒触发日 */
object LunarBirthdayCalendar {
    fun solarEventDateThisYear(
        birthday: BirthdayEntity,
        reference: LocalDate,
    ): LocalDate {
        if (!birthday.isLunar) {
            val ym = YearMonth.of(reference.year, birthday.month)
            val safeDay = birthday.day.coerceAtMost(ym.lengthOfMonth())
            return LocalDate.of(reference.year, birthday.month, safeDay)
        }
        val y = reference.year
        // 农历年与日历年不完全对齐，在相邻农历年中查找落在目标公历年的那一次
        for (lunarYear in y - 1..y + 1) {
            val lunar = Lunar.fromYmd(lunarYear, birthday.month, birthday.day)
            val solar = lunar.solar
            if (solar.year == y) {
                return LocalDate.of(solar.year, solar.month, solar.day)
            }
        }
        val fallback = Lunar.fromYmd(y, birthday.month, birthday.day).solar
        return LocalDate.of(fallback.year, fallback.month, fallback.day)
    }
}
