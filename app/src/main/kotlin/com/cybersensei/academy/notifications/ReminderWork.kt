package com.cybersensei.academy.notifications

import android.content.Context
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the professor decided to do when the reminder went off. */
enum class ReminderOutcome {
    /** Nobody is enrolled, or the reminder was switched off while an alarm was in flight. */
    NO_ONE_TO_REMIND,

    /** The student already studied today. Being reminded now would be noise. */
    ALREADY_STUDIED,

    SPOKEN,
}

/**
 * Everything that happens when the daily alarm fires, kept out of the receiver.
 *
 * A [android.content.BroadcastReceiver] is a hard place to test — it has no lifecycle of its
 * own and its work has to be finished with goAsync — so the decision lives here, where it is
 * a plain suspend function that returns what it chose to do.
 */
@Singleton
class ReminderWork @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: SchoolRepository,
    private val tutorEngine: TutorEngine,
    private val reminders: StudyReminders,
    private val timeProvider: TimeProvider,
) {

    suspend fun run(): ReminderOutcome {
        val profile = repository.profile()
        if (profile?.reminderAt == null) {
            reminders.cancel()
            return ReminderOutcome.NO_ONE_TO_REMIND
        }

        // Re-armed before anything else: an inexact one-shot alarm is the only thing standing
        // between today's reminder and tomorrow's.
        reminders.apply(profile)

        if (repository.stats().lastStudyDate == timeProvider.today().toString()) {
            return ReminderOutcome.ALREADY_STUDIED
        }

        val line = tutorEngine.speak(TutorEvent.Reminder, repository.snapshot())
        ProfessorNotification.show(context, line.text)
        return ReminderOutcome.SPOKEN
    }
}
