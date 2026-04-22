package com.nierduolong.morningbell.core

import com.nierduolong.morningbell.data.db.BirthdayEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class LunarBirthdayCalendarTest {
    @Test
    fun solar_unchangedWithinYear() {
        val b =
            BirthdayEntity(
                id = 1L,
                name = "A",
                month = 8,
                day = 15,
                isLunar = false,
            )
        val d = LunarBirthdayCalendar.solarEventDateThisYear(b, LocalDate.of(2026, 3, 1))
        assertEquals(LocalDate.of(2026, 8, 15), d)
    }

    @Test
    fun lunar_firstDay_mapsToSolarInSameGregorianYear() {
        val b =
            BirthdayEntity(
                id = 2L,
                name = "B",
                month = 1,
                day = 1,
                isLunar = true,
            )
        val d = LunarBirthdayCalendar.solarEventDateThisYear(b, LocalDate.of(2025, 6, 1))
        assertEquals(2025, d.year)
    }
}
