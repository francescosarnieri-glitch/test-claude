package com.cybersensei.academy.engine.labs

import kotlin.math.ln
import kotlin.math.pow

/** How the strength of a password reads once you stop looking at its symbols. */
data class PasswordVerdict(
    val entropyBits: Double,
    /** Which of [PasswordStrength.alphabets] the password forces an attacker to search. */
    val alphabetSize: Int,
    val length: Int,
    val band: StrengthBand,
    /** Weaknesses that make the entropy figure a lie. The honest part of the exercise. */
    val weaknesses: List<Weakness>,
    /** Seconds to exhaust the space, for each attack scenario. */
    val crackTimes: Map<AttackScenario, Double>,
) {
    /** True when a weakness makes the arithmetic meaningless: the number is then a ceiling. */
    val entropyIsOptimistic: Boolean get() = weaknesses.isNotEmpty()
}

enum class StrengthBand(val italianName: String, val icon: String) {
    LAUGHABLE("Ridicola", "✗"),
    WEAK("Debole", "!"),
    FAIR("Discreta", "~"),
    STRONG("Solida", "✓"),
    EXCESSIVE("Oltre il necessario", "✓✓"),
}

/**
 * What makes the entropy estimate optimistic.
 *
 * Entropy assumes the attacker searches blindly. Every item here is a reason they would not
 * have to — and a password that trips one is much weaker than its bit count suggests.
 */
enum class Weakness(val italianName: String, val explanation: String) {
    TOO_SHORT(
        "Troppo corta",
        "Sotto i dodici caratteri il conteggio dei bit non conta: si arriva in fondo comunque.",
    ),
    COMMON_PASSWORD(
        "È fra le più usate al mondo",
        "Compare negli elenchi che ogni attacco prova per primi: viene trovata al primo colpo, " +
            "qualunque cosa dica l'entropia.",
    ),
    SINGLE_ALPHABET(
        "Un solo tipo di carattere",
        "Solo lettere, o solo cifre: lo spazio da cercare è molto più piccolo di quanto sembri.",
    ),
    KEYBOARD_SEQUENCE(
        "Sequenza di tastiera",
        "Sequenze come «qwerty» o «123456» sono in cima a ogni elenco: non sono casuali per nessuno.",
    ),
    REPEATED_CHARACTERS(
        "Caratteri ripetuti",
        "Ripetere allunga la password senza aggiungere possibilità: costa a te, non a chi la cerca.",
    ),
    LEET_SUBSTITUTION(
        "Sostituzioni prevedibili",
        "Scrivere «P@ssw0rd» invece di «password» non inganna nessuno: sono le prime varianti provate.",
    ),
    DATE_LIKE(
        "Contiene una data",
        "Anni e date sono poche migliaia di possibilità, e spesso sono anche pubbliche.",
    ),
}

/**
 * A guessing rate, and where it comes from.
 *
 * Two orders of magnitude apart on purpose: the point of the lab is that the same password
 * is fine against one scenario and gone in seconds against another, so "quanto è forte"
 * cannot be answered without asking "contro chi".
 */
enum class AttackScenario(
    val italianName: String,
    val guessesPerSecond: Double,
    val description: String,
) {
    ONLINE(
        "Tentativi sul sito",
        100.0,
        "Chi prova a indovinare dal modulo di accesso, con blocchi e ritardi in mezzo.",
    ),
    STOLEN_HASHES_SLOW(
        "Archivio rubato, hash lento",
        1_000_000.0,
        "Il sito è stato violato ma proteggeva le password con un algoritmo lento e apposito.",
    ),
    STOLEN_HASHES_FAST(
        "Archivio rubato, hash veloce",
        100_000_000_000.0,
        "Il sito conservava le password con un hash generico e veloce: lo scenario peggiore, " +
            "e il più comune nelle violazioni vere.",
    ),
}

/**
 * The arithmetic behind the Password Forge.
 *
 * Deliberately does two things at once: it computes the entropy, and it says when that
 * number is not to be believed. A tool that shows "72 bit" for `P@ssw0rd123!` teaches the
 * exact habit this school is trying to break.
 */
object PasswordStrength {

    /** Character classes, and how many symbols each one adds to the search space. */
    val alphabets: Map<String, Int> = mapOf(
        "minuscole" to 26,
        "maiuscole" to 26,
        "cifre" to 10,
        "simboli" to 33,
    )

