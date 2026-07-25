package com.cybersensei.academy.engine.nlu

import kotlin.math.ln
import kotlin.math.sqrt

/** What came back when the student asked something. */
sealed interface AnswerResult {
    data class Found(val entry: FaqEntry, val score: Double, val alternatives: List<FaqEntry>) : AnswerResult
    /**
     * Nothing matched well enough. The professor says so honestly instead of inventing —
     * [nearest] is what he offers as the closest thing he does know.
     */
    data class NotUnderstood(val nearest: FaqEntry?, val bestScore: Double) : AnswerResult
}

/**
 * Retrieval over the FAQ corpus, entirely offline.
 *
 * Two scores are mixed. TF-IDF cosine similarity asks "how similar are these two texts",
 * which is good at ranking but easily fooled by a long entry that repeats a common word.
 * Coverage asks the sharper question — "how much of what made this query specific does the
 * entry actually contain" — with each term weighted by how rare it is across the corpus.
 *
 * Before any of that, questions are reduced to what they are *about*: synonyms are folded
 * ("pwd" becomes "password"), typos are repaired against the vocabulary with a tolerance
 * that scales with word length, and verbs of intent are dropped, because in a school about
 * defending things "come mi difendo da X" means X.
 */
class QuestionAnswerer(
    private val knowledgeBase: KnowledgeBase,
    private val config: Config = Config(),
) {
    data class Config(
        /** Below this score the professor admits he does not know. */
        val acceptThreshold: Double = 0.38,
        /**
         * How the two halves of the score are mixed.
         *
         * Cosine similarity alone rewards documents that happen to repeat a common word:
         * "app craccate" lands on the permissions lesson because "app" is everywhere, even
         * though "craccate" appears in exactly one place. Coverage fixes that by asking a
         * different question — how much of the *informative* part of the query did this
         * document actually contain — with each term weighted by how rare it is.
         */
        val cosineWeight: Double = 0.55,
        val coverageWeight: Double = 0.45,
        /** How many other candidates to offer as "forse intendevi". */
        val alternativesCount: Int = 2,
        /**
         * An alternative is only worth showing if it is in the same league as the winner.
         * A fixed floor would offer unrelated topics whenever the best match was strong.
         */
        val alternativeRelativeFloor: Double = 0.55,
        /**
         * Typo tolerance scales with how long the word is: two edits on a short word turn
         * it into a different word entirely ("domani" became "domanda" and dragged a
         * question about the weather into the syllabus), while a long technical term can
         * absorb two without ambiguity.
         */
        val maxTypoDistanceShortWord: Int = 1,
        val maxTypoDistanceLongWord: Int = 2,
        val longWordLength: Int = 8,
        /** Short words are not repaired: too many real words sit one edit apart. */
        val minLengthForTypoRepair: Int = 5,
    )

    private val documents: List<Document> = knowledgeBase.entries.map { entry ->
        val counts = mutableMapOf<String, Int>()
        entry.weightedFields.forEach { (text, weight) ->
            termFrequencies(text).forEach { (term, count) ->
                counts.merge(term, count * weight, Int::plus)
            }
        }
        Document(entry, counts)
    }

    private val documentFrequency: Map<String, Int> = buildMap {
        documents.forEach { document ->
            document.termFrequency.keys.forEach { term -> merge(term, 1, Int::plus) }
        }
    }

    private val vocabulary: Set<String> = documentFrequency.keys

    private val documentVectors: List<Pair<Document, Map<String, Double>>> =
        documents.map { it to it.termFrequency.toTfIdfVector() }

    fun ask(question: String): AnswerResult {
        val queryTerms = termFrequencies(question, repairTypos = true)
        val queryVector = queryTerms.toTfIdfVector()
        if (queryVector.isEmpty()) {
            return AnswerResult.NotUnderstood(nearest = null, bestScore = 0.0)
        }

        val ranked = documentVectors
            .map { (document, vector) ->
                val score = config.cosineWeight * cosine(queryVector, vector) +
                    config.coverageWeight * coverage(queryTerms.keys, document)
                document.entry to score
            }
            .sortedByDescending { it.second }

        val (bestEntry, bestScore) = ranked.first()
        return if (bestScore >= config.acceptThreshold) {
            AnswerResult.Found(
                entry = bestEntry,
                score = bestScore,
                alternatives = ranked.drop(1)
                    .filter { it.second >= bestScore * config.alternativeRelativeFloor }
                    .take(config.alternativesCount)
                    .map { it.first },
            )
        } else {
            AnswerResult.NotUnderstood(
                nearest = bestEntry.takeIf { bestScore > 0.0 },
                bestScore = bestScore,
            )
        }
    }

    /** Exposed for tests and for the content tools: how a question is seen by the engine. */
    fun termsOf(text: String, repairTypos: Boolean = true): List<String> =
        ItalianText.terms(text).map { canonicalise(it, repairTypos) }

    private fun termFrequencies(text: String, repairTypos: Boolean = false): Map<String, Int> =
        termsOf(text, repairTypos).groupingBy { it }.eachCount()

    /** Folds synonyms together and, optionally, repairs an obvious typo. */
    private fun canonicalise(term: String, repairTypos: Boolean): String {
        knowledgeBase.synonymMap[term]?.let { return ItalianText.stem(ItalianText.normalise(it)) }
        if (!repairTypos || term in vocabulary || term.length < config.minLengthForTypoRepair) {
            return term
        }
        val tolerance = if (term.length >= config.longWordLength) {
            config.maxTypoDistanceLongWord
        } else {
            config.maxTypoDistanceShortWord
        }
        val closest = vocabulary
            .filter { kotlin.math.abs(it.length - term.length) <= tolerance }
            .minByOrNull { ItalianText.editDistance(term, it, tolerance) }
            ?: return term
        return if (ItalianText.editDistance(closest, term, tolerance) <= tolerance) closest else term
    }

    private fun Map<String, Int>.toTfIdfVector(): Map<String, Double> {
        val total = values.sum().toDouble()
        if (total == 0.0) return emptyMap()
        // Smoothed IDF: a term nobody else uses is the most informative one there is.
        return mapValues { (term, count) -> (count / total) * idf(term) }
    }

    /**
     * How much of the query's meaning this document contains, counting each term by how
     * rare it is. A document holding the one unusual word of the question scores high even
     * if it is long; a document holding only the ordinary words scores near zero.
     */
    private fun coverage(queryTerms: Set<String>, document: Document): Double {
        var matched = 0.0
        var total = 0.0
        queryTerms.forEach { term ->
            val weight = idf(term)
            total += weight
            if (document.termFrequency.containsKey(term)) matched += weight
        }
        return if (total == 0.0) 0.0 else matched / total
    }

    private fun idf(term: String): Double =
        ln((documents.size + 1.0) / ((documentFrequency[term] ?: 0) + 1.0)) + 1.0

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = if (a.size < b.size) a.keys else b.keys
        var dot = 0.0
        shared.forEach { term ->
            val left = a[term] ?: return@forEach
            val right = b[term] ?: return@forEach
            dot += left * right
        }
        if (dot == 0.0) return 0.0
        val normA = sqrt(a.values.sumOf { it * it })
        val normB = sqrt(b.values.sumOf { it * it })
        return dot / (normA * normB)
    }

    private class Document(val entry: FaqEntry, val termFrequency: Map<String, Int>)
}
