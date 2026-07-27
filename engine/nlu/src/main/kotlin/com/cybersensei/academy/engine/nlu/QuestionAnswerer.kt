package com.cybersensei.academy.engine.nlu

import kotlin.math.ln
import kotlin.math.sqrt

/** What came back when the student asked something. */
sealed interface AnswerResult {
    /**
     * An answer the professor is prepared to state: the student's own words named it.
     *
     * There used to be a [Found] the engine had reached by resemblance alone, carrying a
     * "not sure" label. The label was the honest half of a dishonest shape — it still put a
     * finished answer in front of somebody who had asked something else. Those cases are
     * [Unsure] now, and are offered rather than asserted.
     */
    data class Found(
        val entry: FaqEntry,
        val score: Double,
        val alternatives: List<FaqEntry>,
    ) : AnswerResult
    /**
     * Two entries fit almost equally well, and picking one would be a coin toss dressed up
     * as an answer. The professor asks which, instead — the student knows what they meant,
     * and one tap is cheaper for them than a confident answer to the other question.
     */
    data class Ambiguous(val options: List<FaqEntry>, val scores: List<Double>) : AnswerResult

    /**
     * The professor thinks he understood, and is not sure enough to say it with a straight
     * face — so he offers what he thinks it might be instead of answering.
     *
     * This is the shape the study is built on now. Retrieval that always answers is an oracle,
     * and an oracle that is right most of the time is worse than useless in a school about
     * security: the student cannot tell the right answers from the wrong ones, so they end up
     * trusting none of them. Retrieval that offers is a *navigator* — one extra tap, and a
     * confidently wrong answer stops being possible at all.
     *
     * Certainty is the words, not resemblance. When the student's own words name a lesson
     * outright the professor answers on the spot, because there he is right.
     */
    data class Unsure(val options: List<FaqEntry>, val scores: List<Double>) : AnswerResult

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
        /**
         * And below this the two signals together still have not found anything.
         *
         * Lowered with the move to a 256-dimension table for the same reason as the index's
         * own threshold: more axes, lower cosines, same meanings. Left where it was, it
         * produced a silence on "uno mi ha telefonato dicendo di essere della banca" — which
         * is not an edge case, it is how a person describes being phoned by a fake bank.
         */
        val blendedThreshold: Double = 0.30,
        /** At or under this many informative words, a question is a keyword lookup. */
        val keywordQueryTerms: Int = 2,
        /**
         * The bar to answer outright when the student *named* the subject.
         *
         * Lower than [confidentThreshold] because it does not stand alone. Measured, and the
         * measurement is the whole argument: "come scelgo una password sicura" scores 0.493
         * and "come si cambia una gomma dell'auto" scores 0.480. No threshold on earth
         * separates those two, and for months the engine tried. What separates them is that
         * one of them says "password" — so the number only has to decide among questions that
         * are already about this school's subject, and there 0.45 is comfortably clear.
         */
        val namedTheSubject: Double = 0.45,
        /**
         * How many guesses to put in front of the student when the professor is unsure.
         *
         * Three. Two hides the right answer too often; five is a menu, and a menu is what the
         * student came here to avoid.
         */
        val proposalCount: Int = 3,
        /**
         * How sure meaning has to be before it takes a question away from the conversational
         * stage — and only ever for a question that names something of the subject.
         *
         * Deliberately above the blended threshold: taking a question off the professor's own
         * ground is a stronger claim than merely answering it, and "posso usare la rete del
         * bar per pagare" is the shape of question at stake — a real one about public wi-fi,
         * which came back as the app's price list because "pagare" reads as a question about
         * money to anything that only counts words.
         */
        val meaningOverConversation: Double = 0.45,
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

    /** One question the student asked, and what came back for it. */
    data class Answered(val question: String, val result: AnswerResult)

    /**
     * Everything a message asked, answered.
     *
     * Retrieval returns one answer, which is right for one question and silently wrong for
     * two: "come ti chiami e quanti anni hai" came back as the age alone, with the name
     * dropped on the floor. So the message is cut into its questions first, and each one is
     * searched on its own.
     *
     * The split is then judged by its results and thrown away if it did harm. A piece with
     * nothing left in it to search for, or two pieces that both land on the same answer, mean
     * the sentence was one question wearing a conjunction — and answering it whole, the way it
     * was written, is better than two worse halves.
     */
    fun askAll(rawQuestion: String): List<Answered> {
        val whole = rawQuestion.trim()
        val parts = QuestionSplitter.split(whole)
        if (parts.size <= 1) return listOf(Answered(whole, ask(whole)))

        val answered = parts.map { Answered(it, ask(it)) }
        val lost = answered.any {
            (it.result as? AnswerResult.NotUnderstood)?.reason == Miss.UNPARSEABLE
        }
        val answers = answered.mapNotNull { (it.result as? AnswerResult.Found)?.entry?.id }
        val repeated = answers.size != answers.toSet().size
        return if (lost || repeated) listOf(Answered(whole, ask(whole))) else answered
    }

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

