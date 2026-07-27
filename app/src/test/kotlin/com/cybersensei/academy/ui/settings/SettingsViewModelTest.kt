package com.cybersensei.academy.ui.settings

import android.os.Looper
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.DailyBudget
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.core.model.TutorTone
import com.cybersensei.academy.engine.mastery.Confidence
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings are mostly harmless except for one thing, and that one thing is irreversible.
 *
 * So what is held here is that the reset takes two deliberate steps, that it can be backed
 * out of, and that when it does run it really removes everything — the promise that nothing
 * leaves the device only means something if the student can also make it leave the device.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class SettingsViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var timeProvider: TimeProvider

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            repository.saveProfile(
                StudentProfile(
                    name = "Francesco",
                    nickname = "Franci",
                    birthDate = LocalDate.of(1990, 11, 3),
                    enrolledOn = LocalDate.of(2026, 1, 10),
                    ethicalPactSigned = true,
                ),
            )
            // Some history, so the reset has something real to destroy.
            repository.recordAnswer(
                skillId = curriculum.levels.first().modules.first().skills.first(),
                correct = true,
                confidence = Confidence.SURE,
                responseTime = 30.seconds,
                expectedTime = 40.seconds,
            )
        }
    }

    private fun viewModel(): SettingsViewModel =
        SettingsViewModel(repository, curriculum, timeProvider).also { it.awaitLoaded() }

    private fun SettingsViewModel.awaitLoaded() = settleUntil { uiState.value.profile != null }

    private fun SettingsViewModel.settleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Le impostazioni non sono arrivate allo stato atteso")
    }

    @Test
    fun `changing the tone is saved, not merely shown`() {
        val model = viewModel()
        model.onToneChosen(TutorTone.STRICT)
        model.settleUntil { runBlocking { repository.profile()?.tone } == TutorTone.STRICT }

        assertEquals(TutorTone.STRICT, model.uiState.value.profile?.tone)
        assertEquals(TutorTone.STRICT, runBlocking { repository.profile()?.tone })
    }

    @Test
    fun `changing the daily budget is saved`() {
        val model = viewModel()
        model.onBudgetChosen(DailyBudget.INTENSE)
        model.settleUntil { runBlocking { repository.profile()?.dailyBudget } == DailyBudget.INTENSE }

        assertEquals(DailyBudget.INTENSE, runBlocking { repository.profile()?.dailyBudget })
    }

    /** An empty nickname would leave the professor addressing a gap in his own sentence. */
    @Test
    fun `an emptied nickname falls back to the name`() {
        val model = viewModel()
        model.onNicknameChanged("   ")
        model.settleUntil { runBlocking { repository.profile()?.nickname } == "Francesco" }

        assertEquals("Francesco", model.uiState.value.profile?.nickname)
    }

    @Test
    fun `the reset needs a second, deliberate step`() {
        val model = viewModel()
        assertFalse(model.uiState.value.confirmingReset)

        model.askToReset()
        assertTrue(model.uiState.value.confirmingReset)
        assertNotNull(
            "Chiedere il reset non deve cancellare niente",
            runBlocking { repository.profile() },
        )
    }

    @Test
    fun `the reset can be backed out of`() {
        val model = viewModel()
        model.askToReset()
        model.cancelReset()

        assertFalse(model.uiState.value.confirmingReset)
        assertNotNull(runBlocking { repository.profile() })
    }

    /** Before asking, the professor says what goes — in the student's own numbers. */
    @Test
    fun `what would be lost is counted, not described vaguely`() {
        val lost = viewModel().uiState.value.lost!!

        assertTrue("Deve conoscere le competenze misurate", lost.skillsMeasured >= 1)
        assertTrue("E l'esperienza accumulata", lost.experiencePoints > 0)
        assertEquals(
            "E da quanto lo studente è iscritto",
            LocalDate.of(2026, 1, 10).let { java.time.temporal.ChronoUnit.DAYS.between(it, timeProvider.today()) },
            lost.daysEnrolled,
        )
    }

    @Test
    fun `confirming really forgets everything`() {
        val model = viewModel()
        model.askToReset()
        model.confirmReset()
        model.settleUntil { runBlocking { repository.profile() } == null }

        runBlocking {
            assertNull("Il profilo deve sparire", repository.profile())
            assertTrue("E la padronanza con lui", repository.allMastery().isEmpty())
            assertTrue("E i progressi", repository.completedLessonIds().isEmpty())
            assertTrue("E i riconoscimenti", repository.badgesHeld().isEmpty())
            assertTrue("E il diario", repository.recentStudyEvents().isEmpty())
            assertEquals(0, repository.stats().experiencePoints)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
