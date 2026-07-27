package com.cybersensei.academy.engine.nlu

/**
 * Cuts a message into the questions it actually contains.
 *
 * People do not write one question per message. "Come ti chiami e quanti anni hai" is two
 * questions, and for a long time the professor answered the second one and let the first
 * disappear — which reads exactly like not listening. Retrieval cannot fix that: it is built
 * to return *one* best answer, so a message holding two questions was always going to lose
 * one of them before the search ever started.
 *
 * The cut is deliberately timid, because the failure modes are not symmetrical. Splitting a
 * message that was one question produces two half-answers to something nobody asked; missing
 * a split produces the behaviour we already had. So a conjunction only separates two questions
 * when what follows it *begins* like a question — "e quanti anni hai", not "phishing e
 * ransomware" — and [QuestionAnswerer.askAll] throws the split away if answering the pieces
 * turns out worse than answering the whole.
 */
object QuestionSplitter {

    /** More than this in one message is a paragraph, and answering all of it is a wall of text. */
    const val MAX_PARTS = 3

    /**
     * Words that can start a question. A conjunction is only a boundary when one of these
     * comes right after it, which is what tells "chi sei e cosa fai" (two) apart from
     * "differenza fra virus e worm" (one).
     */
    private val OPENERS = setOf(
        "chi", "cosa", "che", "cos", "com", "come", "quando", "dove", "perche", "qual",
        "quale", "quali", "quanto", "quanta", "quanti", "quante",
        "dimmi", "dimme", "parlami", "spiegami", "raccontami", "sai", "sapresti", "puoi",
        "potresti", "vorrei", "voglio", "mi",
    )

    /** Conjunctions that can join two whole questions, longest first so the longer one wins. */
    private val JOINS = listOf(" e poi ", " e anche ", " ed anche ", " e inoltre ", " e ", " ed ", " oppure ", " inoltre ")

    /** Punctuation that ends a question on its own, no conjunction needed. */
    private val HARD_BREAKS = charArrayOf('?', ';', '\n')

    /**
     * The questions in [text], in the order they were written. One element — the message
     * unchanged — when there is nothing to separate.
     *
     * The pieces are cut out of the original string, not out of the normalised one: the
     * student's own words are what gets shown back to them and what gets searched.
     */
    fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        text.splitOnHardBreaks().forEach { segment ->
            if (parts.size >= MAX_PARTS) return@forEach
            parts += segment.splitOnConjunctions(MAX_PARTS - parts.size)
        }
        val kept = parts.map { it.trim() }.filter { it.isNotBlank() }
        return if (kept.size <= 1) listOf(text.trim()) else kept
    }

    private fun String.splitOnHardBreaks(): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        forEachIndexed { index, character ->
            if (character in HARD_BREAKS) {
                pieces += substring(start, index)
                start = index + 1
            }
        }
        pieces += substring(start)
        return pieces.filter { it.isNotBlank() }
    }

    /**
     * Splits "come ti chiami e quanti anni hai" and leaves "differenza fra phishing e
     * smishing" alone: the conjunction is a boundary only when both sides can stand as
     * questions on their own.
     */
    private fun String.splitOnConjunctions(allowance: Int): List<String> {
        if (allowance <= 1) return listOf(this)
        val padded = " ${ItalianText.normalise(this)} "

        // The normalised text loses characters, so a boundary found there has to be located
        // again in the original — by counting words, the one thing the two versions share.
        JOINS.forEach { join ->
            var from = 0
            while (true) {
                val at = padded.indexOf(join, from)
                if (at < 0) break
                from = at + 1
                val left = padded.substring(0, at)
                val right = padded.substring(at + join.length)
                if (!canStandAlone(left) || !canStandAlone(right)) continue
                if (right.trim().substringBefore(' ') !in OPENERS) continue

                val wordsOnTheLeft = left.trim().split(' ').count { it.isNotEmpty() }
                val joinWords = join.trim().split(' ').size
                val words = wordSpans()
                if (words.size <= wordsOnTheLeft + joinWords) continue
                val leftEnd = words[wordsOnTheLeft - 1].last + 1
                val rightStart = words[wordsOnTheLeft + joinWords].first
                return listOf(substring(0, leftEnd).trim()) +
                    substring(rightStart).splitOnConjunctions(allowance - 1)
            }
        }
        return listOf(this)
    }

    /**
     * Where every word begins and ends in the original text, counted the way [ItalianText]
     * counts words. It is what keeps a boundary found in the normalised sentence aligned with
     * the sentence the student actually typed, accents, apostrophes and all.
     */
    private fun String.wordSpans(): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var start = -1
        indices.forEach { index ->
            val isWordCharacter = ItalianText.normalise(this[index].toString()).isNotEmpty()
            if (isWordCharacter && start < 0) start = index
            if (!isWordCharacter && start >= 0) {
                spans += start until index
                start = -1
            }
        }
        if (start >= 0) spans += start until length
        return spans
    }

    /**
     * Whether a fragment carries a topic of its own.
     *
     * Judged with the same reading the engine uses, verbs of intent included: "come mi
     * difendo" looks like a question and contains nothing to search for, so "cos'è il phishing
     * e come mi difendo" stays whole — one question about phishing, asked in two breaths.
     */
    private fun canStandAlone(fragment: String): Boolean =
        ItalianText.terms(fragment).isNotEmpty()
}
