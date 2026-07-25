package com.cybersensei.academy.core.model

import java.time.LocalDate
import java.time.MonthDay

/**
 * The student's star sign. It carries zero pedagogical weight — it exists purely so that
 * Prof. Hackstein White can drop the occasional personal remark and feel like someone who
 * actually knows the student. Date ranges follow the common Western (tropical) convention.
 */
enum class ZodiacSign(
    val italianName: String,
    val symbol: String,
    private val start: MonthDay,
    private val end: MonthDay,
) {
    CAPRICORN("Capricorno", "♑", MonthDay.of(12, 22), MonthDay.of(1, 19)),
    AQUARIUS("Acquario", "♒", MonthDay.of(1, 20), MonthDay.of(2, 18)),
    PISCES("Pesci", "♓", MonthDay.of(2, 19), MonthDay.of(3, 20)),
    ARIES("Ariete", "♈", MonthDay.of(3, 21), MonthDay.of(4, 19)),
    TAURUS("Toro", "♉", MonthDay.of(4, 20), MonthDay.of(5, 20)),
    GEMINI("Gemelli", "♊", MonthDay.of(5, 21), MonthDay.of(6, 20)),
    CANCER("Cancro", "♋", MonthDay.of(6, 21), MonthDay.of(7, 22)),
    LEO("Leone", "♌", MonthDay.of(7, 23), MonthDay.of(8, 22)),
    VIRGO("Vergine", "♍", MonthDay.of(8, 23), MonthDay.of(9, 22)),
    LIBRA("Bilancia", "♎", MonthDay.of(9, 23), MonthDay.of(10, 22)),
    SCORPIO("Scorpione", "♏", MonthDay.of(10, 23), MonthDay.of(11, 21)),
    SAGITTARIUS("Sagittario", "♐", MonthDay.of(11, 22), MonthDay.of(12, 21));

    private fun contains(monthDay: MonthDay): Boolean =
        if (start <= end) {
            monthDay >= start && monthDay <= end
        } else {
            // Capricorn is the only sign that wraps around New Year's Eve.
            monthDay >= start || monthDay <= end
        }

    companion object {
        fun of(date: LocalDate): ZodiacSign = of(MonthDay.from(date))

        fun of(monthDay: MonthDay): ZodiacSign =
            entries.first { it.contains(monthDay) }
    }
}
