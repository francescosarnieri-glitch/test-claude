package com.cybersensei.academy.ui.path

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.Level
import com.cybersensei.academy.engine.regole.Palestra
import com.cybersensei.academy.engine.scenario.ScenarioLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LessonRow(
    val id: String,
    val title: String,
    val minutes: Int,
    val done: Boolean,
    /**
     * Read to the end, but the interrogation is still missing.
     *
     * Shown as its own state because the alternative is worse than it looks: a student who
     * read four screens and left before the test would find the lesson exactly as he left it,
     * with no trace of the work he did, and would reasonably conclude the app lost it.
     */
    val read: Boolean = false,
    /**
     * Whether the student can open it at all.
     *
     * A lesson is open when everything before it in the level is done, and stays open
     * afterwards — re-reading is never a way of skipping ahead.
     */
    val unlocked: Boolean = true,
)

data class ModuleRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val lessons: List<LessonRow>,
    val questionCount: Int,
    val masteryPercent: Int,
    /** False while the module is still behind the one the student is on. */
    val unlocked: Boolean = true,
    /**
     * The interrogation opens when the module's own lessons have been read.
     *
     * Same reasoning as the level exam one screen below: an interrogation sat before the
     * material measures nothing, and teaches the student that the interrogations are noise.
     */
    val quizUnlocked: Boolean = true,
)

/**
 * One case: a small branching story built only out of material already taught.
 *
 * [opensWith] names the modules that open it, so the row can say what to do rather than only
 * that it is shut — a padlock with no instructions is just a refusal.
 */
data class CaseRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val minutes: Int,
    val unlocked: Boolean,
    val played: Boolean,
    val opensWith: List<String>,
)

/**
 * One rule-writing exercise on the path.
 *
 * Same lock rule as everything else in this school: it opens when the module it is built on
 * has been studied, and [opensWith] names that module so a shut door says what to do.
 */
data class ExerciseRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val unlocked: Boolean,
    val solved: Boolean,
    val opensWith: List<String>,
)

data class LevelRow(
    val order: Int,
    val name: String,
    /** Come si legge in cima al blocco: «1 · Le fondamenta». */
    val label: String,
    val subtitle: String,
    /** There is material for this level. */
    val hasContent: Boolean,
    /** The student has earned the right to open it. */
    val unlocked: Boolean,
    val passed: Boolean,
    /** Every lesson of the level is done, so the exam can be sat. */
    val lessonsFinished: Boolean,
    /** The exam has been sat and passed, which is not the same as the level unlocking. */
    val examPassed: Boolean,
    val modules: List<ModuleRow>,
    /** The rule-writing exercises built on this level's material. */
    val exercises: List<ExerciseRow> = emptyList(),
    /** The cases built on this level's material. */
    val cases: List<CaseRow> = emptyList(),
) {
    val available: Boolean get() = hasContent && unlocked

    val lockedReason: String? get() = when {
        !hasContent -> "Non ancora disponibile — arriva in una fase successiva."
        !unlocked -> "Si apre quando avrai superato il livello precedente."
        else -> null
    }
}

data class PathUiState(val levels: List<LevelRow> = emptyList())

/**
 * The path, and the one rule that governs it: you open the next thing, not any thing.
 *
 * The classroom always proposed the right next lesson; the path let the student pick any of
 * the hundred and eight, in any order. Two screens disagreeing about the order is worse than
 * either order being wrong — the student who starts from the middle finds a lesson written on
 * top of four he has not read, concludes the school is badly written, and is not mistaken from
 * where he is standing.
 *
 * So the padlock is used here too, with the same meaning it has everywhere else in the app: a
 * lesson opens when everything before it in the level is done, a module's interrogation opens
 * when the module's lessons are read, the level exam when the level's are, and the next level
 * when this one is passed. Four gates, one sentence each, all saying what opens them.
 */
