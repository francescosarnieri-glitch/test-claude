package com.cybersensei.academy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.SchoolClock
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.regole.Palestra
import com.cybersensei.academy.notifications.StudyReminders
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the app should open. */
enum class StartDestination { UNKNOWN, ENROLMENT, SCHOOL }

@HiltViewModel
class RootViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val reminders: StudyReminders,
    private val clock: SchoolClock,
    palestra: Palestra,
) : ViewModel() {

    init {
        // The records module counts solved exercises but has no business knowing what one is.
        // Told once, here, where the app already starts itself up.
        repository.declareExercises(palestra.esercizi.size)
    }

    private var flusher: Job? = null

    /**
     * The stopwatch starts when the app comes to the front and is written down when it leaves.
     *
     * It is also written down every minute while running, because a process killed in the
     * background never gets to say goodbye: without the periodic write, a student who studied
     * for forty minutes and had the app reclaimed would find the counter where he left it an
     * hour before, which reads exactly like the frozen number this replaced.
     */
    fun enteredSchool() {
        viewModelScope.launch {
            clock.enter(repository.timeAtSchoolSeconds())
            flusher?.cancel()
            flusher = viewModelScope.launch {
                while (true) {
                    delay(FLUSH_MILLIS)
                    if (clock.running) repository.setTimeAtSchool(clock.totalSeconds)
                }
            }
        }
    }

    fun leftSchool() {
        flusher?.cancel()
        flusher = null
        if (!clock.running) return
        val total = clock.leave()
        // Deliberately on a scope that outlives this one: the write must survive the screen
        // going away, which is the exact moment it happens.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            repository.setTimeAtSchool(total)
        }
    }

    /**
     * Driven by the saved profile, so finishing the interview moves the app into the school
     * on its own — there is no separate "onboarding done" flag to keep in sync.
     */
    val startDestination: StateFlow<StartDestination> = repository.observeProfile()
        .map { profile -> if (profile == null) StartDestination.ENROLMENT else StartDestination.SCHOOL }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartDestination.UNKNOWN)

    init {
        viewModelScope.launch {
            repository.registerOpening()
            // Alarms do not survive a force-stop, and nothing tells the app when that
            // happened. Re-arming on every launch is cheap and makes the reminder resilient
            // to the one case the boot receiver cannot cover.
            reminders.apply(repository.profile())
        }
    }

    private companion object {
        /** Spesso abbastanza da non perdere quasi niente, raro abbastanza da non pesare. */
        const val FLUSH_MILLIS = 60_000L
    }
}
