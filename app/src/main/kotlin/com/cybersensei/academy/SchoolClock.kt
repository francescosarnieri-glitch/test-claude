package com.cybersensei.academy

import com.cybersensei.academy.core.common.TimeProvider
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long the student has actually been here.
 *
 * The number used to be the sum of the durations *declared* by the lessons: read the four
 * cards of a four-minute lesson in twenty seconds and the school credited you with four
 * minutes. It was a plausible number and a false one, and it sat in the report card next to
 * six true ones — which is the worst place for a false number to sit, because it borrows
 * their credibility.
 *
 * So it is a stopwatch now, and it measures the only thing it can honestly measure: time with
 * the app in front of you. It deliberately does not claim to measure studying — somebody can
 * hold the phone and think about dinner — which is why what it is called matters as much as
 * how it is counted.
 *
 * The session is held as a fixed mark rather than an accumulator: every write stores the
 * absolute total, so a flush that lands between two reads can never make the count jump
 * forwards or backwards on screen.
 */
@Singleton
class SchoolClock @Inject constructor(private val timeProvider: TimeProvider) {

    private var baselineSeconds: Long = 0
    private var enteredAt: Instant? = null

    /** Starts a session on top of what was already counted. Re-entering does not restart it. */
    fun enter(alreadyCounted: Long) {
        if (enteredAt != null) return
        baselineSeconds = alreadyCounted
        enteredAt = timeProvider.now()
    }

    /** The total as of this instant, session included. Safe to call every second. */
    val totalSeconds: Long
        get() = baselineSeconds + sessionSeconds

    private val sessionSeconds: Long
        get() = enteredAt?.let {
            Duration.between(it, timeProvider.now()).seconds.coerceAtLeast(0)
        } ?: 0

    /** Closes the session and returns the total to write down. */
    fun leave(): Long {
        val total = totalSeconds
        baselineSeconds = total
        enteredAt = null
        return total
    }

    /** True once a session is open, so a flush before the first entry writes nothing. */
    val running: Boolean get() = enteredAt != null
}

/**
 * The stopwatch as the student reads it.
 *
 * Deliberately a running clock and not «4 minuti»: the seconds have to move, otherwise there
 * is no way to tell a number that is being measured from a number that was made up. The shape
 * stays narrow — two fields under an hour, three above — because this string sits at the right
 * edge of a row and must not push the label it belongs to off the screen.
 */
fun formatTimeAtSchool(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val rest = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%d:%02d".format(minutes, rest)
    }
}
