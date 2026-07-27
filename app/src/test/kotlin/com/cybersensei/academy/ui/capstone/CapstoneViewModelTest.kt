package com.cybersensei.academy.ui.capstone

import android.os.Looper
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.scenario.ChoiceQuality
import com.cybersensei.academy.engine.scenario.Scenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
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
 * The capstone is the only place in the app where the student is not asked a question but
 * put in a situation, so the things worth testing are different: that the consequence is
 * shown before the judgement, that the night is actually recorded against what the student
 * knows, and that a run played badly still ends with a professor talking rather than a score.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class CapstoneViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
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

    private fun viewModel(): CapstoneViewModel =
        CapstoneViewModel(repository, scenario, timeProvider).also { it.awaitLoaded() }

    private fun CapstoneViewModel.awaitLoaded() = settleUntil { uiState.value.title.isNotBlank() }

    private fun CapstoneViewModel.settleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Il capstone non è arrivato allo stato atteso entro ${LOAD_TIMEOUT_MILLIS}ms")
    }

    /** Plays the whole night, always taking the highest-scoring option available. */
    private fun CapstoneViewModel.playBestRun() {
        begin()
        var guard = 0
        while (uiState.value.phase != CapstonePhase.DEBRIEFING) {
            check(guard++ < MAX_SCENES) { "La notte non finisce" }
            val scene = uiState.value.scene ?: break
            val best = scene.choices.maxByOrNull {
                it.effects.containment + it.effects.evidence + it.effects.trust
            }!!
            decide(best.id)
            settleUntil { uiState.value.phase == CapstonePhase.CONSEQUENCE }
            proceed()
            settleUntil {
                uiState.value.phase == CapstonePhase.DECIDING ||
                    uiState.value.phase == CapstonePhase.DEBRIEFING
            }
        }
    }

    @Test
    fun `the briefing comes first, with the professor explaining the rules`() {
        val state = viewModel().uiState.value

        assertEquals(CapstonePhase.BRIEFING, state.phase)
        assertTrue(state.briefing.isNotBlank())
        assertTrue(state.minutes > 0)
        assertNull("Non c'è ancora nessuna scena", state.scene)
    }

    /**
     * The capstone assumes the hard level but does not lock behind it: refusing entry teaches
     * nothing, while saying what it assumes lets the student decide.
     */
    @Test
    fun `a student who has not passed the hard level is warned, not refused`() {
        val state = viewModel().uiState.value

        assertNotNull("Va detto cosa dà per scontato", state.readyWarning)
        assertTrue("Ma deve poter entrare comunque", state.available)
    }

    @Test
    fun `beginning puts the student in the first scene`() {
        val model = viewModel()
        model.begin()
        val state = model.uiState.value

        assertEquals(CapstonePhase.DECIDING, state.phase)
        assertEquals(scenario.startSceneId, state.scene?.id)
        assertTrue("Una scena senza scelte non è una decisione", state.scene!!.choices.size >= 2)
    }

    /** What happened, then whether it was right. Reversing the two makes it a quiz. */
    @Test
    fun `the consequence arrives before the judgement`() {
        val model = viewModel()
        model.begin()
        model.decide("c_spegni")
        model.settleUntil { model.uiState.value.phase == CapstonePhase.CONSEQUENCE }

        val aftermath = model.uiState.value.aftermath!!
        assertTrue("Serve sapere cosa è successo", aftermath.consequence.isNotBlank())
        assertTrue("E poi perché", aftermath.explanation.isNotBlank())
        assertEquals(ChoiceQuality.WRONG, aftermath.quality)
    }

    @Test
    fun `a decision is measured against the skills it exercises`() {
        val model = viewModel()
        model.begin()
        val skills = model.uiState.value.scene!!.choices.first { it.id == "c_isola" }.skills
        model.decide("c_isola")
        model.settleUntil { model.uiState.value.phase == CapstonePhase.CONSEQUENCE }

        val recorded = runBlocking { repository.allMastery() }.map { it.skillId }
        assertTrue(
            "Il capstone deve alimentare la pagella come le interrogazioni: $recorded",
            skills.all { it in recorded },
        )
    }

    @Test
    fun `a bad early decision leads somewhere different`() {
        val sleeping = viewModel().apply {
            begin()
            decide("c_password")
            settleUntil { uiState.value.phase == CapstonePhase.CONSEQUENCE }
            proceed()
            settleUntil { uiState.value.phase == CapstonePhase.DECIDING }
        }
        val isolating = viewModel().apply {
            begin()
            decide("c_isola")
            settleUntil { uiState.value.phase == CapstonePhase.CONSEQUENCE }
            proceed()
            settleUntil { uiState.value.phase == CapstonePhase.DECIDING }
        }

        assertFalse(
            "La storia deve davvero cambiare",
            sleeping.uiState.value.scene?.id == isolating.uiState.value.scene?.id,
        )
    }

    @Test
    fun `the night ends in a debriefing that walks back through every decision`() {
        val model = viewModel()
        model.playBestRun()
        val verdict = model.uiState.value.verdict!!

        assertEquals(CapstonePhase.DEBRIEFING, model.uiState.value.phase)
        assertTrue(verdict.title.isNotBlank())
        assertTrue(verdict.decisions.isNotEmpty())
        assertTrue(
            "Ogni decisione rivista deve avere il suo perché",
            verdict.decisions.all { it.explanation.isNotBlank() },
        )
    }

    @Test
    fun `finishing the night is written down and earns the badge`() {
        val model = viewModel()
        model.playBestRun()

        val completed = runBlocking { repository.hasCompletedCapstone() }
        val badges = runBlocking { repository.badgesHeld() }
        assertTrue("La notte va registrata", completed)
        assertTrue("Chi arriva al debriefing si è guadagnato il segno", "notte_dell_incidente" in badges)
    }

    @Test
    fun `a stale tap cannot end someone's run`() {
        val model = viewModel()
        model.begin()
        val before = model.uiState.value

        model.decide("scelta_inesistente")
        assertEquals(before.phase, model.uiState.value.phase)
        assertEquals(before.scene?.id, model.uiState.value.scene?.id)
    }

    @Test
    fun `replaying starts from the briefing again`() {
        val model = viewModel()
        model.playBestRun()
        model.restart()

        val state = model.uiState.value
        assertEquals(CapstonePhase.BRIEFING, state.phase)
        assertNull(state.verdict)
        assertNull(state.scene)
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val MAX_SCENES = 100
    }
}
