package com.cybersensei.academy.notifications

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the alarm should go off.
 *
 * Small arithmetic, but it is the arithmetic that decides whether a reminder set at eight in
 * the evening rings tonight or in twenty-four hours — and whether it survives the two nights
 * a year that are not twenty-four hours long.
 */
class StudyRemindersTest {

    private val rome: ZoneId = ZoneId.of("Europe/Rome")

    @Test
    fun `an hour still ahead rings today`() {
        val next = StudyReminders.nextOccurrence(
            at = LocalTime.of(20, 0),
            now = LocalDateTime.of(2026, 3, 10, 9, 30),
            zone = rome,
        )

        assertEquals(LocalDateTime.of(2026, 3, 10, 20, 0), next.toLocalDateTime())
    }

    @Test
    fun `an hour already gone rings tomorrow`() {
        val next = StudyReminders.nextOccurrence(
            at = LocalTime.of(8, 0),
            now = LocalDateTime.of(2026, 3, 10, 9, 30),
            zone = rome,
        )

        assertEquals(LocalDateTime.of(2026, 3, 11, 8, 0), next.toLocalDateTime())
    }

    /**
     * The exact minute counts as gone. Rescheduling from inside the receiver happens a few
     * milliseconds after the alarm fired, and "today" there would mean firing again at once.
     */
    @Test
    fun `the very minute it fires counts as gone`() {
        val next = StudyReminders.nextOccurrence(
            at = LocalTime.of(21, 0),
            now = LocalDateTime.of(2026, 3, 10, 21, 0),
            zone = rome,
        )

        assertEquals(LocalDateTime.of(2026, 3, 11, 21, 0), next.toLocalDateTime())
    }

    /**
     * The night the clocks go forward, 02:30 does not exist in Rome. The zone rules push it
     * to a real instant instead of producing a time nobody's phone will ever show.
     */
    @Test
    fun `a reminder set inside the missing hour still lands on a real instant`() {
        val next = StudyReminders.nextOccurrence(
            at = LocalTime.of(2, 30),
            now = LocalDateTime.of(2026, 3, 28, 23, 0),
            zone = rome,
        )

        assertEquals(
            "Deve restare la notte del cambio d'ora",
            java.time.LocalDate.of(2026, 3, 29),
            next.toLocalDate(),
        )
        assertTrue(
            "E deve essere un istante che esiste davvero",
            next.toInstant().isAfter(
                LocalDateTime.of(2026, 3, 28, 23, 0).atZone(rome).toInstant(),
            ),
        )
    }

    /** Twenty-four hours later is not always the same clock time; the same clock time is. */
    @Test
    fun `the day the clocks change keeps the chosen hour`() {
        val next = StudyReminders.nextOccurrence(
            at = LocalTime.of(9, 0),
            now = LocalDateTime.of(2026, 10, 24, 22, 0),
            zone = rome,
        )

        assertEquals(LocalTime.of(9, 0), next.toLocalTime())
        assertEquals(java.time.LocalDate.of(2026, 10, 25), next.toLocalDate())
    }
}
