package com.cybersensei.academy.engine.nlu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What kind of thing an entry is, because the two kinds are found in opposite ways.
 *
 * A question about the syllabus is found by its rare words — "esfiltrazione" appears in one
 * place and settles it. A question about the professor himself is made almost entirely of
 * the words a search engine throws away: "chi sei", "come ti chiami", "cosa sai fare". Run
 * those through the same pipeline and nothing is left to search with, which is exactly how
 * the professor came to have no answer to the first question anybody asks him.
 */
enum class EntryKind {
    /** Subject matter. Retrieved by meaning, against the whole corpus. */
    LESSON,

    /** The professor, the app, the student's own doubts about both. Matched by phrasing. */
    CONVERSATION,

    /**
     * A question about the student themselves, answered from the school's own records:
     * when they enrolled, how long the streak is, where they are weakest.
     *
     * The answer is a template. It has to be, because the answer is different for every
     * student and different tomorrow from today — and it is the one kind of question no
     * general-purpose assistant on earth could answer, because none of them know who is
     * asking.
     */
    FACT,

    /**
     * A question about this conversation: what has been asked so far, what was just said.
     * Also a template, filled from what the professor remembers of the last few minutes.
     */
    MEMORY,
}

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
    val kind: EntryKind = EntryKind.LESSON,
    /**
     * What to say when the template has nothing to fill itself with — no questions asked
     * yet, no streak, nothing measured. Without it the student would read a sentence with
     * a hole in it, which is worse than a plain "non ancora".
     */
    val empty: String? = null,
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

    /** Entries whose answer is a template rather than a finished sentence. */
    val templateEntries: List<FaqEntry>
        get() = entries.filter { it.kind == EntryKind.FACT || it.kind == EntryKind.MEMORY }

    fun validate(): List<String> = buildList {
        val ids = entries.map { it.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Domanda duplicata: '$it'") }
        entries.filter { it.question.isBlank() }.forEach { add("Domanda vuota: '${it.id}'") }
        entries.filter { it.answer.isBlank() }.forEach { add("Risposta vuota: '${it.id}'") }
        entries.filter { it.answer.length < 40 }
            .forEach { add("Risposta troppo sbrigativa per '${it.id}': lo studente merita di più") }
        // A template with no fallback leaves a hole in a sentence the first time the record
        // is empty — which is exactly the moment a new student reads it.
        templateEntries.filter { it.empty.isNullOrBlank() }
            .forEach { add("La voce '${it.id}' si riempie di dati, ma non dice niente quando non ce ne sono") }
        templateEntries.filter { !it.answer.contains('{') }
            .forEach { add("La voce '${it.id}' e' dichiarata dinamica ma non usa nessun dato") }
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
