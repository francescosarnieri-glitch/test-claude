package com.cybersensei.academy.ui.report

import android.os.Looper
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.TrophyEngine
import com.cybersensei.academy.SchoolClock
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.tutor.TutorEngine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A report card is worth something only if it can say bad news.
 *
 * The two claims under test are the ones that make it a measurement rather than a
 * congratulation screen: a skill never answered is reported as untouched and not as zero,
 * and a module counts as passed only when the same gate that unlocks levels agrees.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class ReportViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var trophyEngine: TrophyEngine
    @Inject lateinit var tutor: TutorEngine
    @Inject lateinit var timeProvider: TimeProvider

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            repository.saveProfile(
                StudentProfile(
                    name = "Francesco",
                    birthDate = LocalDate.of(1990, 11, 3),
                    enrolledOn = LocalDate.of(2026, 1, 10),
                    ethicalPactSigned = true,
                ),
            )
        }
    }

    private fun viewModel(): ReportViewModel =
        ReportViewModel(repository, curriculum, trophyEngine, tutor, timeProvider, SchoolClock(timeProvider))
            .also { it.awaitLoaded() }

    private fun ReportViewModel.awaitLoaded() {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (uiState.value.summary != null) return
            Thread.sleep(POLL_MILLIS)
        }
        error("La pagella non ha finito di caricare entro ${LOAD_TIMEOUT_MILLIS}ms")
    }

    /** Answers a skill correctly [times] times, confidently and at a believable pace. */
    private fun drill(skillId: String, times: Int, correct: Boolean = true) = runBlocking {
        repeat(times) {
            repository.recordAnswer(
                skillId = skillId,
                correct = correct,
                confidence = Confidence.SURE,
                responseTime = 30.seconds,
                expectedTime = 40.seconds,
                misconceptionLabel = if (correct) null else "misconcezione_di_prova",
            )
        }
    }

    private fun firstSkillOfFirstModule(): String =
        curriculum.levels.first().modules.first().skills.first()

    @Test
    fun `a brand new student gets an empty report, said plainly`() {
        val state = viewModel().uiState.value

        assertFalse("Senza risposte non ci sono dati", state.hasAnyData)
        assertEquals(0, state.summary!!.masteryPercent)
        assertTrue("Il professore deve comunque dire qualcosa", state.professorLine.isNotBlank())
    }

    @Test
    fun `a skill never answered is untouched, not zero`() {
        val state = viewModel().uiState.value
        val skills = state.levels.flatMap { it.modules }.flatMap { it.skills }

        assertTrue(skills.isNotEmpty())
        assertTrue(
            "Mai affrontata e sapere zero sono cose diverse",
            skills.all { it.state == SkillState.UNTOUCHED },
        )
        assertEquals(0, state.summary!!.skillsTouched)
    }

    @Test
    fun `answering moves a skill out of untouched and into the counters`() {
        val skill = firstSkillOfFirstModule()
        drill(skill, times = 4)

        val state = viewModel().uiState.value
        val row = state.levels.flatMap { it.modules }.flatMap { it.skills }.first { it.id == skill }

        assertTrue("Ha risposto: non può restare «mai affrontata»", row.state != SkillState.UNTOUCHED)
        assertTrue("Deve contare i tentativi", row.attempts >= 4)
        assertEquals(1, state.summary!!.skillsTouched)
        assertTrue(state.hasAnyData)
    }

    /**
     * The heart of the thing: one skill drilled to perfection must not carry a module, because
     * the level gate refuses to let a good average hide a subject nobody ever studied.
     */
    @Test
    fun `one strong skill does not make a module pass`() {
        val module = curriculum.levels.first().modules.first()
        drill(module.skills.first(), times = 8)

        val report = viewModel().uiState.value.levels
            .flatMap { it.modules }
            .first { it.id == module.id }

        assertFalse(
            "Una competenza sola non può far passare un modulo intero",
            report.passed,
        )
    }

    @Test
    fun `wrong answers land at the top of what to revise`() {
        val module = curriculum.levels.first().modules.first()
        val weak = module.skills.first()
        drill(weak, times = 3, correct = false)
        if (module.skills.size > 1) drill(module.skills[1], times = 6)

        val toRevise = viewModel().uiState.value.toRevise

        assertTrue("La competenza sbagliata deve comparire", toRevise.any { it.id == weak })
        assertEquals(
            "L'elenco è ordinato dal più fragile",
            weak,
            toRevise.first().id,
        )
    }

    @Test
    fun `the revision list never suggests a level the student cannot open`() {
        drill(firstSkillOfFirstModule(), times = 2, correct = false)

        val state = viewModel().uiState.value
        val unlocked = state.levels.filter { it.unlocked }.map { it.order }.toSet()
        val skillsInUnlocked = state.levels
            .filter { it.order in unlocked }
            .flatMap { it.modules }
            .flatMap { it.skills }
            .map { it.id }
            .toSet()

        assertTrue(
            "Consigliare un ripasso su un livello bloccato è rumore travestito da consiglio",
            state.toRevise.all { it.id in skillsInUnlocked },
        )
    }

    @Test
    fun `the report covers every level of the syllabus`() {
        val state = viewModel().uiState.value
        assertEquals(curriculum.levels.size, state.levels.size)
        assertTrue(state.levels.all { level -> level.modules.isNotEmpty() })
    }

    @Test
    fun `the attendance strip covers a fortnight and ends today`() {
        val state = viewModel().uiState.value
        assertEquals(14, state.recentDays.size)
        assertEquals(timeProvider.today(), state.recentDays.last().date)
    }

    @Test
    fun `studying today shows up as a day present`() {
        drill(firstSkillOfFirstModule(), times = 1)

        val today = viewModel().uiState.value.recentDays.last()
        assertTrue("Il giorno in cui ha studiato deve risultare presente", today.studied)
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
