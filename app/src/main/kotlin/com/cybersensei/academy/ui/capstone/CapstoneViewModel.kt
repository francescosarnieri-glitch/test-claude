package com.cybersensei.academy.ui.capstone

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.common.inPresentationOrder
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.scenario.Choice
import com.cybersensei.academy.engine.scenario.ChoiceQuality
import com.cybersensei.academy.engine.scenario.RunState
import com.cybersensei.academy.engine.scenario.Scenario
import com.cybersensei.academy.engine.scenario.ScenarioEngine
import com.cybersensei.academy.engine.scenario.ScenarioLibrary
import com.cybersensei.academy.engine.scenario.Scene
import com.cybersensei.academy.engine.scenario.Verdict
import com.cybersensei.academy.ui.navigation.Routes
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
    private val library: ScenarioLibrary,
    private val timeProvider: TimeProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Which case this is. An unknown id falls back to an empty scenario rather than throwing:
     * a stale link must leave the student on a screen that explains itself, not on a crash.
     */
    private val scenario: Scenario = savedStateHandle.get<String>(Routes.ARG_CASE_ID)
        ?.let(library::case)
        ?: library.finale
        ?: EMPTY_CASE

    private val engine = ScenarioEngine(scenario)
    private var run: RunState = engine.start()
    private var shownAt: Instant = timeProvider.now()
    private var studentName: String? = null

    /** Counts replays, so a second night does not present the choices in the same order. */
    private var attempt = 0

    private val _uiState = MutableStateFlow(CapstoneUiState())
    val uiState: StateFlow<CapstoneUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val passed = repository.passedLevels()
            studentName = repository.profile()?.name
            _uiState.value = CapstoneUiState(
                phase = CapstonePhase.BRIEFING,
                title = scenario.title,
                subtitle = scenario.subtitle,
                briefing = scenario.briefing,
                minutes = scenario.minutes,
                available = scenario.scenes.isNotEmpty(),
                // Not a lock, and only for the final night: every other case is built out of
                // material the student has already read, so warning them would be nonsense.
                readyWarning = if (!scenario.finale || scenario.level in passed) {
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
        attempt++
        _uiState.value = _uiState.value.copy(
            phase = CapstonePhase.DECIDING,
            scene = engine.currentScene(run)?.presented(),
            sceneNumber = 1,
            aftermath = null,
            verdict = null,
        )
    }

    /**
     * The scene as the student sees it, with the choices reordered.
     *
     * The script lists the sound decision first in every scene, which is fine for writing it
     * and fatal for playing it: a student worked out in three screens that the top row was
     * always right and stopped reading. Position must carry no information. The engine still
     * resolves choices by id, so only the presentation moves.
     */
    private fun Scene.presented(): Scene = copy(
        choices = choices.inPresentationOrder(studentName, id, attempt),
    )

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
                scene = engine.currentScene(run)?.presented(),
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
        /**
         * What a case with no content looks like, used when an id does not resolve.
         *
         * `available = false` on the state is what the screen actually reads; this exists so
         * that nothing between here and there has to cope with a null scenario.
         */
        val EMPTY_CASE = Scenario(
            id = "assente",
            title = "Caso non disponibile",
            subtitle = "Non riesco ad aprire il copione di questa esercitazione.",
            briefing = "",
            minutes = 0,
            level = 0,
            startSceneId = "",
            scenes = emptyList(),
            debriefing = com.cybersensei.academy.engine.scenario.Debriefing(
                opening = "",
                bands = emptyList(),
                closing = "",
            ),
        )

        /**
         * A decision in an incident is not a quiz answer: reading the situation takes time,
         * and the mastery model must not read deliberation as hesitation.
         */
        const val EXPECTED_DECISION_SECONDS = 75
    }
}
