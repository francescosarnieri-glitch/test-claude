package com.cybersensei.academy.ui.quiz

import android.os.Looper
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.scenario.ChoiceQuality
import com.cybersensei.academy.engine.scenario.Scenario
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.ui.capstone.CapstonePhase
import com.cybersensei.academy.ui.capstone.CapstoneViewModel
import com.cybersensei.academy.ui.navigation.Routes
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The test that should have existed before a single question was written.
 *
 * A student worked out in three screens that the first row was always the right one, and
 * from that moment the whole school measured nothing: mastery, level gates and the report
 * card were all fed by taps that never read a word. The syllabus really does list the
 * correct option first in 242 questions out of 243 — so the guarantee cannot come from the
 * content being careful. It has to come from the position carrying no information at all,
 * and that is what is asserted here, over the entire syllabus rather than a sample.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class AnswerPositionTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var tutor: TutorEngine
    @Inject lateinit var scenario: Scenario
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

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun quizFor(moduleId: String): QuizViewModel = QuizViewModel(
        repository = repository,
        curriculum = curriculum,
        tutor = tutor,
        timeProvider = timeProvider,
        savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_MODULE_ID to moduleId)),
    ).also {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            settle()
            if (it.uiState.value.questions.isNotEmpty()) return@also
            Thread.sleep(POLL_MILLIS)
        }
        error("L'interrogazione non si è caricata")
    }

    /** Where the correct option ends up, once every module of the syllabus is presented. */
    private fun correctPositionsAcrossSyllabus(): List<Int> =
        curriculum.modules.flatMap { module ->
            quizFor(module.id).uiState.value.questions.map { question ->
                question.options.indexOfFirst { it.correct }
            }
        }

    @Test
    fun `the correct answer is not always in the same place`() {
        val positions = correctPositionsAcrossSyllabus()
        assertTrue("Nessuna domanda esaminata", positions.size > 200)

        val distinct = positions.distinct()
        assertTrue(
            "La risposta corretta finisce sempre in posizione ${distinct.firstOrNull()}: " +
                "basterebbe premere sempre lì",
            distinct.size > 1,
        )
    }

    /**
     * Not merely "sometimes elsewhere": every slot has to be used often enough that no
     * position is a better bet than another. With four options, chance alone would put a
     * quarter in each; anything under a tenth is a pattern a student would find.
     */
    @Test
    fun `no position is a better bet than any other`() {
        val positions = correctPositionsAcrossSyllabus()
        val sizes = curriculum.questions.map { it.options.size }
        val slots = sizes.max()

        (0 until slots).forEach { slot ->
            // A slot only exists for questions long enough to have it: almost every question
            // has four options, two have five, so the fifth row is judged on its own handful.
            val eligible = sizes.count { it > slot }
            if (eligible < MINIMUM_SAMPLE) return@forEach

            val count = positions.count { it == slot }
            assertTrue(
                "La posizione $slot ospita la risposta giusta solo $count volte su " +
                    "$eligible domande che ce l'hanno: la distribuzione è sbilanciata",
                count >= eligible / 10,
            )
        }
    }

    /** Same question, same sitting: the rows must not jump around under the student's finger. */
    @Test
    fun `the order is stable while the student is looking at it`() {
        val moduleId = curriculum.modules.first().id
        val first = quizFor(moduleId).uiState.value.questions.map { q -> q.options.map { it.id } }
        val second = quizFor(moduleId).uiState.value.questions.map { q -> q.options.map { it.id } }
        assertEquals(first, second)
    }

    /** Different questions must not be shuffled the same way, or the pattern simply moves. */
    @Test
    fun `two questions of the same module are not shuffled identically`() {
        val module = curriculum.modules.first { it.questions.size > 3 }
        val orders = quizFor(module.id).uiState.value.questions
            .map { q -> q.options.map { it.id } }
        assertTrue(
            "Tutte le domande hanno lo stesso ordine: il difetto si è solo spostato",
            orders.distinct().size > 1,
        )
    }

    // --- The capstone, where the student actually noticed it ----------------------------

    @Test
    fun `the sound decision is not always the first one offered`() {
        val model = CapstoneViewModel(repository, scenario, timeProvider)
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline && model.uiState.value.title.isBlank()) {
            settle()
            Thread.sleep(POLL_MILLIS)
        }

        val positions = mutableListOf<Int>()
        model.begin()
        var guard = 0
        while (model.uiState.value.phase != CapstonePhase.DEBRIEFING) {
            check(guard++ < MAX_SCENES) { "La notte non finisce" }
            val scene = model.uiState.value.scene ?: break
            positions += scene.choices.indexOfFirst { it.quality == ChoiceQuality.RIGHT }
            // Always take the right one, so the run covers the main line of the story.
            model.decide(scene.choices.first { it.quality == ChoiceQuality.RIGHT }.id)
            waitFor(model) { it.phase == CapstonePhase.CONSEQUENCE }
            model.proceed()
            waitFor(model) {
                it.phase == CapstonePhase.DECIDING || it.phase == CapstonePhase.DEBRIEFING
            }
        }

        assertTrue("Nessuna scena giocata", positions.size > 5)
        assertTrue(
            "La decisione giusta è sempre in posizione ${positions.first()}: " +
                "è esattamente il difetto segnalato",
            positions.distinct().size > 1,
        )
    }

    private fun waitFor(model: CapstoneViewModel, condition: (com.cybersensei.academy.ui.capstone.CapstoneUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            settle()
            if (condition(model.uiState.value)) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Il capstone non è arrivato allo stato atteso")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val MAX_SCENES = 100

        /** Below this, a slot has too few questions for its share to mean anything. */
        const val MINIMUM_SAMPLE = 30
    }
}
