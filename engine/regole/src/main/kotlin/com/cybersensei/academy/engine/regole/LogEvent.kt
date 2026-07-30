package com.cybersensei.academy.engine.regole

import kotlinx.serialization.Serializable

/**
 * One line of a log, already broken into fields.
 *
 * Structured rather than a string to be parsed, because the exercise is about writing a rule,
 * not about fighting a log format. Real detection engines work on parsed events too — the
 * parsing happens long before the rule does, and a student who learns to think in fields has
 * learned the thing that transfers.
 */
@Serializable
data class LogEvent(
    val id: String,
    /** "08:14:22", shown to the student. */
    val ora: String,
    /** Everything the rule can look at: tipo, esito, utente, indirizzo, risorsa, byte… */
    val campi: Map<String, String>,
) {
    /**
     * Minutes since midnight, used by the time windows.
     *
     * Derived from [ora] rather than authored, so an exercise can never declare a time that
     * disagrees with the one the student is reading on screen.
     */
    val minuto: Int
        get() {
            val parts = ora.split(':')
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return h * MINUTES_IN_HOUR + m
        }

    /** The line as the student sees it, rebuilt from the fields so the two can never diverge. */
    fun riga(colonne: List<String>): String = buildString {
        append(ora.padEnd(TIME_WIDTH))
        colonne.forEach { colonna ->
            append((campi[colonna] ?: "-").padEnd(COLUMN_WIDTH))
        }
    }.trimEnd()

    private companion object {
        const val MINUTES_IN_HOUR = 60
        const val TIME_WIDTH = 11
        const val COLUMN_WIDTH = 14
    }
}

/**
 * A body of logs an exercise is set on.
 *
 * [colonne] fixes the order the fields are printed in, so the student reads the same shape all
 * the way down and can find "the third column" without counting every time.
 */
@Serializable
data class LogSource(
    val id: String,
    val titolo: String,
    val descrizione: String,
    val colonne: List<String>,
    val eventi: List<LogEvent>,
) {
    /** Every field name that appears anywhere, which is what the chips on screen offer. */
    val campi: List<String>
        get() = (colonne + eventi.flatMap { it.campi.keys }).distinct()

    /** The values seen for one field, so the student can tap instead of typing. */
    fun valori(campo: String): List<String> =
        eventi.mapNotNull { it.campi[campo] }.distinct().sorted()

    fun validate(): List<String> = buildList {
        if (eventi.isEmpty()) add("Il registro '$id' non ha eventi")
        eventi.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Evento duplicato nel registro '$id': '$it'") }
        colonne.forEach { colonna ->
            if (eventi.none { colonna in it.campi }) {
                add("Il registro '$id' dichiara la colonna '$colonna', che nessun evento ha")
            }
        }
        eventi.filter { it.ora.split(':').size < 2 }
            .forEach { add("L'evento '${it.id}' non ha un orario leggibile: '${it.ora}'") }
        // Events out of order would make the time windows nonsense and the log unreadable.
        eventi.zipWithNext().forEach { (before, after) ->
            if (after.minuto < before.minuto) {
                add("Nel registro '$id' l'evento '${after.id}' viene prima di '${before.id}'")
            }
        }
    }
}
