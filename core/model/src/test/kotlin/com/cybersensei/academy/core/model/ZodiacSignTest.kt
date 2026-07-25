package com.cybersensei.academy.core.model

import java.time.LocalDate
import java.time.MonthDay
import org.junit.Assert.assertEquals
import org.junit.Test

class ZodiacSignTest {

    @Test
    fun `every day of the year maps to exactly one sign`() {
        // 2024 is a leap year, so this also covers February 29th.
        var date = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 12, 31)
        while (!date.isAfter(end)) {
            val matches = ZodiacSign.entries.count { sign ->
                runCatching { ZodiacSign.of(date) == sign }.getOrDefault(false)
            }
            assertEquals("Nessun segno (o troppi) per $date", 1, matches)
            date = date.plusDays(1)
        }
    }

    @Test
    fun `boundary days belong to the expected sign`() {
        assertEquals(ZodiacSign.CAPRICORN, ZodiacSign.of(MonthDay.of(12, 22)))
        assertEquals(ZodiacSign.CAPRICORN, ZodiacSign.of(MonthDay.of(1, 19)))
        assertEquals(ZodiacSign.AQUARIUS, ZodiacSign.of(MonthDay.of(1, 20)))
        assertEquals(ZodiacSign.SAGITTARIUS, ZodiacSign.of(MonthDay.of(12, 21)))
        assertEquals(ZodiacSign.PISCES, ZodiacSign.of(MonthDay.of(2, 29)))
    }

    @Test
    fun `new year's eve and new year's day are both capricorn`() {
        assertEquals(ZodiacSign.CAPRICORN, ZodiacSign.of(LocalDate.of(2025, 12, 31)))
        assertEquals(ZodiacSign.CAPRICORN, ZodiacSign.of(LocalDate.of(2026, 1, 1)))
    }
}
