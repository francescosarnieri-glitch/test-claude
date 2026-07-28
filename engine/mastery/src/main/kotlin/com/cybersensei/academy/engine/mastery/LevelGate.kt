package com.cybersensei.academy.engine.mastery

/**
 * Decides whether the student may move on.
 *
 * Two conditions, and the second one is the important one: a high average must not be
 * allowed to hide a subject the student never understood. One weak skill blocks the level,
 * exactly as a real teacher would.
 */
object LevelGate {

    /**
     * The average a level asks for.
     *
     * Seventy-five and not eighty, and the difference is not a rounding: with three questions
     * per competence answered three times — the lesson, the module interrogation, the exam —
     * all correct and all declared certain, the estimate lands at seventy-nine. At eighty a
     * student who had done *everything the app offers* was still refused, with nothing left to
     * do but wait for the next day's review. Gating progress behind a calendar is a different
     * promise from gating it behind understanding, and this school made the second one.
     *
     * What stops a weak pass is the floor below, not this number: answering the same questions
     * while declaring uncertainty reaches fifty-six, and one competence under sixty blocks the
     * level however good the average looks.
     */
    const val AVERAGE_REQUIRED = 0.75
    const val MINIMUM_PER_SKILL = 0.60

    data class Result(
        val passed: Boolean,
        val average: Double,
        /** Skills below the per-skill floor, weakest first — what to revise, in order. */
        val weakSkills: List<Mastery>,
        val missingSkills: List<String>,
    ) {
        val averagePercent: Int get() = (average * 100).toInt()
    }

    /**
     * @param required every skill the level is made of; a skill never practised counts as
     *   missing rather than as zero, so the professor can say "questo non l'hai mai fatto"
     *   instead of "questo lo sai male".
     */
    fun evaluate(required: Collection<String>, achieved: Collection<Mastery>): Result {
        val byId = achieved.associateBy { it.skillId }
        val missing = required.filter { it !in byId }
        val present = required.mapNotNull { byId[it] }

        val average = if (present.isEmpty()) 0.0 else present.sumOf { it.value } / present.size
        val weak = present.filter { it.value < MINIMUM_PER_SKILL }.sortedBy { it.value }

        return Result(
            passed = missing.isEmpty() && weak.isEmpty() && average >= AVERAGE_REQUIRED,
            average = average,
            weakSkills = weak,
            missingSkills = missing,
        )
    }
}
