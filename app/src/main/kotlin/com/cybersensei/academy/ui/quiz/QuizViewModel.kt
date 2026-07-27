package com.cybersensei.academy.ui.quiz

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.common.inPresentationOrder
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.curriculum.Question
import com.cybersensei.academy.core.curriculum.QuestionOption
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.mastery.AnswerVerdict
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.mastery.LevelGate
import com.cybersensei.academy.engine.scheduler.ReviewScheduler
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.ui.navigation.Routes
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
    val isReview: Boolean = false,
    /** Reviews still waiting after this session — the budget rarely covers them all. */
    val stillDue: Int = 0,
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
    val isReview: Boolean = false,
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
    private val reviewScheduler: ReviewScheduler,
    private val timeProvider: TimeProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Absent on the review route: a review is the same interrogation, chosen differently. */
    private val moduleId: String? = savedStateHandle[Routes.ARG_MODULE_ID]

    private val _uiState = MutableStateFlow(QuizUiState())
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    private var shownAt = timeProvider.now()
    private var solid = 0
    private var lucky = 0
    private var wrong = 0
    private var earnedPoints = 0

    private companion object {
        /** Used only if the profile is somehow missing: the middle of the declared options. */
        const val DEFAULT_BUDGET_MINUTES = 15
    }

    init {
        viewModelScope.launch {
            val profile = repository.profile()
            val module = moduleId?.let(curriculum::module)
            val questions = if (moduleId == null) questionsDueForReview() else module?.questions.orEmpty()
            val studentName = profile?.name
            // Attempts already made on each skill, so that coming back to a question a second
            // time reshuffles it: otherwise a student memorises a position, not an answer.
            val attempts = repository.masteryFor(questions.map { it.skill })
                .associate { it.skillId to it.attempts }

            _uiState.value = QuizUiState(
                isReview = moduleId == null,
                moduleTitle = if (moduleId == null) "Ripasso" else module?.title.orEmpty(),
                // Deterministic order for now; Fase 3 will let the scheduler pick what is due.
                // The options, however, are reordered per question — the syllabus lists the
                // correct one first almost everywhere, and position must mean nothing.
                questions = questions.map { question ->
                    question.copy(
                        options = question.options.inPresentationOrder(
                            studentName,
                            question.id,
                            attempts[question.skill] ?: 0,
                        ),
                    )
                },
            )
            shownAt = timeProvider.now()
        }
    }

    /**
     * What the scheduler says is due, turned into questions.
     *
     * Three rules decide the session. Only skills from levels the student has unlocked, so a
     * review never asks about material they have not been taught. One question per skill,
     * because the point is to check whether the memory held, not to drill. And the number of
     * them is capped by the daily budget the student declared at enrolment — a review that
     * outstays its welcome is a review that stops happening.
     */
    private suspend fun questionsDueForReview(): List<Question> {
        val due = repository.dueReviews()
        if (due.isEmpty()) return emptyList()

        val unlocked = repository.unlockedLevels()
        val teachable = curriculum.levels
            .filter { it.level in unlocked }
            .flatMap { level -> level.modules.flatMap { it.questions } }
            .groupBy { it.skill }

        val budget = repository.profile()?.dailyBudget?.minutes ?: DEFAULT_BUDGET_MINUTES
        val capacity = reviewScheduler.capacityFor(budget)

        return due.mapNotNull { item ->
            val candidates = teachable[item.skillId].orEmpty()
            if (candidates.isEmpty()) return@mapNotNull null
            // Rotate through the variants so a returning skill is not the identical question:
            // recognising a prompt is not the same as remembering the answer.
            val attempts = repository.masteryFor(listOf(item.skillId)).firstOrNull()?.attempts ?: 0
            candidates[attempts % candidates.size]
        }.take(capacity)
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
            // A review has no module to pass: it is judged on the skills it actually revisited.
            val skills = if (moduleId == null) {
                _uiState.value.questions.map { it.skill }.distinct()
            } else {
                curriculum.module(moduleId)?.skills.orEmpty()
            }
            val mastery = repository.masteryFor(skills)
            val gate = LevelGate.evaluate(skills, mastery)

            _uiState.value = _uiState.value.copy(
                phase = QuizPhase.FINISHED,
                summary = QuizSummary(
                    isReview = moduleId == null,
                    stillDue = if (moduleId == null) repository.dueReviews().size else 0,
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
