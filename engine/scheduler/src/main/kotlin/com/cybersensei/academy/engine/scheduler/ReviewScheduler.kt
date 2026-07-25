package com.cybersensei.academy.engine.scheduler

import com.cybersensei.academy.engine.mastery.AnswerVerdict
import java.time.LocalDate

/**
 * What the school plans to ask again, and when.
 *
 * [easeFactor] follows the SM-2 convention: it is a multiplier that grows for material the
 * student finds easy and shrinks for material that keeps tripping them up.
 */
data class ReviewItem(
    val skillId: String,
    val intervalDays: Int = 0,
    val easeFactor: Double = 2.5,
    val repetitions: Int = 0,
    val dueOn: LocalDate,
    /** Set when the answer was right but not trustworthy — it comes back fast, on purpose. */
    val flaggedForLuck: Boolean = false,
) {
    fun isDue(today: LocalDate): Boolean = !dueOn.isAfter(today)
}

/**
 * Spaced repetition, adapted from SM-2.
 *
 * The adaptation that matters: the recall quality is derived from the professor's verdict,
 * not from the raw right/wrong. A right-but-guessed answer is scheduled like a failure,
 * because in this school luck is not knowledge.
 */
class ReviewScheduler(private val config: Config = Config()) {

    data class Config(
        val firstIntervalDays: Int = 1,
        val secondIntervalDays: Int = 3,
        val minimumEase: Double = 1.3,
        val maximumIntervalDays: Int = 180,
        /** A suspected lucky answer must return within a couple of days, not weeks. */
        val luckRetryDays: Int = 2,
    )

    /**
     * SM-2 recall quality, 0..5. Anything below 3 restarts the ladder.
     */
    fun qualityOf(verdict: AnswerVerdict): Int = when (verdict) {
        AnswerVerdict.SOLID -> 5
        AnswerVerdict.CORRECT_BUT_FRAGILE -> 3
        AnswerVerdict.SUSPECTED_LUCK -> 2
        AnswerVerdict.HONEST_MISS -> 1
        AnswerVerdict.ROOTED_MISCONCEPTION -> 0
    }

    fun schedule(item: ReviewItem, verdict: AnswerVerdict, today: LocalDate): ReviewItem {
        val quality = qualityOf(verdict)
        val ease = nextEase(item.easeFactor, quality)

        if (quality < 3) {
            // Back to square one: the ladder only rewards recall that actually happened.
            val interval = if (verdict == AnswerVerdict.SUSPECTED_LUCK) config.luckRetryDays else 1
            return item.copy(
                intervalDays = interval,
                easeFactor = ease,
                repetitions = 0,
                dueOn = today.plusDays(interval.toLong()),
                flaggedForLuck = verdict == AnswerVerdict.SUSPECTED_LUCK,
            )
        }

        val repetitions = item.repetitions + 1
        val interval = when (repetitions) {
            1 -> config.firstIntervalDays
            2 -> config.secondIntervalDays
            else -> (item.intervalDays * ease).toInt().coerceAtLeast(item.intervalDays + 1)
        }.coerceAtMost(config.maximumIntervalDays)

        return item.copy(
            intervalDays = interval,
            easeFactor = ease,
            repetitions = repetitions,
            dueOn = today.plusDays(interval.toLong()),
            flaggedForLuck = false,
        )
    }

    private fun nextEase(current: Double, quality: Int): Double {
        val adjusted = current + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
        return adjusted.coerceAtLeast(config.minimumEase)
    }

    /**
     * Picks what to revise now.
     *
     * Order is deliberate: things the student only *seemed* to know come first, then the most
     * overdue. The list is capped by how many minutes the student said they have, because a
     * plan that ignores the student's day is a plan they will abandon.
     */
    fun dueToday(
        items: Collection<ReviewItem>,
        today: LocalDate,
        maxItems: Int = Int.MAX_VALUE,
    ): List<ReviewItem> = items
        .filter { it.isDue(today) }
        .sortedWith(
            compareByDescending<ReviewItem> { it.flaggedForLuck }
                .thenBy { it.dueOn }
                .thenBy { it.skillId },
        )
        .take(maxItems)

    /** How many items fit in a session, given the student's declared daily budget. */
    fun capacityFor(minutesAvailable: Int, secondsPerItem: Int = 45): Int =
        ((minutesAvailable * 60) / secondsPerItem).coerceAtLeast(1)
}
