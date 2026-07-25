package com.cybersensei.academy.ui.classroom

import androidx.lifecycle.ViewModel
import com.cybersensei.academy.engine.tutor.StudentSnapshot
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClassroomUiState(
    val professorLine: String = "",
    val studentName: String? = null,
)

@HiltViewModel
class ClassroomViewModel @Inject constructor(
    private val tutor: TutorEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClassroomUiState())
    val uiState: StateFlow<ClassroomUiState> = _uiState.asStateFlow()

    init {
        greet()
    }

    /**
     * Until onboarding and the database land in Fase 2 there is no profile to read, so the
     * professor speaks as he does to someone he has never met — which is exactly the right
     * thing for him to say right now.
     */
    private fun greet() {
        val snapshot = StudentSnapshot(profile = null)
        _uiState.value = ClassroomUiState(
            professorLine = tutor.speak(TutorEvent.AppOpened, snapshot).text,
            studentName = null,
        )
    }

    /** Lets the student hear the professor again while the school is being built. */
    fun speakAgain() = greet()
}
