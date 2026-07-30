package com.cybersensei.academy.core.curriculum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trophy rules.
 *
 * Every condition type gets a test that it fires when it should and — more important — a test
 * that it does not fire on an empty record. A trophy handed out to a student who has done
 * nothing is the one bug that devalues all forty-four at once.
 */
class TrophyEngineTest {

    private fun trophy(
        id: String,
        type: String,
        value: Int = 0,
        tier: TrophyTier = TrophyTier.BRONZE,
        family: TrophyFamily = TrophyFamily.PATH,
    ) = Trophy(
        id = id,
        name = id,
        icon = "🎖️",
        tier = tier,
        family = family,
        description = "Una descrizione abbastanza lunga da passare la validazione del contenuto.",
        howToEarn = "Fai la cosa richiesta.",
        condition = TrophyCondition(type, value),
    )

    private fun engine(vararg trophies: Trophy) = TrophyEngine(trophies.toList())

    // --- Nothing for free -----------------------------------------------------------------

    @Test
    fun `an empty record earns nothing at all`() {
        val all = TrophyCondition.KNOWN_TYPES.mapIndexed { index, type ->
            // value 1 is the smallest meaningful threshold; zero would make counters trivially true.
            trophy("t$index", type, value = 1)
        }
        val earned = TrophyEngine(all).earned(TrophyContext())
        assertTrue("Nessun trofeo può arrivare a mani vuote: ${earned.map { it.id }}", earned.isEmpty())
    }

    @Test
    fun `an unknown condition never hands out a trophy`() {
        val engine = engine(trophy("misterioso", "una_regola_che_non_esiste", 1))
        // Even a student who has done everything must not get it.
        val everything = TrophyContext(
            lessonsCompleted = 500,
            lessonsTotal = 100,
            streakDays = 900,
            masteryAverage = 1.0,
            diplomaEarned = true,
        )
        assertTrue(engine.earned(everything).isEmpty())
    }

    // --- Percorso -------------------------------------------------------------------------

