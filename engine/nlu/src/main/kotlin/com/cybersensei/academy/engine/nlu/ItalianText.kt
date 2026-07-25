package com.cybersensei.academy.engine.nlu

import java.text.Normalizer
import kotlin.math.min

/**
 * The small amount of Italian the app needs to understand in order to look like it
 * understands Italian.
 *
 * No model, no dictionary download, no network: stripping accents, dropping filler words and
 * cutting the endings off is enough to make "come funzionano le password?" and "a cosa serve
 * una password" land on the same answer.
 */
object ItalianText {

    /** Words that appear in every question and therefore distinguish nothing. */
    val STOPWORDS = setOf(
        "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "del", "dello", "della",
        "dei", "degli", "delle", "al", "allo", "alla", "ai", "agli", "alle", "dal", "dalla",
        "nel", "nella", "nei", "sul", "sulla", "con", "per", "tra", "fra", "di", "a", "da",
        "in", "su", "e", "ed", "o", "ma", "se", "che", "chi", "cosa", "come", "quando",
        "dove", "perche", "quale", "quali", "quanto", "quanta", "quanti", "quante", "mi",
        "ti", "si", "ci", "vi", "me", "te", "lui", "lei", "noi", "voi", "loro", "io", "tu",
        "e'", "sono", "sei", "siamo", "siete", "essere", "ho", "hai", "ha", "abbiamo",
        "hanno", "avere", "fa", "fare", "puo", "posso", "puoi", "possono", "devo", "devi",
        "deve", "il", "non", "piu", "meno", "molto", "poco", "anche", "solo", "gia", "ancora",
        "questo", "questa", "questi", "queste", "quel", "quella", "quello", "mio", "mia",
        "tuo", "tua", "prof", "professore", "professor",
    )

    /**
     * Suffixes stripped to reduce a word to something stable. Deliberately conservative: an
     * over-eager stemmer merges words that mean different things, which is worse than
     * missing a match.
     */
    private val SUFFIXES = listOf(
        "issimo", "issima", "issimi", "issime",
        "amento", "amenti", "imento", "imenti",
        "zione", "zioni", "mente", "aggio",
        "ando", "endo", "ature", "atura",
        "abile", "ibile",
        "are", "ere", "ire", "ato", "ata", "ati", "ate", "ito", "ita", "iti", "ite",
        "oni", "one", "ori", "ore",
        "i", "e", "o", "a",
    )

    /**
     * Italian pluralises -co/-go as -chi/-ghi. Dropping the whole ending would leave a
     * different stem than the singular does, so the h is removed instead: attacco and
     * attacchi both end up as "attacc".
     */
    private val PLURAL_H = listOf("chi" to "c", "che" to "c", "ghi" to "g", "ghe" to "g")

    private const val MIN_STEM_LENGTH = 4

    fun normalise(text: String): String = Normalizer
        .normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(NON_WORD, " ")
        .replace(MULTI_SPACE, " ")
        .trim()

    /**
     * Verbs of *intent* rather than of topic. In a school entirely about defending things,
     * "come mi difendo dal phishing" carries its meaning in "phishing" alone: leaving
     * "difendo" in sends the question to whichever entry happens to use the same verb.
     * They are removed after stemming, since that is the form they end up in.
     */
    private val INTENT_STEMS = setOf(
        "difend", "difes", "protegg", "protett", "funzion", "signific", "serv", "evit",
        "sapere", "capir", "spieg", "consigl", "utilizz", "usar", "facci", "far",
    )

    fun tokenise(text: String): List<String> = normalise(text)
        .split(' ')
        // A lone "l" is what an Italian apostrophe leaves behind ("l'app" -> "l app"); it
        // appeared in three documents out of four and drowned the words that mattered.
        .filter { it.length > 1 && it !in STOPWORDS }

    /** Cuts a known ending off, but never down to a stub that could match anything. */
    fun stem(word: String): String {
        if (word.length <= MIN_STEM_LENGTH) return word
        for ((plural, singular) in PLURAL_H) {
            if (word.endsWith(plural) && word.length - plural.length + singular.length >= MIN_STEM_LENGTH) {
                return word.dropLast(plural.length) + singular
            }
        }
        for (suffix in SUFFIXES) {
            if (word.endsWith(suffix) && word.length - suffix.length >= MIN_STEM_LENGTH) {
                return word.dropLast(suffix.length)
            }
        }
        return word
    }

    fun terms(text: String): List<String> =
        tokenise(text).map(::stem).filterNot { it in INTENT_STEMS }

    /**
     * Edit distance, capped: past [max] the exact number stops mattering and the words are
     * simply different. The cap keeps a long question from turning into a quadratic scan.
     */
    fun editDistance(a: String, b: String, max: Int = 3): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
                rowMin = min(rowMin, current[j])
            }
            if (rowMin > max) return max + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val MULTI_SPACE = Regex(" {2,}")
}
