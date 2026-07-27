package com.cybersensei.academy.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.model.StudentProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one daily alarm, and everything that decides when it goes off.
 *
 * Deliberately an *inexact* alarm. An exact one would need SCHEDULE_EXACT_ALARM, and this
 * app spends a whole module teaching that a permission is justified by a function that
 * cannot exist without it: a study reminder that arrives at 20:04 instead of 20:00 has lost
 * nothing at all. The system is free to hold it back while the phone is asleep and deliver
 * it when it next wakes — which is also why it is scheduled one day at a time and re-armed
 * by the receiver, rather than as a repeating alarm that would drift.
 */
@Singleton
class StudyReminders @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider,
) {

    /** Puts the alarm in whatever state the profile says it should be in. */
    fun apply(profile: StudentProfile?) {
        val at = profile?.reminderAt
        if (at == null) cancel() else schedule(at)
    }

    fun schedule(at: LocalTime) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val triggerAt = nextOccurrence(at, timeProvider.dateTime(), timeProvider.zone())
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt.toInstant().toEpochMilli(),
            pendingIntent(),
        )
    }

    fun cancel() {
        context.getSystemService<AlarmManager>()?.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        // Immutable: nothing outside the app has any business rewriting this intent.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_REMIND = "com.cybersensei.academy.PROMEMORIA"
        private const val REQUEST_CODE = 1

        /**
         * The next time the clock will read [at]: today if that moment is still ahead,
         * tomorrow otherwise.
         *
         * Through [ZonedDateTime] rather than by adding milliseconds, because twice a year a
         * day is not 24 hours long, and a reminder set for 02:30 has to survive the night the
         * clocks move — the zone rules pick a real instant, a fixed offset would not.
         */
        fun nextOccurrence(at: LocalTime, now: LocalDateTime, zone: ZoneId): ZonedDateTime {
            val today = now.toLocalDate().atTime(at)
            val target = if (today.isAfter(now)) today else today.plusDays(1)
            return target.atZone(zone)
        }
    }
}
