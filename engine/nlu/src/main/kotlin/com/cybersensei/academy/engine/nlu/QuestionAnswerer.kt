package com.cybersensei.academy.engine.nlu

import kotlin.math.ln
import kotlin.math.sqrt

/** What came back when the student asked something. */
sealed interface AnswerResult {
    /**
     * [hedged] marks an answer the engine reached by resemblance on a question that named
     * nothing of the subject.
     *
     * It exists because one sentence cannot be told apart from a real question by any number
     * the engine has: "come si cambia una gomma dell'auto" resembles the HTTPS entry exactly
     * as much as "se mi bloccano tutti i file e vogliono soldi" resembles ransomware.
     * Refusing both would cost the second, which is the most ordinary way a person describes
     * being attacked. So the professor answers, and says he is not sure — which is what an
     * honest person does when they think they understood.
     */
    data class Found(
        val entry: FaqEntry,
        val score: Double,
        val alternatives: List<FaqEntry>,
        val hedged: Boolean = false,
    ) : AnswerResult
    /**
     * Two entries fit almost equally well, and picking one would be a coin toss dressed up
     * as an answer. The professor asks which, instead — the student knows what they meant,
     * and one tap is cheaper for them than a confident answer to the other question.
     */
    data class Ambiguous(val options: List<FaqEntry>, val scores: List<Double>) : AnswerResult

    /**
     * Nothing matched well enough. The professor says so honestly instead of inventing —
     * [nearest] is what he offers as the closest thing he does know.
     *
     * [reason] exists because "non lo so" is three different sentences. For a long time it
     * was one, written for a subject not covered yet, and asking about carbonara had the
     * professor promising to teach carbonara at the right moment.
     */
    data class NotUnderstood(
        val nearest: FaqEntry?,
        val bestScore: Double,
        val reason: Miss,
    ) : AnswerResult
}

/** Why the professor could not answer. Three situations that deserve three different replies. */
enum class Miss {
    /**
     * Nothing in the question belongs to this school's subject. Carbonara, football, the
     * weather. The honest reply names the department and points elsewhere — and must never
     * suggest the topic is merely pending.
     */
    OFF_TOPIC,

    /**
     * The question is about security, and about something the syllabus does not cover yet.
     * This is the one case where "ci arriveremo" is the truth.
     */
    NOT_COVERED,

    /** Nothing to work with at all: empty, or made only of words that carry no topic. */
    UNPARSEABLE,
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
    /**
     * The meaning-based index, when there is one.
     *
     * Optional, and consulted last on purpose. Word matching is the precise instrument: when
     * it is sure, it is right, and letting a resemblance override it would trade accuracy for
     * the appearance of cleverness. The semantic index is what happens instead of silence.
     */
    private val semantic: SemanticIndex? = null,
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
        /**
         * How much of the final order comes from meaning, once meaning is the only thing
         * left. The rest comes from the words: even a question that failed the lexical
         * threshold usually shares *something* with the right entry, and that faint signal
         * breaks ties that resemblance alone gets wrong.
         */
        val semanticWeight: Double = 0.7,
        /**
         * Above this, a conversational phrasing is essentially the one somebody wrote down,
         * and nothing else is worth consulting.
         */
        val conversationCertainty: Double = 0.9,
        /** Above this the words are sure enough that meaning is not consulted at all. */
        val confidentThreshold: Double = 0.55,
        /** And below this the two signals together still have not found anything. */
        val blendedThreshold: Double = 0.34,
        /** At or under this many informative words, a question is a keyword lookup. */
        val keywordQueryTerms: Int = 2,
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

