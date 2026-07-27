package com.cybersensei.academy.engine.nlu

import kotlin.math.ln

/**
 * Finds the lesson that answers a question nobody wrote down.
 *
 * The lexical engine matches words: it is precise, and it is deaf to anyone who phrases a
 * question differently from the way the content was written. "Quando mi arriva una mail
 * strana che vuole la password" shares no useful word with "Come riconosco un'email di
 * phishing", so it came back as silence — and no amount of writing aliases fixes that in
 * general, because the aliases are written by two people and the questions come from
 * everybody.
 *
 * This index compares meanings instead. Every way of asking that the content declares, plus
 * every answer, becomes a direction in space; so does the student's question; the closest
 * one wins. It cannot produce a sentence and it cannot reach outside the corpus: its entire
 * power is to point at one of the school's own verified answers.
 */
class SemanticIndex(
    entries: List<FaqEntry>,
    private val vectors: WordVectors,
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * Below this the resemblance is noise. Measured on the bench: real paraphrases of a
         * covered topic land well clear of it, while questions about something else sit
         * under — and the gap is what keeps "parlami della carbonara" out.
         *
         * It came down from 0.42 when the table went from 128 dimensions to 256. Nothing
         * about the meanings changed: spreading the same directions over twice as many axes
         * lowers every cosine, so a threshold tuned for the narrow table would have started
         * refusing questions it used to answer.
         */
        val acceptThreshold: Double = 0.35,
    )

    data class Hit(val entry: FaqEntry, val score: Double)

    private class Point(val entry: FaqEntry, val vector: FloatArray)

    private val lessons = entries.filter { it.kind == EntryKind.LESSON }

    /**
     * How much each word narrows things down, counted over the school's own material. A word
     * used in one entry is worth several times one used in forty.
     */
    private val weights: Map<String, Double> = buildMap {
        val texts = lessons.flatMap { listOf(it.question) + it.aliases + it.answer }
        val seen = HashMap<String, Int>()
        texts.forEach { text ->
            ItalianText.normalise(text).split(' ').toSet().forEach { word ->
                if (word.length > 1) seen.merge(word, 1, Int::plus)
            }
        }
        seen.forEach { (word, count) -> put(word, ln((texts.size + 1.0) / (count + 1.0)) + 1.0) }
    }

    private fun weightOf(word: String): Double = weights[word] ?: DEFAULT_WEIGHT

    /**
     * One point per way of asking, and one per answer — never one average per entry.
     *
     * Averaging was tried first and is worse: an entry with eight phrasings ends up pointing
     * at the middle of them, which is a place none of the eight actually mean.
     */
    private val points: List<Point> = lessons.flatMap { entry ->
        (listOf(entry.question) + entry.aliases + entry.answer).mapNotNull { text ->
            vectors.embed(text) { weightOf(it) }?.let { Point(entry, it) }
        }
    }

    val size: Int get() = points.size

    /** The closest entries, best first, with duplicates of the same entry collapsed. */
    fun search(question: String, limit: Int = 3): List<Hit> {
        val asked = vectors.embed(question) { weightOf(it) } ?: return emptyList()

        val best = HashMap<String, Hit>()
        points.forEach { point ->
            val score = asked.similarityTo(point.vector)
            if (score < config.acceptThreshold) return@forEach
            val current = best[point.entry.id]
            if (current == null || score > current.score) {
                best[point.entry.id] = Hit(point.entry, score)
            }
        }
        return best.values.sortedByDescending { it.score }.take(limit)
    }

    private companion object {
        /** A word the school never uses is, by that fact alone, highly specific. */
        const val DEFAULT_WEIGHT = 4.0
    }
}
