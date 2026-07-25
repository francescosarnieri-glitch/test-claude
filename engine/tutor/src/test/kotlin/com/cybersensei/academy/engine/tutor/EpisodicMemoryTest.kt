package com.cybersensei.academy.engine.tutor

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodicMemoryTest {

    private val now: Instant = Instant.parse("2026-07-25T10:00:00Z")

    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    private fun event(kind: StudyEvent.Kind, label: String, days: Long) =
        StudyEvent(kind, label, daysAgo(days))

    @Test
    fun `an abandoned lesson is remembered until it is finished`() {
        val memory = EpisodicMemory(
            listOf(
                event(StudyEvent.Kind.LESSON_ABANDONED, "Il DNS", 3),
                event(StudyEvent.Kind.SESSION, "sessione", 3),
            ),
        )
        assertEquals("Il DNS", memory.unfinishedLesson())

        memory.record(event(StudyEvent.Kind.LESSON_COMPLETED, "Il DNS", 0))
        assertNull("Una volta finita non deve più essere citata", memory.unfinishedLesson())
    }

    @Test
    fun `the most recent unfinished lesson wins`() {
        val memory = EpisodicMemory(
            listOf(
                event(StudyEvent.Kind.LESSON_ABANDONED, "Il DNS", 10),
                event(StudyEvent.Kind.LESSON_ABANDONED, "Il phishing", 2),
            ),
        )
        assertEquals("Il phishing", memory.unfinishedLesson())
    }

    @Test
    fun `a mistake made twice becomes something the professor can bring up`() {
        val memory = EpisodicMemory(
            listOf(
                event(StudyEvent.Kind.MISCONCEPTION_HIT, "hash reversibile", 8),
                event(StudyEvent.Kind.MISCONCEPTION_HIT, "hash reversibile", 4),
                event(StudyEvent.Kind.MISCONCEPTION_HIT, "vpn anonima", 3),
            ),
        )
        assertEquals("hash reversibile" to 2, memory.recurringMisconception(now))
    }

    @Test
    fun `a mistake made once is not thrown back at the student`() {
        val memory = EpisodicMemory(
            listOf(event(StudyEvent.Kind.MISCONCEPTION_HIT, "vpn anonima", 3)),
        )
        assertNull(memory.recurringMisconception(now))
    }

    @Test
    fun `old mistakes fall out of the window instead of haunting forever`() {
        val memory = EpisodicMemory(
            listOf(
                event(StudyEvent.Kind.MISCONCEPTION_HIT, "hash reversibile", 200),
                event(StudyEvent.Kind.MISCONCEPTION_HIT, "hash reversibile", 190),
            ),
        )
        assertNull(memory.recurringMisconception(now))
    }

    @Test
    fun `days since the last session drive the welcome back line`() {
        val memory = EpisodicMemory(
            listOf(
                event(StudyEvent.Kind.SESSION, "sessione", 40),
                event(StudyEvent.Kind.SESSION, "sessione", 12),
            ),
        )
        assertEquals(12, memory.daysSinceLastSession(now))
    }

    @Test
    fun `a brand new student has no history and no crash`() {
        val memory = EpisodicMemory()
        assertNull(memory.unfinishedLesson())
        assertNull(memory.recurringMisconception(now))
        assertEquals(0, memory.daysSinceLastSession(now))
    }

    @Test
    fun `the diary keeps a bounded size, dropping the oldest pages`() {
        val memory = EpisodicMemory(capacity = 50)
        repeat(200) { i ->
            memory.record(StudyEvent(StudyEvent.Kind.SESSION, "sessione $i", now))
        }
        assertEquals(50, memory.size)
        assertEquals("sessione 199", memory.all().last().label)
    }
}
