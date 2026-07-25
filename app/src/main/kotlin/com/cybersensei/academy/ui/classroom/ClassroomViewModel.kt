package com.cybersensei.academy.ui.classroom

import androidx.lifecycle.ViewModel
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.professor.WelcomeLineComposer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClassroomUiState(
    val professorLine: String = "",
    val studentName: String? = null,
    val dayPartLabel: String = "",
)

@HiltViewModel
class ClassroomViewModel @Inject constructor(
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val composer = WelcomeLineComposer(timeProvider)

    private val _uiState = MutableStateFlow(ClassroomUiState())
    val uiState: StateFlow<ClassroomUiState> = _uiState.asStateFlow()

    init {
        // Fase 2 replaces this with the real profile read from the database; until the
        // student has been through onboarding the professor has no name to use.
        _uiState.value = ClassroomUiState(
            professorLine = composer.compose(studentName = null, openingCount = 0),
            studentName = null,
            dayPartLabel = timeProvider.dayPart().italianGreeting,
        )
    }

    /** Lets the student hear another line — useful while the engine is being built. */
    fun nextLine(openingCount: Int) {
        _uiState.value = _uiState.value.copy(
            professorLine = composer.compose(_uiState.value.studentName, openingCount),
        )
    }
}
