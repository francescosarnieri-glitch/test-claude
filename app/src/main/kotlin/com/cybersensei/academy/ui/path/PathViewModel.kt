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
    val name: String,
    val subtitle: String,
    val available: Boolean,
    val modules: List<ModuleRow>,
)

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

            val rows = Level.entries.map { level ->
                val content = curriculum.level(level.order)
                LevelRow(
                    name = level.italianName,
                    subtitle = level.subtitle,
                    // A level with no material yet is shown, but honestly marked as absent.
                    available = content != null,
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
