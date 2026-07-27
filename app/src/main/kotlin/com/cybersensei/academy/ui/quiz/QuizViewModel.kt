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
import com.cybersensei.academy.core.model.Level
import com.cybersensei.academy.engine.mastery.AnswerVerdict
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.mastery.LevelGate
import com.cybersensei.academy.engine.scheduler.ReviewScheduler
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
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

/**
 * Why these questions and not others.
 *
 * The three ways of being asked something share everything that matters — the same options,
 * the same confidence declaration, the same explanation afterwards — and differ only in what
 * is selected and how the result is judged. Naming that difference keeps one screen honest
 * instead of growing three quietly divergent copies of it.
 */
sealed interface QuizSource {
    /** The interrogation at the end of a module. */
    data class Module(val moduleId: String) : QuizSource

    /** Whatever the scheduler says is about to be forgotten. */
    data object Review : QuizSource

    /** The whole level, one question per skill, judged on this sitting alone. */
    data class Exam(val level: Int) : QuizSource
}

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
    val isExam: Boolean = false,
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
    /** Exams only: modules that did not clear the floor in this sitting, weakest first. */
    val weakModules: List<String> = emptyList(),
    val examScorePercent: Int = 0,
    val levelName: String = "",
)

data class QuizUiState(
    val isReview: Boolean = false,
    val isExam: Boolean = false,
    val moduleTitle: String = "",
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    val phase: QuizPhase = QuizPhase.CHOOSING,
    val selectedOptionId: String? = null,
    val confidence: Confidence? = null,
    val feedback: Feedback? = null,
    val summary: QuizSummary? = null,
    /** The professor's word on the exam, spoken once the paper is closed. */
    val examVerdictLine: String = "",
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

    private val source: QuizSource = when {
        savedStateHandle.get<String>(Routes.ARG_MODULE_ID) != null ->
            QuizSource.Module(checkNotNull(savedStateHandle[Routes.ARG_MODULE_ID]))
        savedStateHandle.get<String>(Routes.ARG_LEVEL) != null ->
            QuizSource.Exam(checkNotNull(savedStateHandle.get<String>(Routes.ARG_LEVEL)).toInt())
        else -> QuizSource.Review
    }

    private val _uiState = MutableStateFlow(QuizUiState())
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    private var shownAt = timeProvider.now()
    private var solid = 0
    private var lucky = 0
    private var wrong = 0
    private var earnedPoints = 0

    /** Exams are judged on this sitting, so the answers given here are tallied per module. */
    private val examCorrect = mutableMapOf<String, Int>()
    private val examAsked = mutableMapOf<String, Int>()

    private companion object {
        /** Used only if the profile is somehow missing: the middle of the declared options. */
        const val DEFAULT_BUDGET_MINUTES = 15

        /** Mirrors the unlock gate on purpose: same bar, applied to a single sitting. */
        const val EXAM_PASS_PERCENT = 80
        const val EXAM_MODULE_FLOOR_PERCENT = 60
    }

    init {
        viewModelScope.launch {
            val questions = when (source) {
                is QuizSource.Module -> curriculum.module(source.moduleId)?.questions.orEmpty()
                QuizSource.Review -> questionsDueForReview()
                is QuizSource.Exam -> examQuestions(source.level)
            }
            val studentName = repository.profile()?.name
            // Attempts already made on each skill, so that coming back to a question a second
            // time reshuffles it: otherwise a student memorises a position, not an answer.
            val attempts = repository.masteryFor(questions.map { it.skill })
                .associate { it.skillId to it.attempts }

            _uiState.value = QuizUiState(
                isReview = source is QuizSource.Review,
                isExam = source is QuizSource.Exam,
                moduleTitle = when (source) {
                    is QuizSource.Module -> curriculum.module(source.moduleId)?.title.orEmpty()
                    QuizSource.Review -> "Ripasso"
                    is QuizSource.Exam -> "Esame — ${levelName(source.level)}"
                },
                // The options are reordered per question: the syllabus lists the correct one
                // first almost everywhere, and position must mean nothing.
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
     * The exam: one question per skill of the level, in syllabus order.
     *
     * Complete rather than sampled, because the whole point of an exam is that nothing can be
     * skipped — a level is not passed by being good at most of it. The variant is picked from
     * how much the skill has been practised, so sitting the exam twice is not the same paper.
     */
    private suspend fun examQuestions(level: Int): List<Question> {
        val content = curriculum.level(level) ?: return emptyList()
        val attempts = repository.allMastery().associate { it.skillId to it.attempts }
        return content.modules.flatMap { module ->
            module.skills.mapNotNull { skill ->
                val candidates = module.questions.filter { it.skill == skill }
                if (candidates.isEmpty()) {
                    null
                } else {
                    candidates[(attempts[skill] ?: 0) % candidates.size]
                }
            }
        }
    }

    private fun levelName(level: Int): String =
        Level.fromOrder(level)?.italianName ?: curriculum.level(level)?.title.orEmpty()

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

            if (source is QuizSource.Exam) {
                // Judged on what was answered here, not on accumulated mastery: an exam that
                // reads the record instead of the paper is not an exam.
                val owner = curriculum.moduleOfQuestion(question.id)?.id
                if (owner != null) {
                    examAsked.merge(owner, 1, Int::plus)
                    if (chosen.correct) examCorrect.merge(owner, 1, Int::plus)
                }
            }

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
            if (source is QuizSource.Exam) {
                finishExam(source.level)
                return@launch
            }

            // A review has no module to pass: it is judged on the skills it actually revisited.
            val skills = when (source) {
                is QuizSource.Module -> curriculum.module(source.moduleId)?.skills.orEmpty()
                else -> _uiState.value.questions.map { it.skill }.distinct()
            }
            val mastery = repository.masteryFor(skills)
            val gate = LevelGate.evaluate(skills, mastery)

            _uiState.value = _uiState.value.copy(
                phase = QuizPhase.FINISHED,
                summary = QuizSummary(
                    isReview = source is QuizSource.Review,
                    stillDue = if (source is QuizSource.Review) repository.dueReviews().size else 0,
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

    /**
     * The verdict on an exam, taken from the exam.
     *
     * Two conditions, the same shape as the gate that unlocks levels and for the same reason:
     * a strong average must not be allowed to cover a subject that was never understood. So
     * the overall score has to clear [EXAM_PASS_PERCENT] *and* no single module may fall
     * below [EXAM_MODULE_FLOOR_PERCENT] on this paper.
     */
    private suspend fun finishExam(level: Int) {
        val asked = examAsked.values.sum()
        val correct = examCorrect.values.sum()
        val score = if (asked == 0) 0 else (correct * 100) / asked

        val weakModules = examAsked
            .filter { (moduleId, total) ->
                total > 0 && (examCorrect[moduleId] ?: 0) * 100 / total < EXAM_MODULE_FLOOR_PERCENT
            }
            .keys
            .mapNotNull { curriculum.module(it)?.title }
            .sorted()

        val passed = asked > 0 && score >= EXAM_PASS_PERCENT && weakModules.isEmpty()
        val name = levelName(level)

        repository.recordExam(level, passed, score)

        val line = tutor.speak(
            if (passed) {
                TutorEvent.ExamPassed(name, score)
            } else {
                TutorEvent.ExamFailed(name, weakModules.firstOrNull() ?: "la media")
            },
            repository.snapshot(),
        ).text

        _uiState.value = _uiState.value.copy(
            phase = QuizPhase.FINISHED,
            summary = QuizSummary(
                isExam = true,
                answered = solid + lucky + wrong,
                solid = solid,
                lucky = lucky,
                wrong = wrong,
                experiencePoints = earnedPoints,
                averageMasteryPercent = score,
                weakSkills = emptyList(),
                gatePassed = passed,
                weakModules = weakModules,
                examScorePercent = score,
                levelName = name,
            ),
            examVerdictLine = line,
        )
    }
}