        // The uncertain band. Above it the words have spoken clearly and meaning is not
        // consulted at all; below it, a lexical match is a guess with a number attached —
        // and there the two signals decide together. Deciding by words alone in this band
        // answered "dove tengo le copie dei miei file" with the ransomware lesson.
        val blended = if (semantic != null && bestScore < config.confidentThreshold) {
            blendedCandidates(question, ranked)
        } else {
            null
        }

        // The conversational stage yields only to a lesson that has made its case. "Posso
        // usare la rete del bar per pagare" was answered with the app's price list on the
        // strength of "usare" and "pagare"; blocking it whenever the question named any
        // technical word was worse, because "quindi sei un bot" names one too and belongs to
        // the professor.
        //
        // Two ways for the syllabus to win, and both require the question to name something
        // of the subject: the words are certain, or meaning is. The second exists because a
        // question asked in a student's own words is *always* uncertain lexically — which is
        // exactly the case the semantic index was built for, and it used to be decided before
        // that index was ever consulted.
        //
        // The second way is closed when the question is about the student themselves. "Quando
        // ho installato l'applicazione" is about an enrolment date; the lesson on attack
        // surface has "applicazioni installate" in its answer, and that resemblance was enough
        // to steal the question and reply with something nobody asked. Resemblance must never
        // outrank the school's own record of this student.
        //
        // Only the second way, though. A lesson the *words* name outright still wins — "come
        // proteggo le mie password" is a lesson, however faintly it also resembles a question
        // about what has been asked so far.
        val aboutTheStudent = spoken != null && spoken.entry.kind != EntryKind.CONVERSATION
        val lessonWins = isAboutTheSubject(question) &&
            (bestScore >= config.confidentThreshold ||
                (!aboutTheStudent && blended != null &&
                    blended.first().second >= config.meaningOverConversation))
        if (spoken != null && !lessonWins) {
            return AnswerResult.Found(spoken.entry, spoken.score, alternatives = emptyList())
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

        // Answered outright only when the student's own words name the lesson. Above this line
        // retrieval is right, and making somebody confirm an answer that is plainly correct is
        // its own kind of rudeness.
        //
        // A one- or two-word question is a lookup, not a sentence — "idor", "regola 3 2 1" —
        // and it clears the bar on the ordinary threshold: there was never enough context for
        // anything stricter to mean something.
        // In the middle band the words are probably right, and "probably" is settled by asking
        // the other instrument. When meaning agrees with the words, two independent signals
        // point at the same entry and that is as certain as this engine gets; when it disagrees
        // the disagreement is itself the answer — the professor is not sure, and says so.
        val meaningAgrees = blended == null || blended.first().first == bestEntry
        val keywordLookup = asked.size <= config.keywordQueryTerms
        if (bestScore >= config.confidentThreshold ||
            (isAboutTheSubject(question) && bestScore >= config.namedTheSubject && meaningAgrees) ||
            (keywordLookup && bestScore >= config.acceptThreshold)
        ) {
            return AnswerResult.Found(
                entry = bestEntry,
                score = bestScore,
                alternatives = ranked.drop(1)
                    .filter { it.second >= bestScore * config.alternativeRelativeFloor }
                    .take(config.alternativesCount)
                    .map { it.first },
            )
        }

        // Below it the professor stops guessing and starts offering. Everything reached by
        // resemblance lives here, which is most of what a person types in their own words:
        // he shows what he thinks it might be and lets the student settle it in one tap.
        val proposals = (blended ?: ranked.filter { it.second >= config.acceptThreshold })
            .take(config.proposalCount)
        if (proposals.isNotEmpty()) {
            return AnswerResult.Unsure(proposals.map { it.first }, proposals.map { it.second })
        }

        return AnswerResult.NotUnderstood(
            nearest = bestEntry.takeIf { bestScore > 0.0 },
            bestScore = bestScore,
            reason = if (isAboutTheSubject(question)) Miss.NOT_COVERED else Miss.OFF_TOPIC,
        )
    }

    /**
     * What the two signals together think the question might be, best first.
     *
     * Resemblance proposes the candidates — it is the only one that can see past the words —
     * and the words reorder them, because even a question that failed the lexical threshold
     * usually shares something with the right entry, and that faint signal is what breaks
     * ties resemblance alone gets wrong.
     *
     * A list and not an answer: nothing found this way is certain enough to be stated, and
     * the whole point of the study now is that uncertainty is offered rather than asserted.
     */
    private fun blendedCandidates(
        question: String,
        ranked: List<Pair<FaqEntry, Double>>,
    ): List<Pair<FaqEntry, Double>>? {
        val hits = semantic?.search(question, SEMANTIC_CANDIDATES).orEmpty()
        if (hits.isEmpty()) return null

        val lexical = ranked.toMap()
        val blended = hits
            .map { hit ->
                hit.entry to config.semanticWeight * hit.score +
                    (1 - config.semanticWeight) * (lexical[hit.entry] ?: 0.0)
            }
            .sortedByDescending { it.second }

        if (blended.first().second < config.blendedThreshold) return null
        return blended
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
