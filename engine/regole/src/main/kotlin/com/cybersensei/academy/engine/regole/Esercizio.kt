package com.cybersensei.academy.engine.regole

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the professor says about a run, chosen by how it went. */
@Serializable
data class Giudizio(
    val titolo: String,
    val corpo: String,
)

/**
 * One rule to write.
 *
 * [bersagli] is the answer key: the events that are the attack. It is never shown before the
 * student has run something — the whole exercise is finding them — but it is what makes the
 * verdict a measurement instead of an opinion.
 */
@Serializable
data class Esercizio(
    val id: String,
    val titolo: String,
    val sottotitolo: String,
    /** The module that has to have been studied for this to open. */
    @SerialName("apre_con") val apreCon: String,
    val livello: Int,
    @SerialName("registro") val registroId: String,
    val briefing: String,
    /** What the student is being asked to catch, in one sentence. */
    val obiettivo: String,
    @SerialName("bersagli") val bersagli: List<String>,
    /** Shown on demand, and it costs nothing but pride. */
    val indizio: String,
    /** A rule that solves it. Shown only after the exercise has been solved or given up on. */
    @SerialName("regola_modello") val regolaModello: String,
    /** Why that rule, and what the near misses teach. */
    val spiegazione: String,
    @SerialName("se_perfetta") val sePerfetta: Giudizio,
    @SerialName("se_rumorosa") val seRumorosa: Giudizio,
    @SerialName("se_incompleta") val seIncompleta: Giudizio,
    @SerialName("se_muta") val seMuta: Giudizio,
    /** The skills a solved exercise credits, same vocabulary as everywhere else. */
    val skills: List<String> = emptyList(),
) {
    /** Which of the four things the professor says applies to this run. */
    fun giudizio(esito: Esito): Giudizio = when {
        esito.muta -> seMuta
        esito.perfetto -> sePerfetta
        esito.persi.isNotEmpty() -> seIncompleta
        else -> seRumorosa
    }
}

@Serializable
data class Palestra(
    val registri: List<LogSource>,
    val esercizi: List<Esercizio>,
) {
    fun registro(id: String): LogSource? = registri.firstOrNull { it.id == id }

    fun esercizio(id: String): Esercizio? = esercizi.firstOrNull { it.id == id }

    fun perLivello(livello: Int): List<Esercizio> = esercizi.filter { it.livello == livello }

    /** The exercise's own log, or nothing — never a fallback, which would silently mislead. */
    fun registroDi(esercizio: Esercizio): LogSource? = registro(esercizio.registroId)

    fun validate(): List<String> = buildList {
        registri.forEach { addAll(it.validate()) }

        registri.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Registro duplicato: '$it'") }
        esercizi.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Esercizio duplicato: '$it'") }

        esercizi.forEach { esercizio ->
            val registro = registro(esercizio.registroId)
            if (registro == null) {
                add("L'esercizio '${esercizio.id}' usa il registro '${esercizio.registroId}', che non esiste")
                return@forEach
            }
            val ids = registro.eventi.map { it.id }.toSet()
            esercizio.bersagli.filterNot { it in ids }.forEach {
                add("L'esercizio '${esercizio.id}' indica il bersaglio '$it', che non è nel registro")
            }
            if (esercizio.bersagli.isEmpty()) {
                add("L'esercizio '${esercizio.id}' non ha bersagli: non c'è niente da trovare")
            }
            if (esercizio.bersagli.size == registro.eventi.size) {
                add(
                    "Nell'esercizio '${esercizio.id}' tutto è bersaglio: una regola che prende " +
                        "tutto sarebbe perfetta, e non si imparerebbe niente",
                )
            }

            // The model answer has to actually solve it. Without this check an exercise can
            // ship whose stated solution the engine scores as wrong — and the student, who is
            // right, is told they are not.
            val analizzatore = Analizzatore(registro.campi)
            when (val lettura = analizzatore.leggi(esercizio.regolaModello)) {
                is Lettura.Fallita -> add(
                    "La regola modello di '${esercizio.id}' non si legge: ${lettura.errore.messaggio}",
                )
                is Lettura.Riuscita -> {
                    val esito = MotoreRegole.esegui(
                        lettura.regola,
                        registro,
                        esercizio.bersagli.toSet(),
                    )
                    if (!esito.perfetto) {
                        add(
                            "La regola modello di '${esercizio.id}' non risolve l'esercizio: " +
                                "${esito.presi.size} presi, ${esito.persi.size} persi, ${esito.falsi.size} falsi",
                        )
                    }
                }
            }

            if (esercizio.spiegazione.length < MINIMA_SPIEGAZIONE) {
                add("L'esercizio '${esercizio.id}' non spiega abbastanza perché quella regola")
            }
            if (esercizio.indizio.isBlank()) {
                add("L'esercizio '${esercizio.id}' non ha un indizio: chi si blocca resta bloccato")
            }
        }

        if (esercizi.isEmpty()) add("Non c'è nessun esercizio")
    }

    companion object {
        const val RESOURCE_PATH = "/regole/palestra.json"
        private const val MINIMA_SPIEGAZIONE = 120

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): Palestra = json.decodeFromString(raw)

        fun fromResources(path: String = RESOURCE_PATH): Palestra {
            val stream = Palestra::class.java.getResourceAsStream(path)
                ?: error("Palestra delle regole non trovata: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
