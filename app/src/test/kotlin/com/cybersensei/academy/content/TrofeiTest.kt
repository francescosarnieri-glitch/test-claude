package com.cybersensei.academy.content

import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.curriculum.LabCatalogue
import com.cybersensei.academy.core.curriculum.TrophyCondition
import com.cybersensei.academy.core.curriculum.TrophyEngine
import com.cybersensei.academy.core.curriculum.TrophyFamily
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.scenario.ScenarioLibrary
import com.cybersensei.academy.ui.labs.Lab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trophies against the school they are meant to measure.
 *
 * The engine's own tests prove the rules work; these prove the forty-four shipped trophies are
 * reachable in *this* school. A trophy asking for eleven cases in a level that has nine is not
 * a bug the rules can catch — it is a bug only the content can.
 */
class TrofeiTest {

    private val trophies = TrophyEngine.fromResources()
    private val curriculum = Curriculum.fromResources()
    private val scenarios = ScenarioLibrary.fromResources()

    private val lessonsTotal = curriculum.levels.sumOf { level -> level.modules.sumOf { it.lessons.size } }
    private val casesByLevel = scenarios.cases.groupingBy { it.level }.eachCount()
    private val examsTotal = curriculum.levels.count { it.modules.isNotEmpty() }

    @Test
    fun `the content is valid`() {
        assertTrue(trophies.validate().toString(), trophies.validate().isEmpty())
    }

    @Test
    fun `every family has somewhere to start and somewhere to end`() {
        trophies.byFamily().forEach { (family, list) ->
            assertTrue("La famiglia ${family.italianName} è vuota", list.isNotEmpty())
        }
        assertEquals("Ogni famiglia dichiarata deve avere una mensola", TrophyFamily.entries.size, trophies.byFamily().size)
    }

    @Test
    fun `no trophy asks for more lessons than the programme has`() {
        trophies.all()
            .filter { it.condition.type == TrophyCondition.LESSONS_COMPLETED }
            .forEach {
                assertTrue(
                    "Il trofeo '${it.id}' chiede ${it.condition.value} lezioni, il programma ne ha $lessonsTotal",
                    it.condition.value <= lessonsTotal,
                )
            }
    }

    @Test
    fun `no trophy asks for more cases than the school has`() {
        val total = casesByLevel.values.sum()
        trophies.all()
            .filter { it.condition.type == TrophyCondition.CASES_COMPLETED }
            .forEach {
                assertTrue(
                    "Il trofeo '${it.id}' chiede ${it.condition.value} casi, la scuola ne ha $total",
                    it.condition.value <= total,
                )
            }
        trophies.all()
            .filter { it.condition.type == TrophyCondition.PERFECT_CASES }
            .forEach {
                assertTrue(
                    "Il trofeo '${it.id}' chiede ${it.condition.value} casi perfetti su $total",
                    it.condition.value <= total,
                )
            }
    }

    @Test
    fun `every level named by a case trophy actually has cases`() {
        trophies.all()
            .filter { it.condition.type == TrophyCondition.CASES_IN_LEVEL }
            .forEach {
                val count = casesByLevel[it.condition.value] ?: 0
                assertTrue(
                    "Il trofeo '${it.id}' riguarda il livello ${it.condition.value}, che non ha casi",
                    count > 0,
                )
            }
    }

    @Test
    fun `every level named by a level trophy exists`() {
        val levels = curriculum.levels.map { it.level }.toSet()
        trophies.all()
            .filter { it.condition.type == TrophyCondition.LEVEL_PASSED }
            .forEach {
                assertTrue(
                    "Il trofeo '${it.id}' riguarda il livello ${it.condition.value}, che non esiste",
                    it.condition.value in levels,
                )
            }
    }

    @Test
    fun `no trophy asks for more exams than the school sits`() {
        trophies.all()
            .filter {
                it.condition.type == TrophyCondition.EXAMS_PASSED ||
                    it.condition.type == TrophyCondition.EXAMS_FIRST_TRY ||
                    it.condition.type == TrophyCondition.PERFECT_EXAMS
            }
            .forEach {
                assertTrue(
                    "Il trofeo '${it.id}' chiede ${it.condition.value} esami, la scuola ne ha $examsTotal",
                    it.condition.value <= examsTotal,
                )
            }
    }