    @Test
    fun `lessons completed counts up to its threshold`() {
        val engine = engine(trophy("dieci", TrophyCondition.LESSONS_COMPLETED, 10))
        assertTrue(engine.earned(TrophyContext(lessonsCompleted = 9)).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(lessonsCompleted = 10)).size)
        assertEquals(1, engine.earned(TrophyContext(lessonsCompleted = 40)).size)
    }

    @Test
    fun `all lessons needs a programme to exist`() {
        val engine = engine(trophy("tutte", TrophyCondition.ALL_LESSONS))
        // A school with no lessons must not certify that they have all been done.
        assertTrue(engine.earned(TrophyContext(lessonsCompleted = 0, lessonsTotal = 0)).isEmpty())
        assertTrue(engine.earned(TrophyContext(lessonsCompleted = 107, lessonsTotal = 108)).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(lessonsCompleted = 108, lessonsTotal = 108)).size)
    }

    // --- Livelli --------------------------------------------------------------------------

    @Test
    fun `a level trophy is about that level and no other`() {
        val engine = engine(trophy("livello2", TrophyCondition.LEVEL_PASSED, 2))
        assertTrue(engine.earned(TrophyContext(passedLevels = setOf(0, 1, 3))).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(passedLevels = setOf(2))).size)
    }

    // --- Casi -----------------------------------------------------------------------------

    @Test
    fun `cases in a level need every case of that level`() {
        val engine = engine(trophy("casiL1", TrophyCondition.CASES_IN_LEVEL, 1))
        val totals = mapOf(0 to 1, 1 to 3, 2 to 5)
        assertTrue(
            engine.earned(
                TrophyContext(casesCompletedByLevel = mapOf(1 to 2), casesTotalByLevel = totals),
            ).isEmpty(),
        )
        assertEquals(
            1,
            engine.earned(
                TrophyContext(casesCompletedByLevel = mapOf(1 to 3), casesTotalByLevel = totals),
            ).size,
        )
    }

    @Test
    fun `cases in a level that has none is never satisfied`() {
        val engine = engine(trophy("casiL9", TrophyCondition.CASES_IN_LEVEL, 9))
        assertTrue(engine.earned(TrophyContext(casesTotalByLevel = mapOf(1 to 3))).isEmpty())
    }

    @Test
    fun `all cases counts across every level`() {
        val engine = engine(trophy("tutti", TrophyCondition.ALL_CASES))
        val totals = mapOf(0 to 1, 1 to 3)
        assertTrue(
            engine.earned(
                TrophyContext(casesCompletedByLevel = mapOf(1 to 3), casesTotalByLevel = totals),
            ).isEmpty(),
        )
        assertEquals(
            1,
            engine.earned(
                TrophyContext(
                    casesCompletedByLevel = mapOf(0 to 1, 1 to 3),
                    casesTotalByLevel = totals,
                ),
            ).size,
        )
    }

    @Test
    fun `perfect cases count only the perfect ones`() {
        val engine = engine(trophy("tre", TrophyCondition.PERFECT_CASES, 3))
        assertTrue(engine.earned(TrophyContext(perfectCases = 2)).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(perfectCases = 3)).size)
    }

    // --- Esami ----------------------------------------------------------------------------

    @Test
    fun `exams passed counts distinct levels`() {
        val engine = engine(trophy("due", TrophyCondition.EXAMS_PASSED, 2))
        assertTrue(engine.earned(TrophyContext(examsPassed = setOf(1))).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(examsPassed = setOf(1, 2))).size)
    }

    @Test
    fun `all exams needs the school to have some`() {
        val engine = engine(trophy("tutti", TrophyCondition.ALL_EXAMS))
        assertTrue(engine.earned(TrophyContext(examsPassed = emptySet(), examsTotal = 0)).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(examsPassed = setOf(0, 1, 2, 3), examsTotal = 4)).size)
    }

    @Test
    fun `first try and perfect exams are separate facts`() {
        val engine = engine(
            trophy("primo", TrophyCondition.EXAMS_FIRST_TRY, 1),
            trophy("perfetto", TrophyCondition.PERFECT_EXAMS, 1),
        )
        // Passed at the first attempt, but not with a hundred: one of the two, not both.
        val ids = engine.earned(TrophyContext(examsFirstTry = 1, perfectExams = 0)).map { it.id }
        assertEquals(listOf("primo"), ids)
    }

    // --- Laboratori -----------------------------------------------------------------------

    @Test
    fun `labs count what has been taken to the end`() {
        val engine = engine(
            trophy("tre", TrophyCondition.LABS_COMPLETED, 3),
            trophy("tutti", TrophyCondition.ALL_LABS),
        )
        assertEquals(
            listOf("tre"),
            engine.earned(TrophyContext(labsCompleted = 3, labsTotal = 5)).map { it.id },
        )
        assertEquals(2, engine.earned(TrophyContext(labsCompleted = 5, labsTotal = 5)).size)
    }

    // --- Padronanza -----------------------------------------------------------------------

    @Test
    fun `mastered skills and modules are counted separately`() {
        val engine = engine(
            trophy("competenze", TrophyCondition.SKILLS_MASTERED, 10),
            trophy("moduli", TrophyCondition.MODULES_MASTERED, 1),
        )
        // Ten strong skills spread across modules do not complete any single module.
        assertEquals(
            listOf("competenze"),
            engine.earned(TrophyContext(skillsMastered = 10, modulesMastered = 0)).map { it.id },
        )
    }

    @Test
    fun `mastery average is read as a percentage`() {
        val engine = engine(trophy("media", TrophyCondition.MASTERY_AVERAGE, 90))
        assertTrue(engine.earned(TrophyContext(masteryAverage = 0.89)).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(masteryAverage = 0.90)).size)
    }

    @Test
    fun `a flawless interrogation is one trophy, not one per question`() {
        val engine = engine(trophy("pulita", TrophyCondition.FLAWLESS_QUIZZES, 1))
        assertTrue(engine.earned(TrophyContext(flawlessQuizzes = 0)).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(flawlessQuizzes = 1)).size)
        assertEquals(1, engine.earned(TrophyContext(flawlessQuizzes = 12)).size)
    }

    // --- Costanza -------------------------------------------------------------------------

    @Test
    fun `a broken streak keeps the record trophy`() {
        val engine = engine(
            trophy("serie", TrophyCondition.STREAK_DAYS, 14),
            trophy("record", TrophyCondition.RECORD_STREAK, 14),
        )
        // The student got to fourteen days and then stopped: the record stands, the streak does not.
        val ids = engine.earned(TrophyContext(streakDays = 1, recordStreakDays = 20)).map { it.id }
        assertEquals(listOf("record"), ids)
    }

    @Test
    fun `coming back is what earns the return trophy`() {
        val engine = engine(trophy("ritorno", TrophyCondition.RETURN_AFTER_ABSENCE, 7))
        assertTrue(engine.earned(TrophyContext(longestReturnDays = 6)).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(longestReturnDays = 9)).size)
    }

    // --- Onore ----------------------------------------------------------------------------

    @Test
    fun `the final case and a perfect final case are different trophies`() {
        val engine = engine(
            trophy("notte", TrophyCondition.FINAL_CASE_COMPLETED),
            trophy("perfetta", TrophyCondition.FINAL_CASE_PERFECT),
        )
        assertEquals(
            listOf("notte"),
            engine.earned(TrophyContext(finalCaseCompleted = true, finalCasePerfect = false)).map { it.id },
        )
        assertEquals(2, engine.earned(TrophyContext(finalCaseCompleted = true, finalCasePerfect = true)).size)
    }

    @Test
    fun `nothing abandoned needs a body of work behind it`() {
        val engine = engine(trophy("scorciatoie", TrophyCondition.NOTHING_ABANDONED, 25))
        // One lesson done and none abandoned is not a record of never having given up.
        assertTrue(engine.earned(TrophyContext(lessonsCompleted = 1, lessonsAbandoned = 0)).isEmpty())
        assertTrue(engine.earned(TrophyContext(lessonsCompleted = 30, lessonsAbandoned = 1)).isEmpty())
        assertEquals(1, engine.earned(TrophyContext(lessonsCompleted = 25, lessonsAbandoned = 0)).size)
    }

    @Test
    fun `everything means every catalogue of the school`() {
        val engine = engine(trophy("maestro", TrophyCondition.EVERYTHING))
        val complete = TrophyContext(
            lessonsCompleted = 108,
            lessonsTotal = 108,
            casesCompletedByLevel = mapOf(0 to 16),
            casesTotalByLevel = mapOf(0 to 16),
            examsPassed = setOf(0, 1, 2, 3),
            examsTotal = 4,
            labsCompleted = 5,
            labsTotal = 5,
            exercisesSolved = 5,
            exercisesTotal = 5,
        )
        assertEquals(1, engine.earned(complete).size)
        // Take away any single one of them and the crown goes with it.
        assertTrue(engine.earned(complete.copy(labsCompleted = 4)).isEmpty())
        assertTrue(engine.earned(complete.copy(exercisesSolved = 4)).isEmpty())
        assertTrue(engine.earned(complete.copy(examsPassed = setOf(0, 1, 2))).isEmpty())
        assertTrue(engine.earned(complete.copy(lessonsCompleted = 107)).isEmpty())
        assertTrue(engine.earned(complete.copy(casesCompletedByLevel = mapOf(0 to 15))).isEmpty())
    }

    // --- Announcing -----------------------------------------------------------------------

    @Test
    fun `a trophy already held is never announced twice`() {
        val engine = engine(trophy("dieci", TrophyCondition.LESSONS_COMPLETED, 10))
        val context = TrophyContext(lessonsCompleted = 30)
        assertEquals(1, engine.newlyEarned(context, alreadyHeld = emptySet()).size)
        assertTrue(engine.newlyEarned(context, alreadyHeld = setOf("dieci")).isEmpty())
    }

    @Test
    fun `families come out in display order with tiers ascending`() {
        val engine = engine(
            trophy("oro", TrophyCondition.DIPLOMA, tier = TrophyTier.GOLD, family = TrophyFamily.HONOUR),
            trophy("bronzo", TrophyCondition.DIPLOMA, tier = TrophyTier.BRONZE, family = TrophyFamily.HONOUR),
            trophy("percorso", TrophyCondition.LESSONS_COMPLETED, 1, family = TrophyFamily.PATH),
        )
        val shelves = engine.byFamily()
        assertEquals(listOf(TrophyFamily.PATH, TrophyFamily.HONOUR), shelves.map { it.first })
        assertEquals(listOf("bronzo", "oro"), shelves.last().second.map { it.id })
    }

    // --- Validation -----------------------------------------------------------------------

    @Test
    fun `validation catches the mistakes a content writer actually makes`() {
        val broken = TrophyEngine(
            listOf(
                trophy("doppio", TrophyCondition.LESSONS_COMPLETED, 1),
                trophy("doppio", TrophyCondition.LESSONS_COMPLETED, 2),
                trophy("ignoto", "non_esiste", 1),
                trophy("muto", TrophyCondition.DIPLOMA).copy(description = "Corta."),
                trophy("senzaicona", TrophyCondition.DIPLOMA).copy(icon = " "),
                trophy("senzaistruzioni", TrophyCondition.DIPLOMA).copy(howToEarn = "Boh."),
            ),
        )
        val problems = broken.validate()
        assertTrue("Il duplicato", problems.any { it.contains("duplicato") })
        assertTrue("La condizione sconosciuta", problems.any { it.contains("sconosciuta") })
        assertTrue("La descrizione corta", problems.any { it.contains("non spiega cosa significa") })
        assertTrue("Il simbolo mancante", problems.any { it.contains("non ha un simbolo") })
        assertTrue("Le istruzioni corte", problems.any { it.contains("non dice come si guadagna") })
        assertTrue("Il simbolo ripetuto", problems.any { it.contains("è usato da più di un trofeo") })
        assertTrue("Le famiglie vuote", problems.any { it.contains("non ha nessun trofeo") })
    }

    @Test
    fun `the shipped trophies are valid`() {
        val problems = TrophyEngine.fromResources().validate()
        assertTrue("I trofei del gioco: $problems", problems.isEmpty())
    }

    @Test
    fun `the shipped trophies give a new student nothing`() {
        // The day the app is installed the wall must be empty, or the first medal means nothing.
        val earned = TrophyEngine.fromResources().earned(TrophyContext(lessonsTotal = 108))
        assertTrue("Il primo giorno la bacheca è vuota: ${earned.map { it.id }}", earned.isEmpty())
    }
}
