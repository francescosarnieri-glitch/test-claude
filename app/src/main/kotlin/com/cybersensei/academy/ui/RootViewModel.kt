package com.cybersensei.academy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.database.SchoolRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the app should open. */
enum class StartDestination { UNKNOWN, ENROLMENT, SCHOOL }

@HiltViewModel
class RootViewModel @Inject constructor(
    private val repository: SchoolRepository,
) : ViewModel() {

    /**
     * Driven by the saved profile, so finishing the interview moves the app into the school
     * on its own — there is no separate "onboarding done" flag to keep in sync.
     */
    val startDestination: StateFlow<StartDestination> = repository.observeProfile()
        .map { profile -> if (profile == null) StartDestination.ENROLMENT else StartDestination.SCHOOL }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartDestination.UNKNOWN)

    init {
        viewModelScope.launch { repository.registerOpening() }
    }
}