    @Test
    fun `no trophy asks for more labs than can be finished`() {
        trophies.all()
            .filter { it.condition.type == TrophyCondition.LABS_COMPLETED }
            .forEach {
                assertTrue(
                    "Il trofeo '${it.id}' chiede ${it.condition.value} laboratori, se ne possono " +
                        "portare a termine ${LabCatalogue.COUNT}",
                    it.condition.value <= LabCatalogue.COUNT,
                )
            }
    }

    @Test
    fun `no trophy asks for more skills than the syllabus teaches`() {
        val skills = curriculum.levels.flatMap { it.modules }.flatMap { it.skills }.distinct().size
        val modules = curriculum.levels.sumOf { it.modules.size }
        trophies.all()
            .filter { it.condition.type == TrophyCondition.SKILLS_MASTERED }
            .forEach {
                assertTrue(
                    "Il trofeo '${it.id}' chiede ${it.condition.value} competenze su $skills",
                    it.condition.value <= skills,
                )
            }
        trophies.all()
            .filter { it.condition.type == TrophyCondition.MODULES_MASTERED }
            .forEach {
                assertTrue(
                    "Il trofeo '${it.id}' chiede ${it.condition.value} moduli su $modules",
                    it.condition.value <= modules,
                )
            }
    }

    /**
     * The list the trophy rules count against, and the workshops the app really has.
     *
     * The ids live in two modules — the rules cannot see the screens — so a lab renamed on one
     * side and not the other would silently make "Tutta l'officina" impossible. This is the
     * seam, and it breaks the build instead.
     */
    @Test
    fun `every finishable lab in the catalogue is a real workshop`() {
        val known = Lab.entries.map { it.id }.toSet()
        LabCatalogue.WITH_VERDICTS.forEach { id ->
            assertTrue("Il laboratorio '$id' del catalogo non esiste fra le officine", id in known)
        }
        assertEquals(
            "Nessun doppione nel catalogo",
            LabCatalogue.WITH_VERDICTS.size,
            LabCatalogue.WITH_VERDICTS.distinct().size,
        )
        assertTrue("Il catalogo non può essere vuoto", LabCatalogue.COUNT > 0)
        assertTrue(
            "I laboratori che si possono finire non possono essere più di quelli che esistono",
            LabCatalogue.COUNT <= Lab.entries.size,
        )
    }

    /**
     * The trophy that stands for the certificate, and the certificate's own id.
     *
     * Written as a constant in two places for good reasons on both sides; this is what keeps
     * them the same string.
     */
    @Test
    fun `the diploma trophy is the one the records module looks for`() {
        assertTrue(
            "Manca il trofeo del diploma: '${SchoolRepository.DIPLOMA_TROPHY_ID}'",
            trophies.byId(SchoolRepository.DIPLOMA_TROPHY_ID) != null,
        )
        assertEquals(
            "Il trofeo del diploma deve usare la condizione del diploma",
            TrophyCondition.DIPLOMA,
            trophies.byId(SchoolRepository.DIPLOMA_TROPHY_ID)?.condition?.type,
        )
    }

    @Test
    fun `the final case the trophies celebrate is the one in the content`() {
        assertTrue(
            "Il caso finale '${SchoolRepository.FINAL_CASE_ID}' non è nei contenuti",
            scenarios.case(SchoolRepository.FINAL_CASE_ID) != null,
        )
        assertTrue(
            "Serve un trofeo per la notte dell'Incidente",
            trophies.all().any { it.condition.type == TrophyCondition.FINAL_CASE_COMPLETED },
        )
    }

    /**
     * Nothing arrives for reading, scrolling or waiting.
     *
     * The rule the whole set is built on, and the one worth a test of its own: if a condition
     * type ever appears that measures time spent or pages turned, this fails and somebody has
     * to argue for it out loud.
     */
    @Test
    fun `no trophy is earned by anything other than work done`() {
        val forbidden = setOf("time_at_school", "app_openings", "lessons_read", "screens_visited")
        trophies.all().forEach {
            assertFalse(
                "Il trofeo '${it.id}' premia qualcosa che non è lavoro: ${it.condition.type}",
                it.condition.type in forbidden,
            )
        }
    }

    @Test
    fun `the wall is worth opening`() {
        // Not a rule, a judgement — but a wall of six medals for a hundred-lesson programme is
        // as wrong as a wall of three hundred, and this is the tripwire for both.
        assertTrue("Troppi pochi trofei: ${trophies.all().size}", trophies.all().size >= 30)
        assertTrue("Troppi trofei: ${trophies.all().size}", trophies.all().size <= 60)
    }
}
