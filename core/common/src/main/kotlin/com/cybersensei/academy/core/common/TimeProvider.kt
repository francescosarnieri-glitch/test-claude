package com.cybersensei.academy.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Every "what time is it?" question in the app goes through here.
 *
 * The professor reasons a lot about time — greetings, streaks, absences, late-night study
 * sessions, spaced repetition — so time has to be injectable, otherwise none of that logic
 * can be tested.
 */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId

    fun dateTime(): LocalDateTime = LocalDateTime.ofInstant(now(), zone())
    fun today(): LocalDate = dateTime().toLocalDate()
    fun time(): LocalTime = dateTime().toLocalTime()
    fun dayPart(): DayPart = DayPart.of(time())
}

/** Used by the professor to pick a greeting — and to notice unhealthy study hours. */
enum class DayPart(val italianGreeting: String) {
    EARLY_MORNING("Buongiorno presto"),
    MORNING("Buongiorno"),
    AFTERNOON("Buon pomeriggio"),
    EVENING("Buonasera"),
    NIGHT("Buonanotte"),
    DEEP_NIGHT("Ma non dormi?");

    companion object {
        fun of(time: LocalTime): DayPart = when (time.hour) {
            in 5..7 -> EARLY_MORNING
            in 8..12 -> MORNING
            in 13..17 -> AFTERNOON
            in 18..21 -> EVENING
            in 22..23 -> NIGHT
            else -> DEEP_NIGHT
        }
    }
}

class SystemTimeProvider(private val zoneId: ZoneId = ZoneId.systemDefault()) : TimeProvider {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = zoneId
}

/** Test double: time stands still until you move it. */
class FixedTimeProvider(
    private var instant: Instant,
    private val zoneId: ZoneId = ZoneId.of("Europe/Rome"),
) : TimeProvider {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId

    fun set(dateTime: LocalDateTime) {
        instant = dateTime.atZone(zoneId).toInstant()
    }
}
