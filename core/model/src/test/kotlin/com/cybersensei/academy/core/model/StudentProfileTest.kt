package com.cybersensei.academy.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentProfileTest {

    private fun profile(birthDate: LocalDate?) = StudentProfile(
        name = "Francesco",
        birthDate = birthDate,
        enrolledOn = LocalDate.of(2026, 1, 10),
    )

    @Test
    fun `zodiac sign is derived from the birth date`() {
        assertEquals(ZodiacSign.SCORPIO, profile(LocalDate.of(1990, 11, 3)).zodiacSign)
    }

    @Test
    fun `a student who skipped the birth date keeps working`() {
        val anonymous = profile(null)
        assertNull(anonymous.zodiacSign)
        assertNull(anonymous.ageOn(LocalDate.of(2026, 7, 25)))
        assertFalse(anonymous.isBirthday(LocalDate.of(2026, 7, 25)))
    }

    @Test
    fun `age accounts for a birthday that has not happened yet this year`() {
        val student = profile(LocalDate.of(1990, 11, 3))
        assertEquals(35, student.ageOn(LocalDate.of(2026, 7, 25)))
        assertEquals(36, student.ageOn(LocalDate.of(2026, 11, 3)))
    }

    @Test
    fun `birthday is recognised regardless of the year`() {
        val student = profile(LocalDate.of(1990, 11, 3))
        assertTrue(student.isBirthday(LocalDate.of(2026, 11, 3)))
        assertFalse(student.isBirthday(LocalDate.of(2026, 11, 4)))
    }

    @Test
    fun `nickname defaults to the name`() {
        assertEquals("Francesco", profile(null).nickname)
    }

    @Test
    fun `days enrolled counts from enrolment day`() {
        assertEquals(21, profile(null).daysEnrolled(LocalDate.of(2026, 1, 31)))
    }
}
