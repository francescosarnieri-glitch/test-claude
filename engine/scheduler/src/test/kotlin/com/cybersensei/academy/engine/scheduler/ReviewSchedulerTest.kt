package com.cybersensei.academy.engine.scheduler

import com.cybersensei.academy.engine.mastery.AnswerVerdict
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSchedulerTest {

    private val scheduler = ReviewScheduler()
    private val today: LocalDate = LocalDate.of(2026, 7, 25)

    private fun item(
        skillId: String = "phishing",
        interval: Int = 0,
        repetitions: Int = 0,
        due: LocalDate = today,
        ease: Double = 2.5,
        lucky: Boolean = false,
    ) = ReviewItem(skillId, interval, ease, repetitions, due, lucky)

    @Test
    fun `intervals grow as the student keeps remembering`() {
        var state = item()
        var day = today
        val intervals = mutableListOf<Int>()

        repeat(5) {
            state = scheduler.schedule(state, AnswerVerdict.SOLID, day)
            intervals += state.intervalDays
            day = state.dueOn
        }

        assertEquals(1, intervals[0])
        assertEquals(3, intervals[1])
        assertTrue(
            "Gli intervalli devono crescere: $intervals",
            intervals.zipWithNext().all { (a, b) -> b > a },
        )
    }

    @Test
    fun `a wrong answer sends the skill back to tomorrow`() {
        val mature = item(interval = 40, repetitions = 6)
        val rescheduled = scheduler.schedule(mature, AnswerVerdict.HONEST_MISS, today)

        assertEquals(1, rescheduled.intervalDays)
        assertEquals(0, rescheduled.repetitions)
        assertEquals(today.plusDays(1), rescheduled.dueOn)
    }

    @Test
    fun `a lucky answer is scheduled like a failure, not like a success`() {
        val mature = item(interval = 40, repetitions = 6)
        val rescheduled = scheduler.schedule(mature, AnswerVerdict.SUSPECTED_LUCK, today)

        assertTrue(
            "Una risposta fortunata non deve allontanare il ripasso",
            rescheduled.intervalDays <= 2,
        )
        assertEquals(0, rescheduled.repetitions)
        assertTrue(rescheduled.flaggedForLuck)
    }

    @Test
    fun `a confident mistake makes the material harder, so it comes back more often`() {
        val afterMisconception = scheduler.schedule(item(), AnswerVerdict.ROOTED_MISCONCEPTION, today)
        val afterHonestMiss = scheduler.schedule(item(), AnswerVerdict.HONEST_MISS, today)

        assertTrue(
            "Un errore da convinti deve abbassare di più la facilità",
            afterMisconception.easeFactor < afterHonestMiss.easeFactor,
        )
    }

    @Test
    fun `the ease factor never drops below the floor`() {
        var state = item()
        repeat(30) {
            state = scheduler.schedule(state, AnswerVerdict.ROOTED_MISCONCEPTION, today)
        }
        assertTrue(state.easeFactor >= 1.3)
    }

    @Test
    fun `intervals are capped so nothing disappears forever`() {
        var state = item()
        var day = today
        repeat(30) {
            state = scheduler.schedule(state, AnswerVerdict.SOLID, day)
            day = state.dueOn
        }
        assertTrue("Intervallo fuori scala: ${state.intervalDays}", state.intervalDays <= 180)
    }

    @Test
    fun `a hesitant but correct answer still moves forward, only slower`() {
        val fragile = scheduler.schedule(item(interval = 10, repetitions = 3), AnswerVerdict.CORRECT_BUT_FRAGILE, today)
        val solid = scheduler.schedule(item(interval = 10, repetitions = 3), AnswerVerdict.SOLID, today)

        assertTrue(fragile.repetitions > 0)
        assertTrue(
            "Chi ha esitato deve rivedere prima di chi era sicuro",
            fragile.intervalDays < solid.intervalDays,
        )
    }

    // --- Choosing what to revise today -----------------------------------------------

    @Test
    fun `only due items are proposed`() {
        val due = scheduler.dueToday(
            listOf(
                item("oggi", due = today),
                item("ieri", due = today.minusDays(1)),
                item("domani", due = today.plusDays(1)),
            ),
            today,
        )
        assertEquals(listOf("ieri", "oggi"), due.map { it.skillId })
    }

    @Test
    fun `suspected luck jumps the queue`() {
        val due = scheduler.dueToday(
            listOf(
                item("vecchio", due = today.minusDays(10)),
                item("fortunato", due = today, lucky = true),
            ),
            today,
        )
        assertEquals("fortunato", due.first().skillId)
    }

    @Test
    fun `the session respects the time the student actually has`() {
        val items = (1..40).map { item("skill_$it", due = today.minusDays(it.toLong())) }
        val capacity = scheduler.capacityFor(minutesAvailable = 5)
        val session = scheduler.dueToday(items, today, maxItems = capacity)

        assertEquals(capacity, session.size)
        assertTrue("Cinque minuti non possono contenere 40 domande", session.size < items.size)
    }

    @Test
    fun `even the shortest budget leaves room for one question`() {
        assertTrue(scheduler.capacityFor(minutesAvailable = 0) >= 1)
    }

    @Test
    fun `an item is due on its date and not before`() {
        val entry = item(due = today.plusDays(1))
        assertFalse(entry.isDue(today))
        assertTrue(entry.isDue(today.plusDays(1)))
        assertTrue(entry.isDue(today.plusDays(2)))
    }
}
