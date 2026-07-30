package com.cybersensei.academy.ui.classroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.curriculum.Trophy
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.curriculum.Lesson
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ClassroomUiState(
    val professorLine: String = "",
    val studentName: String? = null,
    val nextLesson: Lesson? = null,
    val nextModuleId: String? = null,
    val lessonsDone: Int = 0,
    val lessonsTotal: Int = 0,
    val streakDays: Int = 0,
    val experiencePoints: Int = 0,
    val dueReviews: Int = 0,
    val everythingDone: Boolean = false,
    /**
     * Won right now — announced here once, then they live on the trophy wall.
     *
     * The announcement stays in the classroom on purpose: it is the moment that makes a trophy
     * feel like one, and a medal that appears silently on a screen the student has to go and
     * open is a medal nobody notices.
     */
    val newTrophies: List<Trophy> = emptyList(),
    val trophiesEarned: Int = 0,
    val trophiesTotal: Int = 0,
)

@HiltViewModel
class ClassroomViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val tutor: TutorEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClassroomUiState())
    val uiState: StateFlow<ClassroomUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Recomputed every time the classroom comes back to the foreground, so the professor's
     * greeting reflects what the student did a minute ago rather than at app start.
     */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = repository.snapshot()
            val done = repository.completedLessonIds()
            val unlocked = repository.unlockedLevels()
            // Only ever propose something the student is actually allowed to study.
            val lessons = curriculum.levels
                .filter { it.level in unlocked }
                .flatMap { level -> level.modules.flatMap { it.lessons } }
            val next = lessons.firstOrNull { it.id !in done }
            val stats = repository.stats()
            val newTrophies = repository.awardTrophies()
            val trophiesEarned = repository.trophiesHeld().size

            val greeting = when {
                // An absence deserves acknowledging before anything else.
                snapshot.daysSinceLastVisit >= 3 ->
                    tutor.speak(TutorEvent.ReturnedAfterAbsence(snapshot.daysSinceLastVisit), snapshot)
                else -> tutor.speak(TutorEvent.AppOpened, snapshot)
            }

            _uiState.value = ClassroomUiState(
                professorLine = greeting.text,
                studentName = snapshot.profile?.nickname,
                nextLesson = next,
                nextModuleId = next?.let { curriculum.moduleOfLesson(it.id)?.id },
                lessonsDone = done.size,
                lessonsTotal = lessons.size,
                streakDays = stats.streakDays,
                experiencePoints = stats.experiencePoints,
                dueReviews = snapshot.dueReviews,
                everythingDone = next == null && lessons.isNotEmpty(),
                newTrophies = newTrophies,
                trophiesEarned = trophiesEarned,
                trophiesTotal = repository.trophiesInSchool,
            )
        }
    }
}
