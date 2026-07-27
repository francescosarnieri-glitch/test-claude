package com.cybersensei.academy.ui.capstone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.scenario.Choice
import com.cybersensei.academy.engine.scenario.ChoiceQuality
import com.cybersensei.academy.engine.scenario.RunState
import com.cybersensei.academy.engine.scenario.Scenario
import com.cybersensei.academy.engine.scenario.ScenarioEngine
import com.cybersensei.academy.engine.scenario.Scene
import com.cybersensei.academy.engine.scenario.Verdict
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration as JavaDuration
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CapstonePhase { BRIEFING, DECIDING, CONSEQUENCE, DEBRIEFING }

/** What the student sees right after deciding, before the story moves on. */
data class Aftermath(
    val choiceText: String,
    val quality: ChoiceQuality,
    val consequence: String,
    val explanation: String,
    val isLast: Boolean,
)

data class CapstoneUiState(
    val phase: CapstonePhase = CapstonePhase.BRIEFING,
    val title: String = "",
    val subtitle: String = "",
    val briefing: String = "",
    val minutes: Int = 0,
    val readyWarning: String? = null,
    val scene: Scene? = null,
    val sceneNumber: Int = 0,
    val aftermath: Aftermath? = null,
    val verdict: Verdict? = null,
    val available: Boolean = true,
)

/**
 * The capstone: one night, decision by decision.
 *
 * Two things are deliberate. The consequence is shown before the judgement — what happened
 * first, whether it was right second — because that is the order in which reality delivers
 * them and reversing it turns the exercise into a quiz. And every decision is recorded
 * against the skills it exercises, so a night played badly shows up in the report card like
 * any other wrong answer: the capstone measures, it does not just entertain.
 */
@HiltViewModel
class CapstoneViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val scenario: Scenario,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val engine = ScenarioEngine(scenario)
    private var run: RunState = engine.start()
    private var shownAt: Instant = timeProvider.now()

    private val _uiState = MutableStateFlow(CapstoneUiState())
    val uiState: StateFlow<CapstoneUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val passed = repository.passedLevels()
            _uiState.value = CapstoneUiState(
                phase = CapstonePhase.BRIEFING,
                title = scenario.title,
                subtitle = scenario.subtitle,
                briefing = scenario.briefing,
                minutes = scenario.minutes,
                available = scenario.scenes.isNotEmpty(),
                // Not a lock. The capstone assumes the hard level, and saying so is more
                // useful than refusing entry to someone who wants to see what is coming.
                readyWarning = if (HARD_LEVEL in passed) {
                    null
                } else {
                    "Questo è l'esame finale e dà per scontato il livello Difficile. " +
                        "Puoi affrontarlo lo stesso — non ti fermo — ma sappi che alcune " +
                        "decisioni ti sembreranno arbitrarie finché non avrai fatto quei moduli."
                },
            )
        }
    }

    fun begin() {
        run = engine.start()
        shownAt = timeProvider.now()
        _uiState.value = _uiState.value.copy(
            phase = CapstonePhase.DECIDING,
            scene = engine.currentScene(run),
            sceneNumber = 1,
            aftermath = null,
            verdict = null,
        )
    }

    fun decide(choiceId: String) {
        val state = _uiState.value
        if (state.phase != CapstonePhase.DECIDING) return
        val scene = engine.currentScene(run) ?: return
        val choice = scene.choices.firstOrNull { it.id == choiceId } ?: return

        val elapsed = JavaDuration.between(shownAt, timeProvider.now()).toMillis().coerceAtLeast(0)
        run = engine.choose(run, choiceId)

        viewModelScope.launch {
            recordAgainstMastery(choice, elapsed)
            _uiState.value = _uiState.value.copy(
                phase = CapstonePhase.CONSEQUENCE,
                aftermath = Aftermath(
                    choiceText = choice.text,
                    quality = choice.quality,
                    consequence = choice.consequence,
                    explanation = choice.explanation,
                    isLast = run.finished,
                ),
            )
        }
    }

    fun proceed() {
        val state = _uiState.value
        if (state.phase != CapstonePhase.CONSEQUENCE) return

        if (run.finished) {
            finish()
        } else {
            shownAt = timeProvider.now()
            _uiState.value = state.copy(
                phase = CapstonePhase.DECIDING,
                scene = engine.currentScene(run),
                sceneNumber = state.sceneNumber + 1,
                aftermath = null,
            )
        }
    }

    fun restart() {
        run = engine.start()
        _uiState.value = _uiState.value.copy(
            phase = CapstonePhase.BRIEFING,
            scene = null,
            sceneNumber = 0,
            aftermath = null,
            verdict = null,
        )
    }

    private fun finish() {
        viewModelScope.launch {
            repository.completeCapstone(scenario.id)
            repository.awardBadges()
            _uiState.value = _uiState.value.copy(
                phase = CapstonePhase.DEBRIEFING,
                scene = null,
                aftermath = null,
                verdict = engine.debrief(run),
            )
        }
    }

    /**
     * Feeds the decision into the same mastery model the interrogations use.
     *
     * A defensible choice is recorded as correct but unsure: it was not a mistake, and it
     * was not the move either. Confidence is inferred from the quality rather than asked,
     * because stopping a night like this to ask "quanto eri sicuro?" would break the only
     * thing the capstone has that the quizzes do not — the pressure.
     */
    private suspend fun recordAgainstMastery(choice: Choice, elapsedMillis: Long) {
        val correct = choice.quality.isSound
        val confidence = when (choice.quality) {
            ChoiceQuality.RIGHT -> Confidence.SURE
            ChoiceQuality.DEFENSIBLE -> Confidence.UNSURE
            ChoiceQuality.WRONG -> Confidence.UNSURE
            ChoiceQuality.HARMFUL -> Confidence.SURE
        }
        choice.skills.forEach { skill ->
            repository.recordAnswer(
                skillId = skill,
                correct = correct,
                confidence = confidence,
                responseTime = (elapsedMillis / 1000.0).seconds,
                expectedTime = EXPECTED_DECISION_SECONDS.seconds,
                misconceptionLabel = if (correct) null else "capstone_${choice.id}",
            )
        }
    }

    private companion object {
        const val HARD_LEVEL = 3

        /**
         * A decision in an incident is not a quiz answer: reading the situation takes time,
         * and the mastery model must not read deliberation as hesitation.
         */
        const val EXPECTED_DECISION_SECONDS = 75
    }
}
