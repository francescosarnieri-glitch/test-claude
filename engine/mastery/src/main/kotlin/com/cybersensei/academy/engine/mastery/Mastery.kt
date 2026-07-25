package com.cybersensei.academy.engine.mastery

import java.time.Instant
import kotlin.time.Duration

/**
 * What the school believes the student knows about one micro-skill, on a 0..1 scale.
 *
 * This is not a score to show off: it is an estimate that goes *down* on its own as time
 * passes, because human memory does the same.
 */
data class Mastery(
    val skillId: String,
    val value: Double = 0.0,
    val attempts: Int = 0,
    val consecutiveCorrect: Int = 0,
    val lastPracticed: Instant? = null,
) {
    init {
        require(value in 0.0..1.0) { "Mastery must be in 0..1, was $value" }
    }

    val percent: Int get() = (value * 100).toInt()

    /** True once the skill is solid enough to stop being drilled aggressively. */
    val isSolid: Boolean get() = value >= LevelGate.MINIMUM_PER_SKILL
}

/** A single answer, with everything needed to judge whether it was actually understood. */
data class AnswerRecord(
    val skillId: String,
    val correct: Boolean,
    val confidence: Confidence,
    val responseTime: Duration,
    /** How long a student who reasons it through is expected to take. */
    val expectedTime: Duration,
    /** Which misconception the chosen wrong option reveals, when it was a wrong one. */
    val misconceptionId: String? = null,
    val answeredAt: Instant = Instant.EPOCH,
)

/**
 * The professor's reading of an answer. Every branch leads to an explanation — there is no
 * verdict here that means "move on silently".
 */
enum class AnswerVerdict {
    /** Right, confident, and the timing says it was reasoned. Full credit. */
    SOLID,

    /** Right but hesitant: the answer counts less than the explanation that follows. */
    CORRECT_BUT_FRAGILE,

    /** Right by luck — admitted, or betrayed by an impossibly fast click on a weak skill. */
    SUSPECTED_LUCK,

    /** Wrong *and* confident: the worst combination, and the most useful one to catch. */
    ROOTED_MISCONCEPTION,

    /** Wrong and aware of it. Nothing to be ashamed of; explain from the beginning. */
    HONEST_MISS,
    ;

    val isCorrect: Boolean get() = this == SOLID || this == CORRECT_BUT_FRAGILE || this == SUSPECTED_LUCK

    /** Should this question come back soon, regardless of the answer being right? */
    val needsRequeue: Boolean get() = this != SOLID
}

/** Outcome of feeding one answer to the engine. */
data class MasteryUpdate(
    val mastery: Mastery,
    val previous: Mastery,
    val verdict: AnswerVerdict,
    val experiencePoints: Int,
) {
    val delta: Double get() = mastery.value - previous.value
}
