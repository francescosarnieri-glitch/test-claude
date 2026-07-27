package com.cybersensei.academy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.DailyBudget
import com.cybersensei.academy.core.model.LearningGoal
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.core.model.TutorTone
import com.cybersensei.academy.notifications.StudyReminders
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a reset would actually destroy, in the student's own numbers.
 *
 * Shown before the confirmation rather than a generic warning: "perderai tutto" is easy to
 * tap through, "quarantadue lezioni e undici giorni di fila" is not.
 */
data class WhatWouldBeLost(
    val lessonsCompleted: Int,
    val skillsMeasured: Int,
    val experiencePoints: Int,
    val recordStreakDays: Int,
    val badges: Int,
    val studyMinutes: Int,
    val daysEnrolled: Long,
)

data class SettingsUiState(
    val profile: StudentProfile? = null,
    val lost: WhatWouldBeLost? = null,
    /** The reset has been asked for and is waiting for a second, deliberate confirmation. */
    val confirmingReset: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val timeProvider: TimeProvider,
    private val reminders: StudyReminders,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val profile = repository.profile()
            val stats = repository.stats()
            _uiState.value = _uiState.value.copy(
                profile = profile,
                lost = WhatWouldBeLost(
                    lessonsCompleted = repository.completedLessonIds().size,
                    skillsMeasured = repository.allMastery().count { it.attempts > 0 },
                    experiencePoints = stats.experiencePoints,
                    recordStreakDays = stats.recordStreakDays,
                    badges = repository.badgesHeld().size,
                    studyMinutes = stats.totalStudyMinutes,
                    daysEnrolled = profile?.daysEnrolled(timeProvider.today()) ?: 0,
                ),
            )
        }
    }

    fun onToneChosen(tone: TutorTone) = update { it.copy(tone = tone) }

    fun onBudgetChosen(budget: DailyBudget) = update { it.copy(dailyBudget = budget) }

    fun onGoalChosen(goal: LearningGoal) = update { it.copy(goal = goal) }

    /**
     * Sets — or removes — the daily reminder.
     *
     * Saved and re-armed in one coroutine, in that order: the alarm has to reflect what is
     * written down, and two separate launches would let the receiver read the profile from
     * before the save.
     */
    fun onReminderChosen(at: LocalTime?) {
        val current = _uiState.value.profile ?: return
        val updated = current.copy(reminderAt = at)
        _uiState.value = _uiState.value.copy(profile = updated, saved = true)
        viewModelScope.launch {
            repository.saveProfile(updated)
            reminders.apply(updated)
        }
    }

    fun onNicknameChanged(nickname: String) {
        val cleaned = nickname.trim()
        // An empty nickname would leave the professor addressing a hole in a sentence.
        update { it.copy(nickname = cleaned.ifEmpty { it.name }) }
    }

    private fun update(change: (StudentProfile) -> StudentProfile) {
        val current = _uiState.value.profile ?: return
        val updated = change(current)
        _uiState.value = _uiState.value.copy(profile = updated, saved = true)
        viewModelScope.launch { repository.saveProfile(updated) }
    }

    fun askToReset() {
        _uiState.value = _uiState.value.copy(confirmingReset = true)
    }

    fun cancelReset() {
        _uiState.value = _uiState.value.copy(confirmingReset = false)
    }

    /**
     * Forgets the student completely.
     *
     * Nothing is kept back, not even the name: the school's promise is that everything stays
     * on the device, and the counterpart of that promise is being able to remove all of it.
     * The app returns to enrolment on its own, because the start destination follows the
     * saved profile rather than a separate flag.
     */
    fun confirmReset() {
        viewModelScope.launch {
            // The alarm first: erasing the student while an alarm is still armed would leave
            // the professor knocking at a door with nobody behind it.
            reminders.cancel()
            repository.eraseEverything()
            _uiState.value = SettingsUiState()
        }
    }

    /** Total lessons in the syllabus, so the reset warning can put the loss in proportion. */
    val lessonsInSyllabus: Int get() = curriculum.lessons.size
}
