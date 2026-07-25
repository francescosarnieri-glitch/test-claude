package com.cybersensei.academy.engine.mastery

/**
 * How sure the student claimed to be, asked *before* the answer is revealed.
 *
 * This single question is what separates knowing from guessing. Without it a lucky click and
 * a reasoned answer look identical to the app; with it, the professor can tell them apart
 * and react differently — which is the whole point of the school.
 */
enum class Confidence(val italianLabel: String, val icon: String) {
    /** "Tiro a indovinare" — honest admission, never punished. */
    GUESS("Tiro a indovinare", "🤔"),

    /** "Abbastanza sicuro" — the student has an intuition but no certainty. */
    UNSURE("Abbastanza", "😐"),

    /** "Sicurissimo" — the student is putting their reputation on it. */
    SURE("Sicurissimo", "😎"),
}
