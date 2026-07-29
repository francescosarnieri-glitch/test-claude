package com.cybersensei.academy.ui.lesson

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.curriculum.Lesson
import com.cybersensei.academy.core.curriculum.LessonCard
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
import com.cybersensei.academy.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LessonUiState(
    val lesson: Lesson? = null,
    val moduleId: String = "",
    val cardIndex: Int = 0,
    val completed: Boolean = false,
    val closingLine: String? = null,
) {
    val card: LessonCard? get() = lesson?.cards?.getOrNull(cardIndex)
    val cardCount: Int get() = lesson?.cards?.size ?: 0
    val isLastCard: Boolean get() = lesson != null && cardIndex >= lesson.cards.lastIndex
    val progress: Float get() = if (cardCount == 0) 0f else (cardIndex + 1f) / cardCount
}

@HiltViewModel
class LessonViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val tutor: TutorEngine,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val lessonId: String = checkNotNull(savedStateHandle[Routes.ARG_LESSON_ID])

    private val _uiState = MutableStateFlow(LessonUiState())
    val uiState: StateFlow<LessonUiState> = _uiState.asStateFlow()

    init {
        val lesson = curriculum.lesson(lessonId)
        _uiState.value = LessonUiState(
            lesson = lesson,
            moduleId = curriculum.moduleOfLesson(lessonId)?.id.orEmpty(),
        )
        if (lesson != null) {
            viewModelScope.launch { repository.startLesson(lesson.id, lesson.title) }
        }
    }

    fun nextCard() {
        val state = _uiState.value
        val lesson = state.lesson ?: return
        if (state.isLastCard) {
            finish(lesson)
        } else {
            _uiState.value = state.copy(cardIndex = state.cardIndex + 1)
        }
    }

    fun previousCard() {
        val state = _uiState.value
        if (state.cardIndex > 0) _uiState.value = state.copy(cardIndex = state.cardIndex - 1)
    }

    /**
     * Leaving in the middle is recorded, not punished: it is what lets the professor pick
     * the lesson up by name next time instead of asking what to do today.
     */
    fun leaveEarly() {
        val state = _uiState.value
        val lesson = state.lesson ?: return
        if (state.completed) return
        viewModelScope.launch { repository.abandonLesson(lesson.title) }
    }

    /**
     * The last card. The lesson is read — it is not done.
     *
     * It used to mark the lesson complete here, and that quietly undid the whole point of the
     * padlocks: read four screens, tap «Torno in aula» instead of «Mettimi alla prova», and the
     * next lesson opened. A student could walk the entire programme, every level of it, without
     * ever being asked a single question. What closes a lesson is sitting its interrogation —
     * passed or failed, exactly like the rule that opens the questions in the study.
     */
    private fun finish(lesson: Lesson) {
        viewModelScope.launch {
            repository.markLessonRead(lesson.id)
            val line = tutor.speak(
                TutorEvent.LessonCompleted(lesson.title),
                repository.snapshot(),
            ).text
            _uiState.value = _uiState.value.copy(completed = true, closingLine = line)
        }
    }
}
