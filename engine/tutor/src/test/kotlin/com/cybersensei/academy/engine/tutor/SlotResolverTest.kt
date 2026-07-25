package com.cybersensei.academy.engine.tutor

import com.cybersensei.academy.core.common.FixedTimeProvider
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.mastery.AnswerVerdict
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SlotResolverTest {

    private val clock = FixedTimeProvider(Instant.parse("2026-07-25T10:00:00Z"))
    private val resolver = SlotResolver(clock)

    private val student = StudentSnapshot(
        profile = StudentProfile(
            name = "Francesco",
            nickname = "Fra",
            birthDate = LocalDate.of(1990, 11, 3),
            enrolledOn = LocalDate.of(2026, 1, 10),
        ),
        streakDays = 9,
        dueReviews = 5,
    )

    private fun resolve(template: String, state: StudentSnapshot = student) =
        resolver.resolve(template, state, TutorEvent.AppOpened)

    @Test
    fun `personal facts land in the sentence`() {
        assertEquals("Ciao Francesco, sono 9 giorni.", resolve("Ciao {nome}, sono {streak} giorni."))
        assertEquals("Dimmi, Fra.", resolve("Dimmi, {appellativo}."))
    }

    @Test
    fun `the zodiac sign and age come from the birth date`() {
        assertEquals("Scorpione, 35 anni.", resolve("{segno}, {eta} anni."))
    }

    @Test
    fun `the greeting follows the clock`() {
        clock.set(LocalDateTime.of(2026, 7, 25, 20, 0))
        assertEquals("Buonasera.", resolve("{saluto}."))
        clock.set(LocalDateTime.of(2026, 7, 25, 9, 0))
        assertEquals("Buongiorno.", resolve("{saluto}."))
    }

    @Test
    fun `a slot the app cannot fill does not leave a hole in the sentence`() {
        val anonymous = student.copy(profile = null)
        val line = resolve("Bentornato, {nome}. Riprendiamo.", anonymous)
        assertEquals("Bentornato. Riprendiamo.", line)
    }

    @Test
    fun `a sentence starting with a slot value is capitalised`() {
        val line = resolver.resolve(
            "Si riparte dalle fondamenta. {misconcezione}.",
            student,
            TutorEvent.AnswerJudged(AnswerVerdict.HONEST_MISS, "gli hash", "l'hash non si decifra"),
        )
        assertEquals("Si riparte dalle fondamenta. L'hash non si decifra.", line)
    }

    @Test
    fun `text inside quotes keeps the case the content author chose`() {
        assertEquals(
            "Riprendiamo da «il DNS» adesso.",
            resolve("Riprendiamo da «il DNS» adesso."),
        )
    }

    @Test
    fun `no template in the shipped script leaves stray punctuation for an unknown student`() {
        val anonymous = student.copy(profile = null)
        DialogueLibrary.fromResources().pools.forEach { pool ->
            pool.lines.forEach { template ->
                val line = resolver.resolve(template, anonymous, TutorEvent.AppOpened)
                assertFalse("Doppio spazio in «$line»", line.contains("  "))
                assertFalse("Virgola orfana in «$line»", line.contains(" ,") || line.contains(",."))
            }
        }
    }

    @Test
    fun `the slots a template needs can be listed for validation`() {
        assertEquals(setOf("nome", "streak"), resolver.slotsUsedIn("Ciao {nome}, {streak} giorni"))
    }
}
