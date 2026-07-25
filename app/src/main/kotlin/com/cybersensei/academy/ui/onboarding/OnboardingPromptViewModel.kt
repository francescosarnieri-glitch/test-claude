package com.cybersensei.academy.ui.onboarding

import androidx.lifecycle.ViewModel
import com.cybersensei.academy.engine.tutor.StudentSnapshot
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Supplies the professor's line for each question of the interview.
 *
 * It is kept apart from [OnboardingViewModel] so that the lines survive the state changes of
 * the form: the professor should not re-word his question every time a letter is typed.
 */
@HiltViewModel
class OnboardingPromptViewModel @Inject constructor(
    private val tutor: TutorEngine,
) : ViewModel() {

    private val spoken = mutableMapOf<OnboardingStep, String>()

    fun promptFor(step: OnboardingStep, draftName: String): String = spoken.getOrPut(step) {
        tutor.speak(
            TutorEvent.OnboardingPrompt(step.name.lowercase(), draftName),
            StudentSnapshot(profile = null),
        ).text
    }
}
