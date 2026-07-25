package com.cybersensei.academy.engine.nlu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One thing the professor can answer, plus the ways a student might ask for it. */
@Serializable
data class FaqEntry(
    val id: String,
    val question: String,
    val answer: String,
    /** Other phrasings, including the wrong-but-common ones students actually type. */
    val aliases: List<String> = emptyList(),
    /** Which level introduces this, so the professor can say "ci arriveremo". */
    val level: Int = 0,
    @SerialName("skill") val skillId: String? = null,
) {
    /**
     * The searchable fields, with how much each one counts.
     *
     * The question itself is the strongest signal of what an entry is *about*; the aliases
     * are the ways students really phrase it; the answer adds vocabulary that makes the
     * entry findable through words the question never uses — which is how "come mi difendo
     * dal phishing" stops landing on the ransomware entry just because that one happened to
     * contain the verb "difendersi".
     */
    val weightedFields: List<Pair<String, Int>>
        get() = listOf(question to 3, aliases.joinToString(" ") to 2, answer to 1)
}

@Serializable
data class FaqContent(
    /** Domain vocabulary: every alternative maps onto a single canonical term. */
    val synonyms: Map<String, List<String>> = emptyMap(),
    val entries: List<FaqEntry>,
)

class KnowledgeBase(val content: FaqContent) {

    val entries: List<FaqEntry> get() = content.entries

    /** Reverse lookup built once: written form -> canonical term. */
    val synonymMap: Map<String, String> = buildMap {
        content.synonyms.forEach { (canonical, alternatives) ->
            put(ItalianText.stem(ItalianText.normalise(canonical)), canonical)
            alternatives.forEach { put(ItalianText.stem(ItalianText.normalise(it)), canonical) }
        }
    }

    fun validate(): List<String> = buildList {
        val ids = entries.map { it.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Domanda duplicata: '$it'") }
        entries.filter { it.question.isBlank() }.forEach { add("Domanda vuota: '${it.id}'") }
        entries.filter { it.answer.isBlank() }.forEach { add("Risposta vuota: '${it.id}'") }
        entries.filter { it.answer.length < 40 }
            .forEach { add("Risposta troppo sbrigativa per '${it.id}': lo studente merita di più") }
    }

    companion object {
        const val RESOURCE_PATH = "/faq/faq.json"

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): KnowledgeBase = KnowledgeBase(json.decodeFromString(raw))

        fun fromResources(path: String = RESOURCE_PATH): KnowledgeBase {
            val stream = KnowledgeBase::class.java.getResourceAsStream(path)
                ?: error("Domande frequenti non trovate: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
