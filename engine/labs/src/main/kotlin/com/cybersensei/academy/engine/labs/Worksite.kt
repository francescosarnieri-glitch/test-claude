package com.cybersensei.academy.engine.labs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One thing a student can type into the imaginary form.
 *
 * The inputs are a fixed list, chosen deliberately: a free text box connected to a template
 * that shows "what the server would run" is a payload workshop, and this school does not
 * ship one. Here the student picks from prepared examples and watches what the *application*
 * does with them — the lesson is about the code's mistake, not about the string.
 */
@Serializable
data class Attempt(
    val id: String,
    val label: String,
    /** What the student types. Ordinary values as well as awkward ones. */
    val input: String,
    /** What the vulnerable version ends up doing, written out as prose plus a fragment. */
    @SerialName("naive_outcome") val naiveOutcome: String,
    @SerialName("naive_result") val naiveResult: String,
    /** What the corrected version does with the same input. */
    @SerialName("safe_outcome") val safeOutcome: String,
    @SerialName("safe_result") val safeResult: String,
    /** Whether the naive version actually misbehaves on this input. */
    val breaks: Boolean,
    val explanation: String,
)

@Serializable
data class Worksite(
    val title: String,
    val briefing: String,
    /** The two versions of the same line of code, side by side. */
    @SerialName("naive_code") val naiveCode: String,
    @SerialName("safe_code") val safeCode: String,
    val attempts: List<Attempt>,
    val debriefing: String,
) {
    fun validate(): List<String> = buildList {
        attempts.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Tentativo duplicato: '$it'") }
        if (attempts.none { it.breaks }) add("Nessun tentativo rompe niente: non c'è niente da vedere")
        // Ordinary input has to be in the list, or the student learns that apostrophes are
        // an attack rather than that concatenation is a mistake.
        if (attempts.none { !it.breaks }) {
            add("Nessun tentativo innocuo: sembrerebbe che il problema siano i caratteri strani")
        }
        attempts.filter { it.explanation.length < 60 }
            .forEach { add("Il tentativo '${it.id}' non è spiegato abbastanza") }
        if (safeCode.isBlank() || naiveCode.isBlank()) add("Manca una delle due versioni del codice")
    }

    companion object {
        const val RESOURCE_PATH = "/laboratori/cantiere.json"
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): Worksite = json.decodeFromString(raw)

        fun fromResources(path: String = RESOURCE_PATH): Worksite {
            val stream = Worksite::class.java.getResourceAsStream(path)
                ?: error("Cantiere non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
