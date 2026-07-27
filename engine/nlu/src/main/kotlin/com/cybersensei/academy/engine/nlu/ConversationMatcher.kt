package com.cybersensei.academy.engine.nlu

/**
 * Matches the questions that are not about the subject: the professor himself, the app, the
 * student's own record, and the conversation so far.
 *
 * Deliberately a different mechanism from the retrieval used for the syllabus, because the
 * two kinds of question are made of opposite material. "Cos'è l'esfiltrazione" is carried by
 * one rare word and is found by weighing rarity. "Chi sei" is carried entirely by words that
 * appear in every sentence ever written — strip those out, as any search engine must, and
 * nothing at all is left.
 *
 * So here nothing is stripped. The question is compared, word for word, against every way of
 * asking that the content declares, and the winner is the phrasing that overlaps most. It
 * only works because the content lists the phrasings; a phrasing nobody wrote down is a
 * phrasing the professor will not recognise, which is why the test bench exists.
 */
class ConversationMatcher(
    entries: List<FaqEntry>,
    /**
     * How many lesson entries use each word, and how many lessons there are.
     *
     * An extra word in the question means opposite things depending on this. "Quindi sei un
     * bot" carries a word that appears in half the syllabus and identifies nothing; "cos'è il
     * phishing" carries one that appears in three entries and settles the matter. Presence
     * alone was not enough to tell them apart — filler words are everywhere in the answers —
     * so what counts is rarity.
     */
    private val lessonFrequency: Map<String, Int> = emptyMap(),
    private val lessonCount: Int = 0,
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * How much of the longer of the two phrasings has to be shared.
         *
         * Measured against the longer side on purpose: "chi sei" inside a twelve-word
         * question about something else would otherwise score a perfect match on the two
         * words it happens to contain.
         */
        val acceptThreshold: Double = 0.6,
        /** A single shared word is a coincidence, never an intent. */
        val minimumSharedWords: Int = 2,
        /**
         * What the student's extra words cost, compared to the words a phrasing promised
         * and did not get.
         *
         * The two are not symmetrical. "Insegnami a hackerare *un account*" is the same
         * request as "insegnami a hackerare" with a detail attached, so the detail should
         * not sink it. But a phrasing that says "cos'è il *ripasso*" and receives a question
         * with no ripasso in it is being asked about something else entirely, and that
         * missing word must count in full.
         */
        val extraWordPenalty: Double = 0.5,
        /**
         * And what an extra word costs when neither the syllabus nor any phrasing has ever
         * seen it. Almost nothing: "quindi", "esattamente", "praticamente" are how people
         * speak, not what they are asking about.
         */
        val unknownWordPenalty: Double = 0.12,
        /**
         * A word is about the subject only if it appears in a small enough share of the
         * lessons. Above this it is a word of the language, not of the domain.
         *
         * Six per cent, which over seventy-odd entries means four. It was fifteen, and at
         * fifteen "dentro" counted as a technical term — it appears in eight lessons, the
         * way ordinary words do — and "ci sono pubblicità dentro" stopped being understood
         * because of it. A corpus this small needs a strict definition of rare.
         */
        val topicalShare: Double = 0.06,
        /**
         * How few *entries* a word may belong to and still count as what a phrasing is
         * about, rather than how it asks.
         *
         * Entries and not phrasings, which was the mistake twice over. "Ripasso" appears in
         * a dozen ways of asking, so by phrasing count it looked common — but all dozen
         * belong to two entries, both about reviews, which is exactly what makes the word
         * decisive. Counted the old way, "a cosa serve la 2fa" was answered with the review
         * schedule: the frame matched, and the one word that mattered carried no weight.
         *
         * Rarity is still not sufficient on its own: "vuol" belongs to one entry here and to
         * half the syllabus, which makes it a word of the language.
         */
        val keyWordEntries: Int = 2,
    )

    /** [phrasing] is the written form that won: without it, tuning the content is guesswork. */
    data class Match(val entry: FaqEntry, val score: Double, val phrasing: String)

    private class Phrasing(val entry: FaqEntry, val text: String, val words: Set<String>) {
        /** Filled in after the frequencies are known: the words that make this phrasing itself. */
        lateinit var keyWords: Set<String>
    }

    private val phrasings: List<Phrasing> = entries
        .filter { it.kind != EntryKind.LESSON }
        .flatMap { entry ->
            (listOf(entry.question) + entry.aliases).map { Phrasing(entry, it, wordsOf(it)) }
        }
        .filter { it.words.isNotEmpty() }

    private val phrasingFrequency: Map<String, Int> = buildMap {
        phrasings.forEach { phrasing -> phrasing.words.forEach { merge(it, 1, Int::plus) } }
    }

    /** How many distinct entries each word belongs to, however many ways they are phrased. */
    private val entryFrequency: Map<String, Int> = buildMap {
        phrasings.groupBy { it.entry.id }.forEach { (_, ofEntry) ->
            ofEntry.flatMap { it.words }.toSet().forEach { merge(it, 1, Int::plus) }
        }
    }

    init {
        phrasings.forEach { phrasing ->
            phrasing.keyWords = phrasing.words
                .filter { (entryFrequency[it] ?: 0) <= config.keyWordEntries && !isCommonSpeech(it) }
                .toSet()
        }
    }

    /** A word the syllabus uses everywhere is how people write, not what they ask about. */
    private fun isCommonSpeech(word: String): Boolean =
        (lessonFrequency[word] ?: 0) > (lessonCount * config.topicalShare)

    /**
     * How much a word says about *which* thing is being asked.
     *
     * Nothing is thrown away here, but not everything counts the same. "Cos'è il phishing"
     * and "cos'è il ripasso" share three words out of four, and counting them equally
     * answered a question about phishing with an explanation of the review schedule.
     *
     * Inverse frequency without a logarithm, unlike the retrieval side. The usual smoothed
     * IDF exists to keep long documents comparable, and it worked here too — it just did not
     * separate enough: across phrasings four words long, "cos" scored 4.2 and "phishing" 6.6,
     * and a match resting entirely on filler still cleared the bar. Raw inverse frequency
     * puts them at 22 and 266, which is the contrast the job actually needs.
     */
    private fun weight(word: String): Double =
        phrasings.size / ((phrasingFrequency[word] ?: 0) + 1.0)

    /**
     * A word that belongs to the subject rather than to the way people talk: rare enough in
     * the syllabus to name something, and actually present there.
     */
    private fun isTopical(word: String): Boolean {
        val seen = lessonFrequency[word] ?: return false
        return seen in 1..(lessonCount * config.topicalShare).toInt().coerceAtLeast(1)
    }

    fun match(question: String): Match? {
        val asked = wordsOf(question)
        if (asked.isEmpty()) return null

        // "Ciao, chi sei?" is a greeting followed by a question, and the question is the
        // part that matters. Both readings are tried and the better one wins.
        val readings = listOf(asked, withoutOpening(asked)).filter { it.size >= 1 }

        return phrasings
            .mapNotNull { phrasing ->
                val best = readings.maxOf { similarity(it, phrasing) }
                Match(phrasing.entry, best, phrasing.text).takeIf { best >= config.acceptThreshold }
            }
            .maxByOrNull { it.score }
    }

    private fun similarity(asked: Set<String>, phrasing: Phrasing): Double {
        // The frame of a question is not the question. "Cosa vuol dire padronanza media" and
        // "cosa vuol dire crittografia" share three words out of four and mean nothing to
        // each other: everything a phrasing is *about* has to be present, or it does not
        // apply. All of them, not one — one was enough for the frame alone to slip through.
        if (phrasing.keyWords.any { it !in asked }) return 0.0

        val words = phrasing.words
        val shared = asked.filter { it in words }
        if (shared.size < minOf(config.minimumSharedWords, asked.size, words.size)) return 0.0

        val matched = shared.sumOf { weight(it) }
        val extra = asked.filterNot { it in words }.sumOf { word ->
            weight(word) * if (isTopical(word)) config.extraWordPenalty else config.unknownWordPenalty
        }
        val missing = words.filterNot { it in asked }.sumOf { weight(it) }

        val whole = maxOf(matched + missing, matched + extra)
        return if (whole == 0.0) 0.0 else matched / whole
    }

    /** Drops the opening pleasantries, as long as something is left to ask about. */
    private fun withoutOpening(words: Set<String>): Set<String> {
        val trimmed = words - OPENINGS
        return if (trimmed.isEmpty()) words else trimmed
    }

    private companion object {
        /**
         * Greetings and throat-clearing. Removed only as an alternative reading, never
         * outright: "ciao" on its own is a greeting and deserves an answer of its own.
         */
        val OPENINGS: Set<String> = setOf(
            "ciao", "salve", "buongiorno", "buonasera", "buonanotte", "ehi", "hey", "ei",
            "scusa", "scusami", "senti", "allora", "ok", "okay", "prof", "professore",
            "professor", "per", "favore", "gentilmente", "dimmi", "dimmelo", "vorrei",
            // Stemmed like everything else: the words arrive here already cut down, so a
            // list of whole words would never match a single one of them.
        ).map(ItalianText::stem).toSet()

        fun wordsOf(text: String): Set<String> = ItalianText.normalise(text)
            .split(' ')
            .filter { it.isNotBlank() }
            .map(ItalianText::stem)
            .toSet()
    }
}
