package com.cybersensei.academy.engine.mastery

import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.math.pow

/**
 * Turns answers into knowledge estimates.
 *
 * The model is a deliberately simple relative of Bayesian Knowledge Tracing: every answer
 * moves the estimate a fraction of the *remaining* distance to the bound it is heading for,
 * so progress slows down near certainty and collapses fast when a confident student is
 * wrong. Confidence and response time weight that step.
 */
class MasteryEngine(private val config: Config = Config()) {

    data class Config(
        /** Step sizes toward 1.0 for a correct answer, by declared confidence. */
        val gainSure: Double = 0.20,
        val gainUnsure: Double = 0.11,
        val gainGuess: Double = 0.03,
        /** Step sizes toward 0.0 for a wrong answer, by declared confidence. */
        val lossSure: Double = 0.32,
        val lossUnsure: Double = 0.16,
        val lossGuess: Double = 0.07,
        /**
         * Below this fraction of the expected time, a correct answer on a weak skill did not
         * involve reading the options, let alone thinking about them.
         */
        val impossiblyFastFraction: Double = 0.25,
        /** A skill this weak cannot produce a genuine instant answer. */
        val luckSuspicionBelow: Double = 0.45,
        /** Days for an unrehearsed skill to lose half of its estimated value. */
        val baseHalfLifeDays: Double = 12.0,
        /** Each consecutive correct answer makes the memory this much more durable. */
        val halfLifeBonusPerStreak: Double = 0.45,
        val maxHalfLifeDays: Double = 120.0,
    )

    fun register(current: Mastery, answer: AnswerRecord): MasteryUpdate {
        require(current.skillId == answer.skillId) {
            "Answer for '${answer.skillId}' cannot update mastery of '${current.skillId}'"
        }

        val verdict = judge(current, answer)
        val decayed = decay(current, answer.answeredAt)
        val newValue = when (verdict) {
            AnswerVerdict.SOLID -> decayed.value + config.gainSure * (1.0 - decayed.value)
            AnswerVerdict.CORRECT_BUT_FRAGILE -> decayed.value + config.gainUnsure * (1.0 - decayed.value)
            // A lucky hit is worth almost nothing: it must not unlock anything.
            AnswerVerdict.SUSPECTED_LUCK -> decayed.value + config.gainGuess * (1.0 - decayed.value)
            AnswerVerdict.ROOTED_MISCONCEPTION -> decayed.value - config.lossSure * decayed.value
            AnswerVerdict.HONEST_MISS -> {
                val loss = if (answer.confidence == Confidence.GUESS) config.lossGuess else config.lossUnsure
                decayed.value - loss * decayed.value
            }
        }.coerceIn(0.0, 1.0)

        val updated = Mastery(
            skillId = current.skillId,
            value = newValue,
            attempts = current.attempts + 1,
            consecutiveCorrect = if (verdict.isCorrect) current.consecutiveCorrect + 1 else 0,
            lastPracticed = answer.answeredAt,
        )

        return MasteryUpdate(
            mastery = updated,
            previous = current,
            verdict = verdict,
            experiencePoints = experienceFor(verdict),
        )
    }

    /**
     * Reads the answer the way a teacher standing next to the student would: not just right
     * or wrong, but *how* it was right or wrong.
     */
    fun judge(current: Mastery, answer: AnswerRecord): AnswerVerdict = when {
        !answer.correct && answer.confidence == Confidence.SURE -> AnswerVerdict.ROOTED_MISCONCEPTION
        !answer.correct -> AnswerVerdict.HONEST_MISS
        answer.confidence == Confidence.GUESS -> AnswerVerdict.SUSPECTED_LUCK
        answeredTooFastToBeReal(current, answer) -> AnswerVerdict.SUSPECTED_LUCK
        answer.confidence == Confidence.UNSURE -> AnswerVerdict.CORRECT_BUT_FRAGILE
        else -> AnswerVerdict.SOLID
    }

    /**
     * A correct, instant answer is only suspicious while the skill is still weak — and only
     * when the student did not stake anything on it.
     *
     * Speed on its own is not evidence of guessing, and treating it as such gets the wrong
     * person every time: somebody who already knows the answer *should* be fast, and the first
     * question of a fresh install always finds their mastery at zero. Told "sospetto tu abbia
     * tirato a indovinare" for knowing something, a student learns to sit and wait before
     * clicking — which is a strategy the school taught them, and a stupid one.
     *
     * So a declared "ne sono sicuro" is believed. It can be, because declaring it is not free:
     * the same declaration on a wrong answer is the harshest verdict the engine has. A guesser
     * who claims certainty pays for it three questions out of four, which is a far better
     * filter than a stopwatch. And nothing is lost against the random clicker, who is wrong
     * most of the time and whose mastery falls on its own.
     */
    private fun answeredTooFastToBeReal(current: Mastery, answer: AnswerRecord): Boolean {
        if (answer.confidence == Confidence.SURE) return false
        if (current.value >= config.luckSuspicionBelow) return false
        if (answer.expectedTime.inWholeMilliseconds <= 0) return false
        val fraction = answer.responseTime.inWholeMilliseconds.toDouble() /
            answer.expectedTime.inWholeMilliseconds.toDouble()
        return fraction < config.impossiblyFastFraction
    }

    /**
     * Applies forgetting. Knowledge that was never rehearsed fades quickly; knowledge
     * confirmed several times in a row fades slowly. This is what makes the professor say
     * "questo concetto ti sta scivolando via" before the student finds out the hard way.
     */
    fun decay(mastery: Mastery, now: Instant): Mastery {
        val last = mastery.lastPracticed ?: return mastery
        val elapsedDays = JavaDuration.between(last, now).toMillis() / MILLIS_PER_DAY
        if (elapsedDays <= 0.0) return mastery

        val halfLife = (
            config.baseHalfLifeDays *
                (1.0 + config.halfLifeBonusPerStreak * mastery.consecutiveCorrect)
            ).coerceAtMost(config.maxHalfLifeDays)

        val retained = mastery.value * 0.5.pow(elapsedDays / halfLife)
        return mastery.copy(value = retained.coerceIn(0.0, 1.0))
    }

    /**
     * Experience rewards understanding, not outcome: a hesitant right answer is worth less
     * than a confident one, and a lucky one is worth almost nothing. Being wrong honestly
     * still pays a little, because admitting it is what makes the lesson land.
     */
    fun experienceFor(verdict: AnswerVerdict): Int = when (verdict) {
        AnswerVerdict.SOLID -> 20
        AnswerVerdict.CORRECT_BUT_FRAGILE -> 10
        AnswerVerdict.SUSPECTED_LUCK -> 2
        AnswerVerdict.ROOTED_MISCONCEPTION -> 3
        AnswerVerdict.HONEST_MISS -> 5
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000.0
    }
}
