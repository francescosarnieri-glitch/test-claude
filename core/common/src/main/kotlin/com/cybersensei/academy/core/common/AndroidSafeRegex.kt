package com.cybersensei.academy.core.common

/**
 * Builds a [Regex] that behaves the same on a JVM and on a phone.
 *
 * Android delegates regular expressions to ICU, and ICU is stricter than the JVM engine in
 * ways that never show up in unit tests: a closing brace the JVM happily treats as a literal
 * is a syntax error there, and `\p{InSomeBlock}` is Java-only spelling ICU does not know.
 * Both compile fine on a laptop and crash on a device — which is exactly how this app once
 * died on launch with no explanation.
 *
 * Every pattern in the project goes through here, so the mistake fails a unit test on the
 * machine that wrote it instead of a phone in someone's hand.
 */
fun androidSafeRegex(pattern: String): Regex {
    problemIn(pattern)?.let { problem ->
        throw IllegalArgumentException("Espressione regolare non sicura su Android: $problem — «$pattern»")
    }
    return Regex(pattern)
}

/** Returns a description of the portability problem, or null when the pattern is safe. */
internal fun problemIn(pattern: String): String? {
    if (pattern.contains("\\p{In") || pattern.contains("\\P{In")) {
        return "i blocchi Unicode in stile Java (\\p{In...}) non esistono in ICU: usa \\p{Mn} o simili"
    }

    var index = 0
    while (index < pattern.length) {
        when {
            pattern[index] == '\\' -> index += 2

            pattern[index] == '{' -> {
                val end = pattern.indexOf('}', index)
                if (end < 0) return "graffa aperta e mai chiusa"
                val body = pattern.substring(index + 1, end)
                val isQuantifier = body.isNotEmpty() && body.all { it.isDigit() || it == ',' }
                val isUnicodeProperty = index >= 2 &&
                    (pattern[index - 1] == 'p' || pattern[index - 1] == 'P') &&
                    pattern[index - 2] == '\\'
                if (!isQuantifier && !isUnicodeProperty) {
                    return "graffe usate come testo: vanno protette con \\{ e \\}"
                }
                index = end + 1
            }

            pattern[index] == '}' ->
                return "graffa di chiusura non protetta: ICU la rifiuta, scrivi \\}"

            else -> index++
        }
    }
    return null
}
