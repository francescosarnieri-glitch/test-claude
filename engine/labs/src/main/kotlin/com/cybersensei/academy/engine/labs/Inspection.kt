package com.cybersensei.academy.engine.labs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One thing to look at and judge.
 *
 * [detail] is shown in monospace because these exercises are all about noticing something in
 * a block of text — a domain, a permission, a port — and proportional type is very good at
 * hiding exactly that kind of difference.
 */
@Serializable
data class InspectionItem(
    val id: String,
    val title: String,
    val detail: String,
    val question: String,
    val options: List<String>,
    /** Index into [options]. */
    val correct: Int,
    val explanation: String,
    val clues: List<Clue> = emptyList(),
)

/**
 * The shape shared by three of the workshops: certificates, packets and app manifests.
 *
 * They differ only in what is being looked at. Giving them one engine and one screen is not
 * a shortcut — it is the honest observation that "guarda, decidi, scopri cosa ti era
 * sfuggito" is a single exercise applied to three subjects.
 */
@Serializable
data class InspectionLab(
    val id: String,
    val title: String,
    val briefing: String,
    val items: List<InspectionItem>,
    val debriefing: String,
) {
    fun validate(): List<String> = buildList {
        items.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Elemento duplicato: '$it'") }
        if (items.size < MINIMUM_ITEMS) add("Solo ${items.size} elementi: troppo pochi per un laboratorio")
        items.forEach { item ->
            if (item.options.size < 2) add("L'elemento '${item.id}' non offre una scelta")
            if (item.correct !in item.options.indices) {
                add("L'elemento '${item.id}' indica una risposta corretta che non esiste")
            }
            if (item.explanation.length < 60) {
                add("L'elemento '${item.id}' non è spiegato abbastanza: è il requisito centrale")
            }
            if (item.detail.isBlank()) add("L'elemento '${item.id}' non ha niente da ispezionare")
        }
        // If every item has the same answer, the exercise is a button, not a judgement.
        if (items.map { it.correct }.distinct().size < 2) {
            add("Tutti gli elementi hanno la stessa risposta: basterebbe premere sempre lì")
        }
    }

    companion object {
        const val MINIMUM_ITEMS = 4
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): InspectionLab = json.decodeFromString(raw)

        fun fromResources(path: String): InspectionLab {
            val stream = InspectionLab::class.java.getResourceAsStream(path)
                ?: error("Laboratorio non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}

/** An answered item. */
data class InspectionAnswer(val itemId: String, val chosen: Int) {
    fun isRight(item: InspectionItem): Boolean = item.correct == chosen
}