@HiltViewModel
class PathViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val cases: ScenarioLibrary,
    private val palestra: Palestra,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PathUiState())
    val uiState: StateFlow<PathUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val done = repository.completedLessonIds()
            val mastery = repository.allMastery().associateBy { it.skillId }
            val unlocked = repository.unlockedLevels()
            val passed = repository.passedLevels()
            val examsPassed = repository.examPassedLevels()
            val casesPlayed = repository.completedCases()
            val exercisesSolved = repository.solvedExercises()
            val read = repository.readLessonIds()
            val modulesRead = curriculum.modules
                .filter { module -> module.lessons.isNotEmpty() && module.lessons.all { it.id in done } }
                .map { it.id }
                .toSet()

            val rows = Level.entries.map { level ->
                val content = curriculum.level(level.order)
                val lessons = content?.modules.orEmpty().flatMap { it.lessons }
                // The frontier: everything read, plus the one lesson that comes next. It is
                // the same choice the classroom makes when the student taps «cominciamo», so
                // the two screens can never disagree about what to study now.
                val next = lessons.firstOrNull { it.id !in done }?.id
                val openLessons = lessons.map { it.id }.filter { it in done || it == next }.toSet()
                LevelRow(
                    order = level.order,
                    name = level.italianName,
                    label = level.label,
                    subtitle = level.subtitle,
                    // A level with no material yet is shown, but honestly marked as absent.
                    hasContent = content != null,
                    unlocked = level.order in unlocked,
                    passed = level.order in passed,
                    lessonsFinished = lessons.isNotEmpty() && lessons.all { it.id in done },
                    examPassed = level.order in examsPassed,
                    modules = content?.modules.orEmpty().map { module ->
                        val skills = module.skills
                        val average = if (skills.isEmpty()) {
                            0.0
                        } else {
                            skills.sumOf { mastery[it]?.value ?: 0.0 } / skills.size
                        }
                        val rowsOfLessons = module.lessons.map { lesson ->
                            LessonRow(
                                id = lesson.id,
                                title = lesson.title,
                                minutes = lesson.minutes,
                                done = lesson.id in done,
                                read = lesson.id in read && lesson.id !in done,
                                unlocked = lesson.id in openLessons,
                            )
                        }
                        ModuleRow(
                            id = module.id,
                            title = module.title,
                            subtitle = module.subtitle,
                            lessons = rowsOfLessons,
                            questionCount = module.questions.size,
                            masteryPercent = (average * 100).toInt(),
                            unlocked = rowsOfLessons.any { it.unlocked },
                            quizUnlocked = rowsOfLessons.isNotEmpty() && rowsOfLessons.all { it.done },
                        )
                    },
                    exercises = palestra.perLivello(level.order).map { esercizio ->
                        ExerciseRow(
                            id = esercizio.id,
                            title = esercizio.titolo,
                            subtitle = esercizio.sottotitolo,
                            unlocked = esercizio.apreCon in modulesRead,
                            solved = esercizio.id in exercisesSolved,
                            opensWith = listOfNotNull(esercizio.apreCon)
                                .filterNot { it in modulesRead }
                                .mapNotNull { id -> curriculum.module(id)?.title },
                        )
                    },
                    cases = cases.forLevel(level.order).map { caso ->
                        CaseRow(
                            id = caso.id,
                            title = caso.title,
                            subtitle = caso.subtitle,
                            minutes = caso.minutes,
                            // Un caso che non dichiara moduli non assume niente: e' aperto,
                            // esattamente come una domanda dello Studio senza competenza.
                            unlocked = caso.opensWith.all { it in modulesRead },
                            played = caso.id in casesPlayed,
                            opensWith = caso.opensWith
                                .filterNot { it in modulesRead }
                                .mapNotNull { id -> curriculum.module(id)?.title },
                        )
                    },
                )
            }
            _uiState.value = PathUiState(levels = rows)
        }
    }
}
