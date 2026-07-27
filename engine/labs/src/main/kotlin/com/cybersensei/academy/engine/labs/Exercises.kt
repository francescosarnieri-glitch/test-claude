package com.cybersensei.academy.engine.labs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a message in the suspicious inbox really is. */
@Serializable
enum class MessageVerdict(val italianName: String) {
    @SerialName("legittima") LEGITIMATE("Legittima"),
    @SerialName("phishing") PHISHING("Phishing"),
    @SerialName("spam") SPAM("Spam"),
}

/**
 * A clue inside a message.
 *
 * [visible] is the exact fragment the student could have noticed; the professor reveals it
 * afterwards, which is the part that turns guessing into looking.
 */
@Serializable
data class Clue(
    val visible: String,
    val explanation: String,
    /** A clue that on its own settles the question. */
    val decisive: Boolean = false,
)

@Serializable
data class InboxMessage(
    val id: String,
    val from: String,
    @SerialName("from_address") val fromAddress: String,
    val subject: String,
    val body: String,
    val verdict: MessageVerdict,
    val clues: List<Clue> = emptyList(),
    /** Why it is what it is, said plainly once the student has answered. */
    val explanation: String,
)

@Serializable
data class Inbox(val messages: List<InboxMessage>) {
    fun validate(): List<String> = buildList {
        messages.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Messaggio duplicato: '$it'") }
        messages.filter { it.explanation.length < 40 }
            .forEach { add("Il messaggio '${it.id}' non è spiegato abbastanza") }
        // A legitimate message with no clues is fine; a phishing one without any is a trap.
        messages.filter { it.verdict == MessageVerdict.PHISHING && it.clues.isEmpty() }
            .forEach { add("Il phishing '${it.id}' non ha indizi: sarebbe indovinabile solo a caso") }
        MessageVerdict.entries.forEach { verdict ->
            if (messages.none { it.verdict == verdict }) {
                add("Nessun messaggio di tipo ${verdict.italianName}: l'esercizio sarebbe sbilanciato")
            }
        }
    }

    companion object {
        const val RESOURCE_PATH = "/laboratori/casella.json"
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): Inbox = json.decodeFromString(raw)

        fun fromResources(path: String = RESOURCE_PATH): Inbox {
            val stream = Inbox::class.java.getResourceAsStream(path)
                ?: error("Casella non trovata: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}

// --- The anomaly hunt -------------------------------------------------------------------

@Serializable
data class LogLine(
    val id: String,
    val text: String,
    /** True for the handful of lines that actually tell the story of the intrusion. */
    val guilty: Boolean = false,
    /** Why this line matters, or why it looks like it does and does not. */
    val note: String? = null,
)

@Serializable
data class LogHunt(
    val title: String,
    val briefing: String,
    val lines: List<LogLine>,
    val debriefing: String,
) {
    val guiltyLines: List<LogLine> get() = lines.filter { it.guilty }

    fun validate(): List<String> = buildList {
        lines.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Riga duplicata: '$it'") }
        if (guiltyLines.isEmpty()) add("Nessuna riga colpevole: non c'è niente da trovare")
        if (guiltyLines.size > lines.size / 4) {
            add("Troppe righe colpevoli: trovarle sarebbe banale")
        }
        guiltyLines.filter { it.note.isNullOrBlank() }
            .forEach { add("La riga colpevole '${it.id}' non è spiegata") }
        if (lines.size < MINIMUM_LINES) {
            add("Solo ${lines.size} righe: l'ago si trova solo se il pagliaio è un pagliaio")
        }
    }

    companion object {
        const val RESOURCE_PATH = "/laboratori/anomalia.json"
        const val MINIMUM_LINES = 40
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): LogHunt = json.decodeFromString(raw)

        fun fromResources(path: String = RESOURCE_PATH): LogHunt {
            val stream = LogHunt::class.java.getResourceAsStream(path)
                ?: error("Registro non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}

/** How a hunt went: what was found, what was missed, what was accused wrongly. */
data class HuntResult(
    val found: List<LogLine>,
    val missed: List<LogLine>,
    val falseAccusations: List<LogLine>,
) {
    val perfect: Boolean get() = missed.isEmpty() && falseAccusations.isEmpty()
}

fun LogHunt.judge(selectedIds: Set<String>): HuntResult {
    val selected = lines.filter { it.id in selectedIds }
    return HuntResult(
        found = selected.filter { it.guilty },
        missed = guiltyLines.filterNot { it.id in selectedIds },
        falseAccusations = selected.filterNot { it.guilty },
    )
}