    fun evaluate(password: String): PasswordVerdict {
        if (password.isEmpty()) {
            return PasswordVerdict(
                entropyBits = 0.0,
                alphabetSize = 0,
                length = 0,
                band = StrengthBand.LAUGHABLE,
                weaknesses = emptyList(),
                crackTimes = AttackScenario.entries.associateWith { 0.0 },
            )
        }

        val alphabet = alphabetSizeOf(password)
        // log2(alphabet^length), computed as length * log2(alphabet) to avoid overflowing.
        val entropy = password.length * (ln(alphabet.toDouble()) / ln(2.0))
        val weaknesses = weaknessesOf(password)

        return PasswordVerdict(
            entropyBits = entropy,
            alphabetSize = alphabet,
            length = password.length,
            band = bandFor(entropy, weaknesses),
            weaknesses = weaknesses,
            crackTimes = AttackScenario.entries.associateWith { scenario ->
                // Average case: on a uniform search you expect to find it halfway through.
                val guesses = 2.0.pow(entropy) / 2
                guesses / scenario.guessesPerSecond
            },
        )
    }

    fun alphabetSizeOf(password: String): Int {
        var size = 0
        if (password.any { it.isLowerCase() }) size += alphabets.getValue("minuscole")
        if (password.any { it.isUpperCase() }) size += alphabets.getValue("maiuscole")
        if (password.any { it.isDigit() }) size += alphabets.getValue("cifre")
        if (password.any { !it.isLetterOrDigit() }) size += alphabets.getValue("simboli")
        return size.coerceAtLeast(1)
    }

    private fun weaknessesOf(password: String): List<Weakness> = buildList {
        val lower = password.lowercase()

        if (password.length < MINIMUM_SERIOUS_LENGTH) add(Weakness.TOO_SHORT)
        if (lower in COMMON_PASSWORDS) add(Weakness.COMMON_PASSWORD)
        if (password.all { it.isLetter() } || password.all { it.isDigit() }) {
            add(Weakness.SINGLE_ALPHABET)
        }
        if (KEYBOARD_RUNS.any { it in lower }) add(Weakness.KEYBOARD_SEQUENCE)
        if (hasLongRepeat(password)) add(Weakness.REPEATED_CHARACTERS)
        if (looksLikeLeetOfACommonWord(lower)) add(Weakness.LEET_SUBSTITUTION)
        if (YEAR.containsMatchIn(password)) add(Weakness.DATE_LIKE)
    }

    /** Undoes the usual substitutions and checks whether a common word was underneath. */
    private fun looksLikeLeetOfACommonWord(lower: String): Boolean {
        val undone = lower
            .replace('0', 'o').replace('1', 'i').replace('3', 'e')
            .replace('4', 'a').replace('5', 's').replace('7', 't')
            .replace('@', 'a').replace('$', 's').replace('!', 'i')
        if (undone == lower) return false
        return COMMON_WORDS.any { it in undone }
    }

    private fun hasLongRepeat(password: String): Boolean {
        var run = 1
        for (index in 1 until password.length) {
            run = if (password[index] == password[index - 1]) run + 1 else 1
            if (run >= MAX_REPEAT) return true
        }
        return false
    }

    /**
     * A weakness caps the band, whatever the arithmetic says.
     *
     * This is the whole point: `Password1!` computes to a respectable number of bits and is
     * found instantly, so the number must not be allowed to speak alone.
     */
    private fun bandFor(entropy: Double, weaknesses: List<Weakness>): StrengthBand {
        val fromEntropy = when {
            entropy < 28 -> StrengthBand.LAUGHABLE
            entropy < 45 -> StrengthBand.WEAK
            entropy < 65 -> StrengthBand.FAIR
            entropy < 100 -> StrengthBand.STRONG
            else -> StrengthBand.EXCESSIVE
        }
        if (weaknesses.isEmpty()) return fromEntropy

        val ceiling = when {
            Weakness.COMMON_PASSWORD in weaknesses ||
                Weakness.KEYBOARD_SEQUENCE in weaknesses -> StrengthBand.LAUGHABLE
            Weakness.LEET_SUBSTITUTION in weaknesses ||
                Weakness.TOO_SHORT in weaknesses -> StrengthBand.WEAK
            else -> StrengthBand.FAIR
        }
        return minOf(fromEntropy, ceiling)
    }

    /** A handful of real ones, enough to make the point without shipping a cracking list. */
    private val COMMON_PASSWORDS = setOf(
        "password", "123456", "123456789", "12345678", "qwerty", "abc123", "111111",
        "123123", "admin", "letmein", "welcome", "monkey", "iloveyou", "ciao", "juventus",
        "password1", "qwerty123", "1q2w3e4r", "francesco", "napoli",
    )

    private val COMMON_WORDS = setOf(
        "password", "admin", "welcome", "letmein", "monkey", "dragon", "master", "ciao",
    )

    private val KEYBOARD_RUNS = listOf(
        "qwert", "asdfg", "zxcvb", "12345", "09876", "abcde",
    )

    private val YEAR = Regex("(19|20)\\d{2}")

    const val MINIMUM_SERIOUS_LENGTH = 12
    private const val MAX_REPEAT = 3
}
