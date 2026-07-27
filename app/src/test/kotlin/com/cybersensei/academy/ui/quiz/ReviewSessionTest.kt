package com.cybersensei.academy.ui.quiz

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.cybersensei.academy.core.common.FixedTimeProvider
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.di.TimeModule
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.DailyBudget
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.scheduler.ReviewScheduler
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.ui.navigation.Routes
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import java.time.LocalDateTime
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
 * Reviews are the loop the whole scheduler exists for, and for several phases the app
 * counted them in three screens without offering any way to do one — the professor told the
 * student off for a debt they could not pay.
 *
 * What is worth holding here is that the session is chosen by the scheduler and not by
 * subject, that it never asks about material the student has not unlocked, and that it stops
 * at the time the student said they had.
 */
@HiltAndroidTest
@UninstallModules(TimeModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class ReviewSessionTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * Spaced repetition only does anything once a day has passed, so a fixed clock is not a
     * convenience here — without it nothing is ever due and the feature cannot be tested at
     * all.
     */
    @BindValue
    @JvmField
    val clock: TimeProvider = FixedTimeProvider(
        LocalDateTime.of(2026, 3, 2, 10, 0).atZone(java.time.ZoneId.of("Europe/Rome")).toInstant(),
    )

    private val fixedClock: FixedTimeProvider get() = clock as FixedTimeProvider

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var tutor: TutorEngine
    @Inject lateinit var scheduler: ReviewScheduler

    @Before
    fun setUp() {
        hiltRule.inject()
        enrol(DailyBudget.NORMAL)
    }

    /** Moves the clock on, which is the only thing that makes a review fall due. */
    private fun tomorrow() = fixedClock.set(LocalDateTime.of(2026, 3, 3, 10, 0))

    private fun enrol(budget: DailyBudget) {
        runBlocking {
            repository.saveProfile(
                StudentProfile(
                    name = "Francesco",
                    birthDate = LocalDate.of(1990, 11, 3),
                    enrolledOn = LocalDate.of(2026, 1, 10),
                    ethicalPactSigned = true,
                    dailyBudget = budget,
                ),
            )
        }
    }

    /** Answers [skill] wrongly, which is what puts it back in the queue for tomorrow. */
    private fun miss(skill: String) = runBlocking {
        repository.recordAnswer(
            skillId = skill,
            correct = false,
            confidence = Confidence.SURE,
            responseTime = 20.seconds,
            expectedTime = 40.seconds,
            misconceptionLabel = "prova",
        )
    }

    private fun reviewSession(): QuizViewModel = QuizViewModel(
        repository = repository,
        curriculum = curriculum,
        tutor = tutor,
        reviewScheduler = scheduler,
        timeProvider = clock,
        // No module argument: that is what makes it a review.
        savedStateHandle = SavedStateHandle(),
    ).also { it.awaitLoaded() }

    private fun QuizViewModel.awaitLoaded() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (uiState.value.isReview) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Il ripasso non si è caricato")
    }

    private fun introSkills(): List<String> = curriculum.levels.first().modules.flatMap { it.skills }

    @Test
    fun `with nothing due the professor says so instead of inventing a session`() {
        val state = reviewSession().uiState.value

        assertTrue(state.isReview)
        assertTrue("Non deve pescare domande a caso", state.questions.isEmpty())
    }

    @Test
    fun `a skill answered wrongly comes back in the review queue`() {
        val skill = introSkills().first()
        miss(skill)
        tomorrow()

        val questions = reviewSession().uiState.value.questions
        assertTrue("Il ripasso deve contenere qualcosa", questions.isNotEmpty())
        assertTrue("Deve contenere proprio l'abilità sbagliata", questions.any { it.skill == skill })
    }

    /** One question per skill: a review checks whether the memory held, it does not drill. */
    @Test
    fun `the session asks about each due skill once`() {
        introSkills().take(3).forEach { miss(it) }
        tomorrow()

        val skills = reviewSession().uiState.value.questions.map { it.skill }
        assertEquals("Nessuna abilità va chiesta due volte", skills.distinct().size, skills.size)
    }

    /**
     * The rule that keeps a review honest: it can only ask about things the student has been
     * taught, whatever the scheduler happens to have in its queue.
     */
    @Test
    fun `a review never asks about a level that is still locked`() {
        val locked = curriculum.levels.last().modules.first().skills.first()
        miss(locked)
        tomorrow()

        val questions = reviewSession().uiState.value.questions
        assertTrue(
            "Ha chiesto di un livello non ancora sbloccato",
            questions.none { it.skill == locked },
        )
    }

    /**
     * The budget is the promise the professor made at enrolment, and a review that runs long
     * is a review the student stops doing. Needs more due skills than a short session holds,
     * so the intro is passed first to open the easy level.
     */
    @Test
    fun `the session stops at the time the student said they had`() {
        passTheIntro()
        val easySkills = curriculum.levels[1].modules.flatMap { it.skills }
        assertTrue("Serve materiale a sufficienza", easySkills.size > 10)
        easySkills.forEach { miss(it) }
        tomorrow()

        val roomy = reviewSession().uiState.value.questions.size
        enrol(DailyBudget.SHORT)
        val tight = reviewSession().uiState.value.questions.size

        assertEquals(
            "Cinque minuti valgono esattamente la capacità dichiarata",
            scheduler.capacityFor(DailyBudget.SHORT.minutes),
            tight,
        )
        assertTrue("E quindici minuti devono valere di più", roomy > tight)
    }

    /** Answers every intro skill well enough, often enough, to open the level after it. */
    private fun passTheIntro() = runBlocking {
        repeat(DRILL_ROUNDS) {
            introSkills().forEach { skill ->
                repository.recordAnswer(
                    skillId = skill,
                    correct = true,
                    confidence = Confidence.SURE,
                    responseTime = 35.seconds,
                    expectedTime = 40.seconds,
                )
            }
        }
        check(1 in repository.unlockedLevels()) {
            "Il livello Facile non si è aperto: il test non può misurare quello che vuole"
        }
    }

    @Test
    fun `a review is not judged like a module exam`() {
        introSkills().take(2).forEach { miss(it) }
        tomorrow()
        val model = reviewSession()

        // Answer everything, whatever the outcome: what matters is the shape of the summary.
        var guard = 0
        while (model.uiState.value.phase != QuizPhase.FINISHED) {
            check(guard++ < MAX_STEPS) { "Il ripasso non finisce" }
            val question = model.uiState.value.question ?: break
            model.onOptionSelected(question.options.first().id)
            model.onAnswerConfirmed()
            model.onConfidenceChosen(Confidence.UNSURE)
            settleUntil(model) { it.phase == QuizPhase.FEEDBACK || it.phase == QuizPhase.FINISHED }
            model.onContinue()
            settleUntil(model) { it.phase != QuizPhase.FEEDBACK }
        }

        val summary = model.uiState.value.summary!!
        assertTrue("Il riepilogo deve sapere di essere un ripasso", summary.isReview)
        assertFalse(
            "Un ripasso non promuove un modulo: non è quello che ha misurato",
            summary.gatePassed,
        )
    }

    private fun settleUntil(model: QuizViewModel, condition: (QuizUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition(model.uiState.value)) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Il ripasso non è arrivato allo stato atteso")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val MAX_STEPS = 60
        const val DRILL_ROUNDS = 8
    }
}
