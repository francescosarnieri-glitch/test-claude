package com.cybersensei.academy.ui.quiz

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.scheduler.ReviewScheduler
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.ui.navigation.Routes
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
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
 * The exam exists to make passing a level an event rather than a number quietly crossing a
 * threshold, and to be the one assessment judged on a single sitting instead of on the
 * accumulated record.
 *
 * The rule worth defending is the second condition: a strong average must not be able to
 * carry a module the student never understood. Everything else here follows from that.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class LevelExamTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var tutor: TutorEngine
    @Inject lateinit var scheduler: ReviewScheduler
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

    private fun exam(level: Int): QuizViewModel = QuizViewModel(
        repository = repository,
        curriculum = curriculum,
        tutor = tutor,
        reviewScheduler = scheduler,
        timeProvider = timeProvider,
        savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_LEVEL to level.toString())),
    ).also { it.awaitLoaded() }

    private fun QuizViewModel.awaitLoaded() = settleUntil(this) { it.questions.isNotEmpty() }

    private fun settleUntil(model: QuizViewModel, condition: (QuizUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition(model.uiState.value)) return
            Thread.sleep(POLL_MILLIS)
        }
        error("L'esame non è arrivato allo stato atteso")
    }

    /**
     * Sits the whole paper. [wrongIn] names modules to deliberately fail, so a test can
     * produce a student who is strong overall and hopeless in one subject.
     */
    private fun sit(model: QuizViewModel, wrongIn: Set<String> = emptySet()) {
        var guard = 0
        while (model.uiState.value.phase != QuizPhase.FINISHED) {
            check(guard++ < MAX_STEPS) { "L'esame non finisce" }
            val question = model.uiState.value.question ?: break
            val module = curriculum.moduleOfQuestion(question.id)!!
            val option = if (module.id in wrongIn) {
                question.options.first { !it.correct }
            } else {
                question.options.first { it.correct }
            }
            model.onOptionSelected(option.id)
            model.onAnswerConfirmed()
            model.onConfidenceChosen(Confidence.SURE)
            settleUntil(model) { it.phase == QuizPhase.FEEDBACK || it.phase == QuizPhase.FINISHED }
            model.onContinue()
            settleUntil(model) { it.phase != QuizPhase.FEEDBACK }
        }
    }

    @Test
    fun `the exam covers every skill of the level exactly once`() {
        val level = curriculum.levels.first()
        val expected = level.modules.flatMap { it.skills }

        val asked = exam(level.level).uiState.value.questions.map { it.skill }

        assertEquals("Un esame non campiona: chiede tutto", expected.sorted(), asked.sorted())
        assertEquals("E niente due volte", asked.distinct().size, asked.size)
    }

    @Test
    fun `answering everything correctly passes the level`() {
        val level = curriculum.levels.first().level
        val model = exam(level)
        sit(model)

        val summary = model.uiState.value.summary!!
        assertTrue(summary.isExam)
        assertTrue("Tutto giusto deve bastare", summary.gatePassed)
        assertEquals(100, summary.examScorePercent)
        assertTrue("Il professore deve dire la sua", model.uiState.value.examVerdictLine.isNotBlank())
    }

    /**
     * The heart of it: strong everywhere except one module, which on a plain average would
     * still pass comfortably. It must not.
     */
    @Test
    fun `one failed module stops the exam however good the average is`() {
        val level = curriculum.levels.first { it.modules.size > 3 }
        val weakest = level.modules.first()

        val model = exam(level.level)
        sit(model, wrongIn = setOf(weakest.id))
        val summary = model.uiState.value.summary!!

        assertTrue(
            "La media doveva restare alta: ${summary.examScorePercent}%",
            summary.examScorePercent >= 70,
        )
        assertFalse("Ma un modulo a zero deve fermare l'esame", summary.gatePassed)
        assertTrue(
            "E va detto quale, altrimenti il voto non è utilizzabile",
            summary.weakModules.contains(weakest.title),
        )
    }

    @Test
    fun `a failed exam is written down, not only a passed one`() {
        val level = curriculum.levels.first { it.modules.size > 3 }
        sit(exam(level.level), wrongIn = level.modules.map { it.id }.toSet())

        val passed = runBlocking { repository.examPassedLevels() }
        val events = runBlocking { repository.recentStudyEvents() }

        assertFalse("Non può risultare superato", level.level in passed)
        assertTrue(
            "Il tentativo fallito deve restare a registro",
            events.any { it.label.startsWith("esame:${level.level}:") },
        )
    }

    @Test
    fun `passing is remembered for the level that was sat`() {
        val level = curriculum.levels.first().level
        sit(exam(level))

        assertTrue(level in runBlocking { repository.examPassedLevels() })
    }

    /** Sitting it again must not be the same paper, or the second attempt tests memory of it. */
    @Test
    fun `a second sitting is not the identical paper`() {
        val level = curriculum.levels.first { level ->
            level.modules.any { module -> module.skills.any { s -> module.questions.count { it.skill == s } > 1 } }
        }
        val first = exam(level.level).uiState.value.questions.map { it.id }
        sit(exam(level.level))
        val second = exam(level.level).uiState.value.questions.map { it.id }

        assertTrue("Le domande devono poter cambiare fra un tentativo e l'altro", first != second)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 20L
        const val MAX_STEPS = 120
    }
}
