package com.cybersensei.academy.engine.tutor

import com.cybersensei.academy.core.common.FixedTimeProvider
import com.cybersensei.academy.core.model.DailyBudget
import com.cybersensei.academy.core.model.Level
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.core.model.TutorTone
import com.cybersensei.academy.engine.mastery.AnswerVerdict
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorEngineTest {

    private val clock = FixedTimeProvider(Instant.parse("2026-07-25T10:00:00Z"))
    private val library = DialogueLibrary.fromResources()
    private fun engine() = TutorEngine(library, clock)

    private fun profile(
        name: String = "Francesco",
        birth: LocalDate? = LocalDate.of(1990, 11, 3),
        tone: TutorTone = TutorTone.FRIENDLY,
    ) = StudentProfile(
        name = name,
        birthDate = birth,
        tone = tone,
        dailyBudget = DailyBudget.NORMAL,
        enrolledOn = LocalDate.of(2026, 1, 10),
    )

    private fun student(
        profile: StudentProfile? = profile(),
        streak: Int = 2,
        reviews: Int = 0,
        absence: Int = 0,
        mastery: Double = 0.5,
        unfinished: String? = null,
        misconception: String? = null,
        misconceptionTimes: Int = 0,
    ) = StudentSnapshot(
        profile = profile,
        level = Level.EASY,
        streakDays = streak,
        recordStreakDays = 12,
        daysSinceLastVisit = absence,
        masteryAverage = mastery,
        weakestSkillLabel = "le porte di rete",
        recurringMisconceptionLabel = misconception,
        recurringMisconceptionTimes = misconceptionTimes,
        unfinishedLessonTitle = unfinished,
        dueReviews = reviews,
        totalStudyMinutes = 240,
    )

    // --- The professor is never silent -------------------------------------------------

    @Test
    fun `every event produces a real sentence, whatever the student's state`() {
        val events = listOf(
            TutorEvent.AppOpened,
            TutorEvent.StreakBroken,
            TutorEvent.LessonCompleted("Password e passphrase"),
            TutorEvent.LessonAbandoned("Il DNS"),
            TutorEvent.ExamPassed("Facile", 88),
            TutorEvent.ExamFailed("Facile", "il phishing"),
            TutorEvent.ReturnedAfterAbsence(45),
            TutorEvent.ReviewsDue(12),
            TutorEvent.LevelUnlocked("Intermedio"),
            TutorEvent.UnknownQuestion("la VPN mi rende anonimo?"),
        ) + AnswerVerdict.entries.map {
            TutorEvent.AnswerJudged(it, "gli hash", "l'hash non si decifra")
        }

        val states = listOf(
            student(),
            student(profile = null),
            student(streak = 30, reviews = 20, mastery = 0.95),
            student(absence = 60, mastery = 0.05, unfinished = "Il DNS"),
            student(profile = profile(tone = TutorTone.STRICT), misconception = "hash e cifratura", misconceptionTimes = 3),
            student(profile = profile(tone = TutorTone.IRONIC)),
        )

        val engine = engine()
        for (hour in listOf(3, 9, 15, 20, 23)) {
            clock.set(LocalDateTime.of(2026, 7, 25, hour, 0))
            for (event in events) {
                for (state in states) {
                    val line = engine.speak(event, state)
                    assertTrue("Battuta vuota per ${event.key}", line.text.isNotBlank())
                    assertFalse(
                        "Segnaposto non risolto in «${line.text}» (${line.poolId})",
                        line.text.contains('{') || line.text.contains('}'),
                    )
                    assertFalse(
                        "Punteggiatura orfana in «${line.text}»",
                        line.text.contains(" ,") || line.text.contains(",.") || line.text.contains(" ."),
                    )
                }
            }
        }
    }

    // --- Choosing the right thing to say -----------------------------------------------

    @Test
    fun `a student the professor has never met gets introduced to him`() {
        val line = engine().speak(TutorEvent.AppOpened, student(profile = null))
        assertEquals("apertura_primo_incontro", line.poolId)
        assertTrue(line.text.contains("Hackstein White"))
    }

    @Test
    fun `a birthday outranks everything else`() {
        clock.set(LocalDateTime.of(2026, 11, 3, 10, 0))
        val line = engine().speak(TutorEvent.AppOpened, student(streak = 30, reviews = 15))
        assertEquals("apertura_compleanno", line.poolId)
    }

    @Test
    fun `studying at four in the morning changes what he says`() {
        clock.set(LocalDateTime.of(2026, 7, 25, 4, 0))
        val line = engine().speak(TutorEvent.AppOpened, student())
        assertEquals("apertura_notte_fonda", line.poolId)
    }

    @Test
    fun `an unfinished lesson is picked up by name`() {
        val line = engine().speak(TutorEvent.AppOpened, student(unfinished = "Il DNS"))
        assertEquals("apertura_lezione_interrotta", line.poolId)
        assertTrue("Il titolo della lezione deve comparire: ${line.text}", line.text.contains("Il DNS"))
    }

    @Test
    fun `a repeated mistake is cited with the number of times it happened`() {
        val line = engine().speak(
            TutorEvent.AppOpened,
            student(misconception = "hash e cifratura", misconceptionTimes = 3),
        )
        assertEquals("apertura_errore_ricorrente", line.poolId)
        // Case-insensitive: the label is capitalised when it happens to open a sentence.
        assertTrue(line.text.contains("hash e cifratura", ignoreCase = true))
        assertTrue("Deve dire quante volte: ${line.text}", line.text.contains("3"))
    }

    @Test
    fun `the chosen tone changes the professor's voice`() {
        val strict = engine().speak(TutorEvent.AppOpened, student(profile = profile(tone = TutorTone.STRICT)))
        val ironic = engine().speak(TutorEvent.AppOpened, student(profile = profile(tone = TutorTone.IRONIC)))
        val friendly = engine().speak(TutorEvent.AppOpened, student(profile = profile(tone = TutorTone.FRIENDLY)))

        assertEquals("apertura_severa", strict.poolId)
        assertEquals("apertura_ironica", ironic.poolId)
        assertEquals("apertura_standard", friendly.poolId)
    }

    @Test
    fun `each verdict gets its own kind of feedback`() {
        val engine = engine()
        val expected = mapOf(
            AnswerVerdict.SOLID to "risposta_solida",
            AnswerVerdict.CORRECT_BUT_FRAGILE to "risposta_fragile",
            AnswerVerdict.SUSPECTED_LUCK to "risposta_fortunata",
            AnswerVerdict.ROOTED_MISCONCEPTION to "errore_radicato",
            AnswerVerdict.HONEST_MISS to "errore_onesto",
        )
        expected.forEach { (verdict, pool) ->
            val line = engine.reactToAnswer(verdict, "gli hash", student(), "l'hash non si decifra")
            assertEquals("Verdetto $verdict", pool, line.poolId)
        }
    }

    @Test
    fun `a long absence is treated differently from a short one`() {
        val engine = engine()
        assertEquals(
            "rientro_lungo",
            engine.speak(TutorEvent.ReturnedAfterAbsence(45), student(absence = 45)).poolId,
        )
        assertEquals(
            "rientro_medio",
            engine.speak(TutorEvent.ReturnedAfterAbsence(14), student(absence = 14)).poolId,
        )
        assertEquals(
            "rientro_breve",
            engine.speak(TutorEvent.ReturnedAfterAbsence(2), student(absence = 2)).poolId,
        )
    }

    @Test
    fun `a question outside the syllabus gets an honest answer, not an invented one`() {
        val line = engine().speak(TutorEvent.UnknownQuestion("come si buca un wifi?"), student())
        assertEquals("domanda_sconosciuta", line.poolId)
        assertTrue(line.text.isNotBlank())
    }

    // --- Not sounding like a machine ----------------------------------------------------

    @Test
    fun `the professor does not repeat himself in consecutive openings`() {
        val engine = engine()
        val state = student()
        var previous = engine.speak(TutorEvent.AppOpened, state).text
        repeat(30) {
            val line = engine.speak(TutorEvent.AppOpened, state).text
            assertFalse("Battuta ripetuta di fila: $line", line == previous)
            previous = line
        }
    }

    @Test
    fun `over many openings he uses the whole pool, not one favourite line`() {
        val engine = engine()
        val state = student()
        val seen = (1..40).map { engine.speak(TutorEvent.AppOpened, state).text }.toSet()
        val poolSize = library.poolOf("apertura_standard")!!.lines.size
        assertEquals("Deve usare tutte le varianti disponibili", poolSize, seen.size)
    }

    @Test
    fun `the same student in the same situation hears the same thing`() {
        val first = TutorEngine(library, clock).speak(TutorEvent.AppOpened, student())
        val second = TutorEngine(library, clock).speak(TutorEvent.AppOpened, student())
        assertEquals(first.text, second.text)
    }

    @Test
    fun `two different students do not get the same script`() {
        val a = TutorEngine(library, clock)
        val b = TutorEngine(library, clock)
        val linesA = (1..6).map { a.speak(TutorEvent.AppOpened, student(profile = profile("Francesco"))).text }
        val linesB = (1..6).map { b.speak(TutorEvent.AppOpened, student(profile = profile("Giulia"))).text }
        assertTrue("Due studenti diversi devono divergere", linesA != linesB)
    }

    @Test
    fun `the rule that fired is always traceable`() {
        val line = engine().speak(TutorEvent.AppOpened, student())
        assertNotNull(line.ruleId)
        assertTrue(library.rules.any { it.id == line.ruleId })
    }
}
