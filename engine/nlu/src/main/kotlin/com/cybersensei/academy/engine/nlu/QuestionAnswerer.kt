package com.cybersensei.academy.engine.nlu

import kotlin.math.ln
import kotlin.math.sqrt

/** What came back when the student asked something. */
sealed interface AnswerResult {
    data class Found(val entry: FaqEntry, val score: Double, val alternatives: List<FaqEntry>) : AnswerResult
    /**
     * Two entries fit almost equally well, and picking one would be a coin toss dressed up
     * as an answer. The professor asks which, instead — the student knows what they meant,
     * and one tap is cheaper for them than a confident answer to the other question.
     */
    data class Ambiguous(val options: List<FaqEntry>, val scores: List<Double>) : AnswerResult

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
         * How close the runner-up has to be before the professor stops choosing and asks.
         *
         * Ninety-six per cent of the winner's score: only a near-tie. At ninety-two the
         * professor started asking about questions he had a clear answer to, and a
         * clarification nobody needed is its own kind of unhelpful.
         */
        val ambiguityRatio: Double = 0.96,
        /**
         * And only when the winner is a strong match to begin with.
         *
         * A vague one-word question — "password" — puts half the corpus within a whisker of
         * itself, and there the right move is the best answer plus the near misses, not an
         * interrogation. Asking "quale delle due?" is worth it only when both candidates are
         * solid and the ranking between them is a coin toss.
         */
        val ambiguityFloor: Double = 0.5,
        /**
         * And only for a question with more than one informative word.
         *
         * A single word — "password", "backup" — is not two readings of a sentence, it is a
         * topic with no sentence around it. There the honest move is the best answer with the
         * near misses beside it; asking "quale delle due?" about a bare keyword is passing
         * the vagueness back to the student unhelpfully.
         */
        val ambiguityMinimumTerms: Int = 2,
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
        /**
         * What a repaired word is worth compared to one the student actually wrote.
         *
         * Half, because a repair is a guess. "Come ti chiami" used to be answered with the
         * chain of custody: "chiami" is not in the vocabulary, sits one edit from "chiave",
         * and that single invented word was enough to carry a confident wrong answer. A
         * guess can still tip a match that other words already support — it can no longer
         * produce one on its own. Three quarters rather than half: at half, "come riconosco
         * unemail di phishng" stopped being understood, and repairing genuine typing is the
         * whole reason the repairer exists. The conversational stage now catches the case
         * that made the discount necessary, so what is left here is a light touch.
         */
        val repairedTermWeight: Double = 0.9,
    )

    /**
     * Only the syllabus is retrieved this way. Conversational entries live in the matcher
     * below: run through this pipeline they would lose every word they are made of, and
     * they would also pollute the vocabulary that repairs the student's typos.
     */
    private val documents: List<Document> = knowledgeBase.entries
        .filter { it.kind == EntryKind.LESSON }
        .map { entry ->
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

    private val conversation =
        ConversationMatcher(knowledgeBase.entries, documentFrequency, documents.size)

    private val documentVectors: List<Pair<Document, Map<String, Double>>> =
        documents.map { it to it.termFrequency.toTfIdfVector() }

    fun ask(question: String): AnswerResult {
        // Asked first, and answered on the spot when it fires: "chi sei" is not a question
        // about the syllabus, and sending it through retrieval is how it came back as
        // silence — or, worse, as the chain of custody.
        conversation.match(question)?.let { spoken ->
            return AnswerResult.Found(spoken.entry, spoken.score, alternatives = emptyList())
        }

        if (documents.isEmpty()) return AnswerResult.NotUnderstood(nearest = null, bestScore = 0.0)
        val asked = weighQuestion(question)
        val queryVector = asked.weightedTfIdf()
        if (queryVector.isEmpty()) {
            return AnswerResult.NotUnderstood(nearest = null, bestScore = 0.0)
        }

        val ranked = documentVectors
            .map { (document, vector) ->
                val score = config.cosineWeight * cosine(queryVector, vector) +
                    config.coverageWeight * coverage(asked, document)
                document.entry to score
            }
            .sortedByDescending { it.second }

        val (bestEntry, bestScore) = ranked.first()
        val (runnerUp, runnerUpScore) = ranked.getOrNull(1) ?: (null to 0.0)
        if (bestScore >= config.ambiguityFloor &&
            asked.size >= config.ambiguityMinimumTerms &&
            runnerUp != null &&
            runnerUpScore >= bestScore * config.ambiguityRatio
        ) {
            return AnswerResult.Ambiguous(
                options = listOf(bestEntry, runnerUp),
                scores = listOf(bestScore, runnerUpScore),
            )
        }

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
        return if (repairTypos) repair(term) else term
    }

    /** The closest word in the corpus, when the one written is close enough to be a slip. */
    private fun repair(term: String): String {
        if (term in vocabulary || term.length < config.minLengthForTypoRepair) {
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

    private fun Map<String, Int>.toTfIdfVector(): Map<String, Double> =
        mapValues { (_, count) -> count.toDouble() }.weightedTfIdf()

    private fun Map<String, Double>.weightedTfIdf(): Map<String, Double> {
        val total = values.sum()
        if (total == 0.0) return emptyMap()
        // Smoothed IDF: a term nobody else uses is the most informative one there is.
        return mapValues { (term, weight) -> (weight / total) * idf(term) }
    }

    /**
     * The student's question as the engine will weigh it: every word they wrote counts once,
     * every word the repairer had to invent for them counts half.
     */
    private fun weighQuestion(question: String): Map<String, Double> = buildMap {
        ItalianText.terms(question).forEach { written ->
            val folded = knowledgeBase.synonymMap[written]
                ?.let { ItalianText.stem(ItalianText.normalise(it)) }
            val term = folded ?: repair(written)
            val guessed = folded == null && term != written
            merge(term, if (guessed) config.repairedTermWeight else 1.0, Double::plus)
        }
    }

    /**
     * How much of the query's meaning this document contains, counting each term by how
     * rare it is. A document holding the one unusual word of the question scores high even
     * if it is long; a document holding only the ordinary words scores near zero.
     */
    private fun coverage(asked: Map<String, Double>, document: Document): Double {
        var matched = 0.0
        var total = 0.0
        asked.forEach { (term, confidence) ->
            val weight = idf(term) * confidence
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
