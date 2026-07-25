package com.cybersensei.academy.ui.quiz

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.curriculum.Question
import com.cybersensei.academy.core.curriculum.QuestionOption
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.mastery.AnswerVerdict
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.mastery.LevelGate
import com.cybersensei.academy.engine.tutor.TutorEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration as JavaDuration
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the student is inside a single question. */
enum class QuizPhase { CHOOSING, DECLARING_CONFIDENCE, FEEDBACK, FINISHED }

data class Feedback(
    val verdict: AnswerVerdict,
    val professorLine: String,
    val chosen: QuestionOption,
    val correct: QuestionOption,
    val explanation: String,
    val realWorld: String?,
    val controlQuestion: String?,
    val experiencePoints: Int,
    val masteryPercent: Int,
)

data class QuizSummary(
    val answered: Int,
    val solid: Int,
    val lucky: Int,
    val wrong: Int,
    val experiencePoints: Int,
    val averageMasteryPercent: Int,
    val weakSkills: List<String>,
    val gatePassed: Boolean,
)

data class QuizUiState(
    val moduleTitle: String = "",
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    val phase: QuizPhase = QuizPhase.CHOOSING,
    val selectedOptionId: String? = null,
    val confidence: Confidence? = null,
    val feedback: Feedback? = null,
    val summary: QuizSummary? = null,
) {
    val question: Question? get() = questions.getOrNull(index)
    val progress: Float get() = if (questions.isEmpty()) 0f else (index + 1f) / questions.size
    val isLast: Boolean get() = index >= questions.lastIndex
}

@HiltViewModel
class QuizViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val tutor: TutorEngine,
    private val timeProvider: TimeProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val moduleId: String = checkNotNull(savedStateHandle["moduleId"])

    private val _uiState = MutableStateFlow(QuizUiState())
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    private var shownAt = timeProvider.now()
    private var solid = 0
    private var lucky = 0
    private var wrong = 0
    private var earnedPoints = 0

    init {
        val module = curriculum.module(moduleId)
        _uiState.value = QuizUiState(
            moduleTitle = module?.title.orEmpty(),
            // Deterministic order for now; Fase 3 will let the scheduler pick what is due.
            questions = module?.questions.orEmpty(),
        )
        shownAt = timeProvider.now()
    }

    fun onOptionSelected(optionId: String) {
        if (_uiState.value.phase != QuizPhase.CHOOSING) return
        _uiState.value = _uiState.value.copy(selectedOptionId = optionId)
    }

    /** The answer is locked in, but the student does not learn the outcome yet. */
    fun onAnswerConfirmed() {
        val state = _uiState.value
        if (state.phase != QuizPhase.CHOOSING || state.selectedOptionId == null) return
        _uiState.value = state.copy(phase = QuizPhase.DECLARING_CONFIDENCE)
    }

    fun onConfidenceChosen(confidence: Confidence) {
        val state = _uiState.value
        if (state.phase != QuizPhase.DECLARING_CONFIDENCE) return
        val question = state.question ?: return
        val chosen = question.options.first { it.id == state.selectedOptionId }

        viewModelScope.launch {
            val elapsed = JavaDuration.between(shownAt, timeProvider.now())
                .toMillis()
                .coerceAtLeast(0)

            val update = repository.recordAnswer(
                skillId = question.skill,
                correct = chosen.correct,
                confidence = confidence,
                responseTime = (elapsed / 1000.0).seconds,
                expectedTime = question.expectedSeconds.seconds,
                misconceptionLabel = chosen.misconception,
            )

            when (update.verdict) {
                AnswerVerdict.SOLID -> solid++
                AnswerVerdict.SUSPECTED_LUCK, AnswerVerdict.CORRECT_BUT_FRAGILE -> lucky++
                else -> wrong++
            }
            earnedPoints += update.experiencePoints

            val line = tutor.reactToAnswer(
                verdict = update.verdict,
                skillLabel = question.skill.replace('_', ' '),
                student = repository.snapshot(),
                misconceptionLabel = chosen.rebuttal ?: chosen.misconception,
            ).text

            _uiState.value = _uiState.value.copy(
                phase = QuizPhase.FEEDBACK,
                confidence = confidence,
                feedback = Feedback(
                    verdict = update.verdict,
                    professorLine = line,
                    chosen = chosen,
                    correct = question.correctOption,
                    explanation = question.explanation,
                    realWorld = question.realWorld,
                    controlQuestion = question.controlQuestion,
                    experiencePoints = update.experiencePoints,
                    masteryPercent = update.mastery.percent,
                ),
            )
        }
    }

    fun onContinue() {
        val state = _uiState.value
        if (state.phase != QuizPhase.FEEDBACK) return
        if (state.isLast) {
            finish()
        } else {
            shownAt = timeProvider.now()
            _uiState.value = state.copy(
                index = state.index + 1,
                phase = QuizPhase.CHOOSING,
                selectedOptionId = null,
                confidence = null,
                feedback = null,
            )
        }
    }

    private fun finish() {
        viewModelScope.launch {
            val module = curriculum.module(moduleId)
            val skills = module?.skills.orEmpty()
            val mastery = repository.masteryFor(skills)
            val gate = LevelGate.evaluate(skills, mastery)

            _uiState.value = _uiState.value.copy(
                phase = QuizPhase.FINISHED,
                summary = QuizSummary(
                    answered = solid + lucky + wrong,
                    solid = solid,
                    lucky = lucky,
                    wrong = wrong,
                    experiencePoints = earnedPoints,
                    averageMasteryPercent = gate.averagePercent,
                    weakSkills = gate.weakSkills.map { it.skillId.replace('_', ' ') },
                    gatePassed = gate.passed,
                ),
            )
        }
    }
}
