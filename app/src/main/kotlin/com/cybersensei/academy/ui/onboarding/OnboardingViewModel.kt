package com.cybersensei.academy.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.DailyBudget
import com.cybersensei.academy.core.model.LearningGoal
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.core.model.TutorTone
import com.cybersensei.academy.core.model.ZodiacSign
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The steps of the interview, in the order the professor asks them. */
enum class OnboardingStep {
    WELCOME,
    NAME,
    NICKNAME,
    BIRTH_DATE,
    GOAL,
    TONE,
    BUDGET,
    PACT,
    ;

    val isFirst: Boolean get() = ordinal == 0
    val progress: Float get() = (ordinal + 1f) / entries.size
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val name: String = "",
    val nickname: String = "",
    val day: String = "",
    val month: String = "",
    val year: String = "",
    val skipBirthDate: Boolean = false,
    val goal: LearningGoal? = null,
    val tone: TutorTone? = null,
    val budget: DailyBudget? = null,
    val pactSigned: Boolean = false,
    val finished: Boolean = false,
) {
    val birthDate: LocalDate? = runCatching {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    }.getOrNull()

    val zodiacSign: ZodiacSign? = birthDate?.let(ZodiacSign::of)

    /** The name the professor will actually use, before it has been confirmed. */
    val addressAs: String get() = nickname.ifBlank { name }.trim()

    val dateLooksComplete: Boolean get() = day.isNotBlank() && month.isNotBlank() && year.isNotBlank()

    val dateError: String? get() = when {
        skipBirthDate || !dateLooksComplete -> null
        birthDate == null -> "Questa data non esiste. Ricontrolla."
        birthDate.isAfter(LocalDate.now()) -> "Non puoi essere nato nel futuro."
        birthDate.isBefore(LocalDate.now().minusYears(120)) -> "Mi sembra un filo troppo indietro."
        else -> null
    }

    /** Whether the student may move on from the step they are on. */
    val canAdvance: Boolean get() = when (step) {
        OnboardingStep.WELCOME -> true
        OnboardingStep.NAME -> name.trim().length >= 2
        OnboardingStep.NICKNAME -> true
        OnboardingStep.BIRTH_DATE -> skipBirthDate || (dateLooksComplete && dateError == null)
        OnboardingStep.GOAL -> goal != null
        OnboardingStep.TONE -> tone != null
        OnboardingStep.BUDGET -> budget != null
        OnboardingStep.PACT -> pactSigned
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onNameChanged(value: String) = update { copy(name = value.take(40)) }
    fun onNicknameChanged(value: String) = update { copy(nickname = value.take(24)) }
    fun onDayChanged(value: String) = update { copy(day = value.filter(Char::isDigit).take(2)) }
    fun onMonthChanged(value: String) = update { copy(month = value.filter(Char::isDigit).take(2)) }
    fun onYearChanged(value: String) = update { copy(year = value.filter(Char::isDigit).take(4)) }
    fun onSkipBirthDate() = update { copy(skipBirthDate = true) }.also { next() }
    fun onGoalChosen(goal: LearningGoal) = update { copy(goal = goal) }
    fun onToneChosen(tone: TutorTone) = update { copy(tone = tone) }
    fun onBudgetChosen(budget: DailyBudget) = update { copy(budget = budget) }
    fun onPactSigned() = update { copy(pactSigned = true) }

    fun next() {
        val state = _uiState.value
        if (!state.canAdvance) return
        if (state.step == OnboardingStep.PACT) {
            enrol()
            return
        }
        update { copy(step = OnboardingStep.entries[step.ordinal + 1]) }
    }

    fun back() {
        val state = _uiState.value
        if (state.step.isFirst) return
        update { copy(step = OnboardingStep.entries[step.ordinal - 1]) }
    }

    private fun enrol() {
        val state = _uiState.value
        val profile = StudentProfile(
            name = state.name.trim(),
            nickname = state.addressAs.ifBlank { state.name.trim() },
            birthDate = if (state.skipBirthDate) null else state.birthDate,
            goal = state.goal ?: LearningGoal.CURIOSITY,
            tone = state.tone ?: TutorTone.FRIENDLY,
            dailyBudget = state.budget ?: DailyBudget.NORMAL,
            enrolledOn = timeProvider.today(),
            ethicalPactSigned = true,
        )
        viewModelScope.launch {
            repository.saveProfile(profile)
            // Enrolling is the first day of school, and it counts as one.
            repository.registerStudyDay()
            update { copy(finished = true) }
        }
    }

    private inline fun update(block: OnboardingUiState.() -> OnboardingUiState) {
        _uiState.value = _uiState.value.block()
    }
}
