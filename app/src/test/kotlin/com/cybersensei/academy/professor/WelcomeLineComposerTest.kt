package com.cybersensei.academy.professor

import com.cybersensei.academy.core.common.FixedTimeProvider
import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeLineComposerTest {

    private val clock = FixedTimeProvider(Instant.parse("2026-07-25T10:00:00Z"))
    private val composer = WelcomeLineComposer(clock)

    @Test
    fun `the line is personalised with the student name`() {
        clock.set(LocalDateTime.of(2026, 7, 25, 10, 0))
        assertTrue(composer.compose("Francesco", openingCount = 1).contains("Francesco"))
    }

    @Test
    fun `no slot placeholder ever reaches the screen`() {
        repeat(40) { opening ->
            clock.set(LocalDateTime.of(2026, 7, 25, opening % 24, 0))
            val line = composer.compose("Francesco", openingCount = opening)
            assertFalse("Slot non sostituito in: $line", line.contains("{"))
            assertFalse("Slot non sostituito in: $line", line.contains("}"))
        }
    }

    @Test
    fun `the professor does not repeat himself two openings in a row`() {
        clock.set(LocalDateTime.of(2026, 7, 25, 10, 0))
        var previous = composer.compose("Francesco", openingCount = 0)
        repeat(20) { opening ->
            val line = composer.compose("Francesco", openingCount = opening + 1)
            assertFalse("Battuta ripetuta di fila: $line", line == previous)
            previous = line
        }
    }

    @Test
    fun `a student who has not introduced himself gets the first-meeting lines`() {
        clock.set(LocalDateTime.of(2026, 7, 25, 10, 0))
        val line = composer.compose(studentName = null, openingCount = 0)
        assertTrue(line.contains("Hackstein White"))
    }

    @Test
    fun `studying at four in the morning gets a different kind of welcome`() {
        clock.set(LocalDateTime.of(2026, 7, 25, 4, 0))
        val line = composer.compose("Francesco", openingCount = 0)
        assertTrue(
            "La battuta notturna dovrebbe parlare dell'ora: $line",
            line.contains("notte") || line.contains("ore piccole") || line.contains("dormire"),
        )
    }

    @Test
    fun `the same context always produces the same line`() {
        clock.set(LocalDateTime.of(2026, 7, 25, 10, 0))
        val first = WelcomeLineComposer(clock).compose("Francesco", openingCount = 7)
        val second = WelcomeLineComposer(clock).compose("Francesco", openingCount = 7)
        assertEquals(first, second)
    }
}
