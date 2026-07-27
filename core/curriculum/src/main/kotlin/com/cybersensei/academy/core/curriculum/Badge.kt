package com.cybersensei.academy.core.curriculum

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What has to be true for a badge to be earned. */
@Serializable
data class BadgeCondition(
    val type: String,
    val value: Int,
) {
    companion object {
        const val LEVEL_PASSED = "level_passed"
        const val LESSONS_COMPLETED = "lessons_completed"
        const val STREAK_DAYS = "streak_days"
        const val MASTERY_AVERAGE = "mastery_average"

        /** The final exercise, finished at least once. Value is ignored. */
        const val CAPSTONE_COMPLETED = "capstone_completed"

        val KNOWN_TYPES = setOf(
            LEVEL_PASSED, LESSONS_COMPLETED, STREAK_DAYS, MASTERY_AVERAGE, CAPSTONE_COMPLETED,
        )
    }
}

@Serializable
data class Badge(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val condition: BadgeCondition,
)

@Serializable
data class BadgeContent(val badges: List<Badge>)

/** Everything the badge rules are allowed to look at. */
data class BadgeContext(
    val lessonsCompleted: Int = 0,
    val streakDays: Int = 0,
    /** Average mastery across every skill practised so far, 0..1. */
    val masteryAverage: Double = 0.0,
    val passedLevels: Set<Int> = emptySet(),
    /** Whether the student has been through a whole night of the final exercise. */
    val capstoneCompleted: Boolean = false,
)

/**
 * Awards badges from real progress.
 *
 * Deliberately dull: a badge is never given for opening the app or for time spent, only for
 * something the student demonstrably did. A reward that arrives for nothing devalues the
 * ones that mean something.
 */
class BadgeEngine(private val badges: List<Badge>) {

    fun all(): List<Badge> = badges

    fun byId(id: String): Badge? = badges.firstOrNull { it.id == id }

    fun earned(context: BadgeContext): List<Badge> = badges.filter { isEarned(it, context) }

    /** The badges earned now that were not held before — what the professor announces. */
    fun newlyEarned(context: BadgeContext, alreadyHeld: Set<String>): List<Badge> =
        earned(context).filterNot { it.id in alreadyHeld }

    private fun isEarned(badge: Badge, context: BadgeContext): Boolean =
        when (badge.condition.type) {
            BadgeCondition.LEVEL_PASSED -> badge.condition.value in context.passedLevels
            BadgeCondition.LESSONS_COMPLETED -> context.lessonsCompleted >= badge.condition.value
            BadgeCondition.STREAK_DAYS -> context.streakDays >= badge.condition.value
            BadgeCondition.MASTERY_AVERAGE -> context.masteryAverage * 100 >= badge.condition.value
            BadgeCondition.CAPSTONE_COMPLETED -> context.capstoneCompleted
            // An unknown rule must never hand out a badge by accident.
            else -> false
        }

    fun validate(): List<String> = buildList {
        val ids = badges.map { it.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Badge duplicato: '$it'") }
        badges.filter { it.condition.type !in BadgeCondition.KNOWN_TYPES }
            .forEach { add("Il badge '${it.id}' usa una condizione sconosciuta: '${it.condition.type}'") }
        badges.filter { it.description.length < 30 }
            .forEach { add("Il badge '${it.id}' non spiega perché è stato guadagnato") }
        badges.filter { it.icon.isBlank() }
            .forEach { add("Il badge '${it.id}' non ha un simbolo") }
    }

    companion object {
        const val RESOURCE_PATH = "/badges/badges.json"

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): BadgeEngine = BadgeEngine(json.decodeFromString<BadgeContent>(raw).badges)

        fun fromResources(path: String = RESOURCE_PATH): BadgeEngine {
            val stream = BadgeEngine::class.java.getResourceAsStream(path)
                ?: error("Elenco dei badge non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
