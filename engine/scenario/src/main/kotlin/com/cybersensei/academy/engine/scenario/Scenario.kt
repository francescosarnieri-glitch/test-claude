package com.cybersensei.academy.engine.scenario

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How good a decision was.
 *
 * Four bands rather than right/wrong, because that is how incident response actually reads:
 * most bad outcomes come from choices that were defensible in isolation and wrong in
 * sequence, and a simulation that scores them as plain errors teaches the wrong lesson.
 */
@Serializable
enum class ChoiceQuality {
    /** The move a competent responder makes. */
    @SerialName("right") RIGHT,

    /** Not wrong — costs time, information or goodwill, but nothing is broken. */
    @SerialName("defensible") DEFENSIBLE,

    /** A real mistake: the situation gets worse, or evidence is lost. */
    @SerialName("wrong") WRONG,

    /** The kind of mistake that ends careers: destroys evidence, tips off the attacker. */
    @SerialName("harmful") HARMFUL,
    ;

    val isSound: Boolean get() = this == RIGHT || this == DEFENSIBLE
}

/**
 * What a decision does to the situation.
 *
 * Three axes, because an incident is not won on one: you can contain perfectly and be unable
 * to prove anything, or preserve every byte while the attacker is still inside.
 */
@Serializable
data class Effects(
    /** Did this stop the bleeding? */
    val containment: Int = 0,
    /** Will anyone be able to reconstruct what happened? */
    val evidence: Int = 0,
    /** Are the people who depend on you still able to trust what you tell them? */
    val trust: Int = 0,
    /** Hours burned. The clock is a resource like any other. */
    @SerialName("hours") val hours: Int = 0,
    /** Facts the rest of the scenario, and the debriefing, can react to. */
    val flags: List<String> = emptyList(),
)

@Serializable
data class Choice(
    val id: String,
    val text: String,
    /** Where this leads. Null ends the run — some decisions close the incident. */
    val next: String? = null,
    val quality: ChoiceQuality,
    /** What happens because of it. Shown before any judgement, always. */
    val consequence: String,
    /** Why. Non-negotiable: no decision in this app goes unexplained. */
    val explanation: String,
    /** Micro-skills this decision exercises, so the capstone feeds the report card. */
    val skills: List<String> = emptyList(),
    val effects: Effects = Effects(),
)

@Serializable
data class Scene(
    val id: String,
    val title: String,
    /** Wall clock inside the story. Pressure is part of the exercise. */
    val clock: String? = null,
    val situation: String,
    /** Logs, alerts, a message: what the responder is actually looking at. */
    val terminal: String? = null,
    val question: String,
    val choices: List<Choice>,
)

/** One band of the final judgement, chosen by score. */
@Serializable
data class Band(
    @SerialName("min_score") val minScore: Int,
    val title: String,
    val body: String,
)

@Serializable
data class Debriefing(
    val opening: String,
    val bands: List<Band>,
    /** Extra remarks triggered by what the student actually did. */
    @SerialName("flag_notes") val flagNotes: Map<String, String> = emptyMap(),
    val closing: String,
)

@Serializable
data class Scenario(
    val id: String,
    val title: String,
    val subtitle: String,
    val briefing: String,
    val minutes: Int,
    @SerialName("start") val startSceneId: String,
    val scenes: List<Scene>,
    val debriefing: Debriefing,
) {
    fun scene(id: String): Scene? = scenes.firstOrNull { it.id == id }

    /** The best score obtainable, used to turn raw points into a percentage. */
    val maximumScore: Int
        get() = scenes.sumOf { scene ->
            scene.choices.maxOfOrNull { it.effects.score } ?: 0
        }

    /**
     * Everything that would make the scenario unplayable or unfair, found at build time
     * rather than by a student stuck on a dead end at two in the morning.
     */
    fun validate(): List<String> = buildList {
        val ids = scenes.map { it.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Scena duplicata: '$it'") }
        if (scene(startSceneId) == null) add("La scena iniziale '$startSceneId' non esiste")

        scenes.forEach { scene ->
            if (scene.choices.isEmpty()) add("La scena '${scene.id}' non offre alcuna scelta")
            if (scene.choices.none { it.quality == ChoiceQuality.RIGHT }) {
                add("La scena '${scene.id}' non ha nessuna scelta giusta: sarebbe una trappola")
            }
            scene.choices.forEach { choice ->
                if (choice.next != null && scene(choice.next) == null) {
                    add("La scelta '${choice.id}' porta alla scena inesistente '${choice.next}'")
                }
                if (choice.next == scene.id) add("La scelta '${choice.id}' torna su sé stessa")
                if (choice.explanation.isBlank()) {
                    add("La scelta '${choice.id}' non è spiegata: è il requisito centrale dell'app")
                }
                if (choice.consequence.isBlank()) {
                    add("La scelta '${choice.id}' non dice cosa succede")
                }
            }
            scene.choices.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                .forEach { add("Scelta duplicata '$it' nella scena '${scene.id}'") }
        }

        // A scene nobody can reach is content that will never be read; a run that cannot end
        // is worse. Both are content bugs, and both are cheap to catch here.
        val reachable = reachableScenes()
        ids.filterNot { it in reachable }.forEach { add("Scena irraggiungibile: '$it'") }
        if (scenes.none { scene -> scene.choices.any { it.next == null } }) {
            add("Nessuna scelta chiude lo scenario: il capstone non finirebbe mai")
        }

        if (debriefing.bands.isEmpty()) add("Il debriefing non ha alcuna fascia di giudizio")
        if (debriefing.bands.none { it.minScore <= 0 }) {
            add("Il debriefing non copre il punteggio più basso: chi va male resterebbe senza verdetto")
        }
    }

    private fun reachableScenes(): Set<String> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOfNotNull(startSceneId.takeIf { scene(it) != null }))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            scene(id)?.choices?.mapNotNull { it.next }?.forEach { queue.addLast(it) }
        }
        return seen
    }

    companion object {
        const val RESOURCE_PATH = "/scenari/incidente.json"

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): Scenario = json.decodeFromString(raw)

        fun fromResources(path: String = RESOURCE_PATH): Scenario {
            val stream = Scenario::class.java.getResourceAsStream(path)
                ?: error("Scenario non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}

/** The three axes summed. Hours never count against the score — they colour the debriefing. */
val Effects.score: Int get() = containment + evidence + trust
