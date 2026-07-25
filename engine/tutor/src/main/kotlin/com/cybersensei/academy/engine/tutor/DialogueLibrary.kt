package com.cybersensei.academy.engine.tutor

import com.cybersensei.academy.core.common.DayPart
import com.cybersensei.academy.core.model.TutorTone
import com.cybersensei.academy.engine.mastery.AnswerVerdict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A set of interchangeable ways to say the same thing in the same situation. */
@Serializable
data class LinePool(
    val id: String,
    val lines: List<String>,
)

/**
 * When a pool applies. Every field is optional; a rule with no conditions is the safety net
 * that guarantees the professor always has something to say.
 */
@Serializable
data class RuleConditions(
    @SerialName("day_parts") val dayParts: List<DayPart>? = null,
    val verdicts: List<AnswerVerdict>? = null,
    val tones: List<TutorTone>? = null,
    @SerialName("min_streak") val minStreak: Int? = null,
    @SerialName("max_streak") val maxStreak: Int? = null,
    @SerialName("min_absence_days") val minAbsenceDays: Int? = null,
    @SerialName("min_mastery") val minMastery: Double? = null,
    @SerialName("max_mastery") val maxMastery: Double? = null,
    @SerialName("min_reviews_due") val minReviewsDue: Int? = null,
    @SerialName("requires_known_student") val requiresKnownStudent: Boolean? = null,
    @SerialName("requires_birthday") val requiresBirthday: Boolean = false,
    @SerialName("requires_unfinished_lesson") val requiresUnfinishedLesson: Boolean = false,
    @SerialName("requires_recurring_misconception") val requiresRecurringMisconception: Boolean = false,
) {
    val isUnconditional: Boolean
        get() = dayParts == null && verdicts == null && tones == null && minStreak == null &&
            maxStreak == null && minAbsenceDays == null && minMastery == null &&
            maxMastery == null && minReviewsDue == null && requiresKnownStudent == null &&
            !requiresBirthday && !requiresUnfinishedLesson && !requiresRecurringMisconception
}

/**
 * "In this situation, say something from that pool." Higher [priority] wins; the most
 * specific rules therefore carry the highest numbers and the safety nets the lowest.
 */
@Serializable
data class DialogueRule(
    val id: String,
    val event: String,
    val pool: String,
    val priority: Int = 0,
    @SerialName("when") val conditions: RuleConditions = RuleConditions(),
)

@Serializable
data class DialogueContent(
    val pools: List<LinePool>,
    val rules: List<DialogueRule>,
)

/**
 * The professor's script: pools of sentences plus the rules that decide which pool fits the
 * moment. Loaded from JSON so that writing new material never means touching Kotlin.
 */
class DialogueLibrary(content: DialogueContent) {

    private val poolsById: Map<String, LinePool> = content.pools.associateBy { it.id }
    private val rulesByEvent: Map<String, List<DialogueRule>> =
        content.rules.groupBy { it.event }.mapValues { (_, rules) -> rules.sortedByDescending { it.priority } }

    val pools: Collection<LinePool> get() = poolsById.values
    val rules: List<DialogueRule> get() = rulesByEvent.values.flatten()

    fun poolOf(id: String): LinePool? = poolsById[id]

    /** Rules for an event, most specific first. */
    fun rulesFor(eventKey: String): List<DialogueRule> = rulesByEvent[eventKey].orEmpty()

    /**
     * Structural problems that must never reach a student's phone: a rule pointing at a pool
     * that does not exist, an empty pool, or an event the professor could not answer at all.
     */
    fun validate(): List<String> = buildList {
        rules.forEach { rule ->
            val pool = poolsById[rule.pool]
            when {
                pool == null -> add("La regola '${rule.id}' punta al pool inesistente '${rule.pool}'")
                pool.lines.isEmpty() -> add("Il pool '${pool.id}' non contiene battute")
                pool.lines.any { it.isBlank() } -> add("Il pool '${pool.id}' contiene una battuta vuota")
            }
        }
        poolsById.values.filter { pool -> rules.none { it.pool == pool.id } }
            .forEach { add("Il pool '${it.id}' non è usato da nessuna regola") }

        TutorEvent.ALL_KEYS.forEach { key ->
            val eventRules = rulesFor(key)
            when {
                eventRules.isEmpty() -> add("Nessuna regola per l'evento '$key': il professore resterebbe muto")
                eventRules.none { it.conditions.isUnconditional } ->
                    add("L'evento '$key' non ha una regola di riserva senza condizioni")
            }
        }
    }

    companion object {
        const val RESOURCE_PATH = "/dialogue/professor.json"

        private val json = Json {
            ignoreUnknownKeys = false
            allowTrailingComma = false
        }

        fun parse(raw: String): DialogueLibrary = DialogueLibrary(json.decodeFromString(raw))

        /** Loads the script packaged with the app. */
        fun fromResources(path: String = RESOURCE_PATH): DialogueLibrary {
            val stream = DialogueLibrary::class.java.getResourceAsStream(path)
                ?: error("Copione del professore non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
