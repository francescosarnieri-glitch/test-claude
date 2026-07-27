package com.cybersensei.academy.ui.path

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.Level
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
)

data class ModuleRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val lessons: List<LessonRow>,
    val questionCount: Int,
    val masteryPercent: Int,
)

data class LevelRow(
    val order: Int,
    val name: String,
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
) {
    val available: Boolean get() = hasContent && unlocked

    val lockedReason: String? get() = when {
        !hasContent -> "Non ancora disponibile — arriva in una fase successiva."
        !unlocked -> "Si apre quando avrai superato il livello precedente."
        else -> null
    }
}

data class PathUiState(val levels: List<LevelRow> = emptyList())

@HiltViewModel
class PathViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
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

            val rows = Level.entries.map { level ->
                val content = curriculum.level(level.order)
                val lessons = content?.modules.orEmpty().flatMap { it.lessons }
                LevelRow(
                    order = level.order,
                    name = level.italianName,
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
                        ModuleRow(
                            id = module.id,
                            title = module.title,
                            subtitle = module.subtitle,
                            lessons = module.lessons.map { lesson ->
                                LessonRow(lesson.id, lesson.title, lesson.minutes, lesson.id in done)
                            },
                            questionCount = module.questions.size,
                            masteryPercent = (average * 100).toInt(),
                        )
                    },
                )
            }
            _uiState.value = PathUiState(levels = rows)
        }
    }
}