    fun ask(rawQuestion: String): AnswerResult {
        // Expressions first: "parola d'ordine" becomes "password" before anything else looks
        // at the sentence, so every stage downstream sees the school's own vocabulary.
        val question = knowledgeBase.rewritePhrases(rawQuestion)

        // Asked first, and answered on the spot when it fires: "chi sei" is not a question
        // about the syllabus, and sending it through retrieval is how it came back as
        // silence — or, worse, as the chain of custody.
        //
        val spoken = conversation.match(question)
        // A phrasing somebody wrote down word for word: nothing else needs consulting.
        if (spoken != null && spoken.score >= config.conversationCertainty) {
            return AnswerResult.Found(spoken.entry, spoken.score, alternatives = emptyList())
        }

        if (documents.isEmpty() || weighQuestion(question).isEmpty()) {
            return spoken?.let { AnswerResult.Found(it.entry, it.score, emptyList()) }
                ?: AnswerResult.NotUnderstood(null, 0.0, Miss.UNPARSEABLE)
        }
        val asked = weighQuestion(question)
        val queryVector = asked.weightedTfIdf()
        if (queryVector.isEmpty()) {
            return AnswerResult.NotUnderstood(null, 0.0, Miss.UNPARSEABLE)
        }

        val ranked = documentVectors
            .map { (document, vector) ->
                val score = config.cosineWeight * cosine(queryVector, vector) +
                    config.coverageWeight * coverage(asked, document)
                document.entry to score
            }
            .sortedByDescending { it.second }

        val (bestEntry, bestScore) = ranked.first()

        // The conversational stage yields only to a *confident* lesson. "Posso usare la rete
        // del bar per pagare" was answered with the app's price list on the strength of
        // "usare" and "pagare"; blocking it whenever the question named any technical word
        // was worse, because "quindi sei un bot" names one too and belongs to the professor.
        if (spoken != null && !(isAboutTheSubject(question) && bestScore >= config.confidentThreshold)) {
            return AnswerResult.Found(spoken.entry, spoken.score, alternatives = emptyList())
        }

        // The uncertain band. Above it the words have spoken clearly and meaning is not
        // consulted at all; below it, a lexical match is a guess with a number attached —
        // and there the two signals decide together. Deciding by words alone in this band
        // answered "dove tengo le copie dei miei file" with the ransomware lesson.
        if (semantic != null && bestScore < config.confidentThreshold) {
            blendedAnswer(question, ranked)?.let { return it }
        }

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

        // A question with no word of the subject in it needs a *strong* match before it can
        // be answered with a lesson. Without this, "come si cambia una gomma dell'auto"
        // reached the home-router entry with a middling score and a straight face.
        // Two or fewer words is not a sentence, it is a lookup — "idor", "regola 3 2 1" —
        // and there the ordinary threshold is the right one: there was never enough context
        // for the domain test to mean anything.
        val strongEnough = if (isAboutTheSubject(question) || asked.size <= config.keywordQueryTerms) {
            config.acceptThreshold
        } else {
            config.confidentThreshold
        }

        return if (bestScore >= strongEnough) {
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
                reason = if (isAboutTheSubject(question)) Miss.NOT_COVERED else Miss.OFF_TOPIC,
            )
        }
    }

    /**
     * The answer the two signals agree on, when neither is sure on its own.
     *
     * Resemblance proposes the candidates — it is the only one that can see past the words —
     * and the words reorder them, because even a question that failed the lexical threshold
     * usually shares something with the right entry, and that faint signal is what breaks
     * ties resemblance alone gets wrong.
     */
    private fun blendedAnswer(
        question: String,
        ranked: List<Pair<FaqEntry, Double>>,
    ): AnswerResult.Found? {
        val hits = semantic?.search(question, SEMANTIC_CANDIDATES).orEmpty()
        if (hits.isEmpty()) return null

        val lexical = ranked.toMap()
        val blended = hits
            .map { hit ->
                hit.entry to config.semanticWeight * hit.score +
                    (1 - config.semanticWeight) * (lexical[hit.entry] ?: 0.0)
            }
            .sortedByDescending { it.second }

        val (entry, score) = blended.first()
        if (score < config.blendedThreshold) return null
        return AnswerResult.Found(
            entry = entry,
            score = score,
            alternatives = blended.drop(1).map { it.first }.take(config.alternativesCount),
            // Understood by resemblance, on a question with no word of the subject in it:
            // worth saying out loud rather than answering with a straight face.
            hedged = !isAboutTheSubject(question),
        )
    }

    /**
     * Whether the question is about this school's subject at all.
     *
     * Answered against the declared domain lexicon, and against what the student actually
     * wrote — before the typo repairer gets a chance to bend an unknown word into a known
     * one, which would make every misspelling look like a security term.
     *
     * The first version of this counted how rare each word was in the corpus, which was
     * wrong in both directions: "costa" appears in few lessons and made "quanto costa un
     * volo per Tokyo" a security question, while "sandboxing" appears in none and made a
     * real security question look like small talk.
     */
    fun isAboutTheSubject(question: String): Boolean =
        ItalianText.terms(question).any { written ->
            val term = knowledgeBase.synonymMap[written]
                ?.let { ItalianText.stem(ItalianText.normalise(it)) } ?: written
            term in knowledgeBase.domainStems
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
            // A word no entry contains cannot be covered by any of them, so counting it in
            // the denominator lowers every candidate equally — and on a short question it
            // lowers them below the threshold. "Mi conviene mettere una vpn" was answered
            // with the password manager: "conviene" and "mettere" are unknown to the corpus,
            // and the one word that mattered was outvoted two to one by their absence.
            if ((documentFrequency[term] ?: 0) == 0) return@forEach
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

    private companion object {
        /** How many meanings to consider before the words are allowed to reorder them. */
        const val SEMANTIC_CANDIDATES = 8
    }


}
