package com.cybersensei.academy.engine.regole

/**
 * What the parser says when a rule does not make sense.
 *
 * The message matters as much as the rule engine does. A student writing their first rule will
 * get it wrong four times before getting it right, and "errore di sintassi" teaches nothing:
 * every message here says what was found, what was expected, and — where it can — offers the
 * nearest thing that would have worked.
 */
data class ErroreDiRegola(
    val messaggio: String,
    /** The word the parser tripped on, so the screen can point at it. */
    val parola: String? = null,
    val suggerimento: String? = null,
)

sealed interface Lettura {
    data class Riuscita(val regola: Regola) : Lettura
    data class Fallita(val errore: ErroreDiRegola) : Lettura
}

/**
 * Turns what the student typed into a rule.
 *
 * Forgiving where forgiveness costs nothing — case, extra spaces, `E` for `e` — and strict
 * where being strict is the lesson: an unknown field is always an error, never silently
 * ignored, because a rule that quietly matches nothing is the worst thing that can happen to
 * somebody learning this.
 */
class Analizzatore(private val campiNoti: List<String>) {

    fun leggi(testo: String): Lettura {
        val tokens = tokenize(testo)
        if (tokens.isEmpty()) {
            return fallita("Non hai scritto niente. Una regola comincia con un campo, per esempio «tipo».")
        }

        val condizioni = mutableListOf<Condizione>()
        val congiunzioni = mutableListOf<Congiunzione>()
        var soglia: Soglia? = null
        var index = 0

        while (index < tokens.size) {
            if (tokens[index].equals(CONTA, ignoreCase = true)) {
                if (condizioni.isEmpty()) {
                    return fallita(
                        "«conta» da solo non è una regola: prima devi dire quali eventi contare.",
                        suggerimento = "Per esempio: esito = fallito e conta > 5 in 2 minuti per indirizzo",
                    )
                }
                // The `e` before `conta` is optional; if it was written, it is not a join.
                if (congiunzioni.size == condizioni.size) congiunzioni.removeAt(congiunzioni.size - 1)
                when (val letta = leggiSoglia(tokens, index)) {
                    is SogliaLetta.Errore -> return Lettura.Fallita(letta.errore)
                    is SogliaLetta.Ok -> {
                        soglia = letta.soglia
                        index = letta.prossimo
                    }
                }
                if (index < tokens.size) {
                    return fallita(
                        "Dopo «per ${soglia.perCampo}» la regola è finita, ma hai scritto ancora «${tokens[index]}».",
                        parola = tokens[index],
                        suggerimento = "La soglia va sempre in fondo.",
                    )
                }
                continue
            }

            when (val letta = leggiCondizione(tokens, index)) {
                is CondizioneLetta.Errore -> return Lettura.Fallita(letta.errore)
                is CondizioneLetta.Ok -> {
                    condizioni += letta.condizione
                    index = letta.prossimo
                }
            }

            if (index >= tokens.size) break
            // The `e` before a threshold is optional, so a bare `conta` here is not a mistake.
            if (tokens[index].equals(CONTA, ignoreCase = true)) continue

            val congiunzione = Congiunzione.of(tokens[index])
                ?: return fallita(
                    "Fra una condizione e l'altra ci vuole «e» oppure «oppure». Ho trovato «${tokens[index]}».",
                    parola = tokens[index],
                )
            congiunzioni += congiunzione
            index++
            if (index >= tokens.size) {
                return fallita(
                    "La regola finisce con «${congiunzione.parola}» e poi non c'è più niente.",
                    parola = congiunzione.parola,
                )
            }
        }

        if (condizioni.isEmpty()) {
            return fallita("Serve almeno una condizione, per esempio «esito = fallito».")
        }
        return Lettura.Riuscita(Regola(condizioni, congiunzioni, soglia))
    }

    // --- The pieces -----------------------------------------------------------------------

    private sealed interface CondizioneLetta {
        data class Ok(val condizione: Condizione, val prossimo: Int) : CondizioneLetta
        data class Errore(val errore: ErroreDiRegola) : CondizioneLetta
    }

    private fun leggiCondizione(tokens: List<String>, start: Int): CondizioneLetta {
        val campo = tokens[start].lowercase()
        if (campo !in campiNoti) {
            return CondizioneLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Non esiste un campo che si chiama «${tokens[start]}».",
                    parola = tokens[start],
                    suggerimento = suggerisciCampo(campo),
                ),
            )
        }
        val operatoreToken = tokens.getOrNull(start + 1)
            ?: return CondizioneLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Dopo «$campo» manca l'operatore.",
                    parola = campo,
                    suggerimento = "Gli operatori sono: ${Operatore.simboli.joinToString(", ")}",
                ),
            )
        val operatore = Operatore.of(operatoreToken)
            ?: return CondizioneLetta.Errore(
                ErroreDiRegola(
                    messaggio = "«$operatoreToken» non è un operatore.",
                    parola = operatoreToken,
                    suggerimento = "Gli operatori sono: ${Operatore.simboli.joinToString(", ")}",
                ),
            )
        val valore = tokens.getOrNull(start + 2)
            ?: return CondizioneLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Dopo «$campo ${operatore.simbolo}» manca il valore da confrontare.",
                    parola = operatore.simbolo,
                ),
            )
        if (Congiunzione.of(valore) != null) {
            return CondizioneLetta.Errore(
                ErroreDiRegola(
                    messaggio = "«$valore» è una congiunzione, non un valore: dopo «$campo ${operatore.simbolo}» " +
                        "ci vuole la cosa da confrontare.",
                    parola = valore,
                ),
            )
        }
        return CondizioneLetta.Ok(Condizione(campo, operatore, valore), start + 3)
    }

    private sealed interface SogliaLetta {
        data class Ok(val soglia: Soglia, val prossimo: Int) : SogliaLetta
        data class Errore(val errore: ErroreDiRegola) : SogliaLetta
    }

    /** `conta > 5 in 2 minuti per indirizzo` */
    private fun leggiSoglia(tokens: List<String>, start: Int): SogliaLetta {
        var index = start + 1

        val comparatore = tokens.getOrNull(index)
        if (comparatore != ">") {
            return SogliaLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Dopo «conta» ci vuole «>» e poi quanti.",
                    parola = comparatore,
                    suggerimento = "Per esempio: conta > 5 in 2 minuti per indirizzo",
                ),
            )
        }
        index++

        val minimo = tokens.getOrNull(index)?.toIntOrNull()
            ?: return SogliaLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Dopo «conta >» ci vuole un numero. Ho trovato «${tokens.getOrNull(index) ?: "niente"}».",
                    parola = tokens.getOrNull(index),
                ),
            )
        index++

        if (!tokens.getOrNull(index).equals(IN, ignoreCase = true)) {
            return SogliaLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Dopo il numero ci vuole «in», per dire in quanto tempo.",
                    parola = tokens.getOrNull(index),
                    suggerimento = "Per esempio: conta > $minimo in 2 minuti per indirizzo",
                ),
            )
        }
        index++

        val minuti = tokens.getOrNull(index)?.toIntOrNull()
            ?: return SogliaLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Dopo «in» ci vogliono quanti minuti.",
                    parola = tokens.getOrNull(index),
                ),
            )
        if (minuti <= 0) {
            return SogliaLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Una finestra di $minuti minuti non esiste: il tempo deve essere almeno un minuto.",
                    parola = minuti.toString(),
                ),
            )
        }
        index++

        if (!tokens.getOrNull(index).equals(MINUTI, ignoreCase = true)) {
            return SogliaLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Dopo il numero ci vuole la parola «minuti».",
                    parola = tokens.getOrNull(index),
                    suggerimento = "Per esempio: conta > $minimo in $minuti minuti per indirizzo",
                ),
            )
        }
        index++

        if (!tokens.getOrNull(index).equals(PER, ignoreCase = true)) {
            return SogliaLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Manca «per»: una soglia deve dire per chi conta — per indirizzo, per utente…",
                    parola = tokens.getOrNull(index),
                    suggerimento = "Contare senza raggruppare somma persone diverse e non vuol dire niente.",
                ),
            )
        }
        index++

        val campo = tokens.getOrNull(index)?.lowercase()
            ?: return SogliaLetta.Errore(
                ErroreDiRegola(messaggio = "Dopo «per» manca il campo per cui raggruppare."),
            )
        if (campo !in campiNoti) {
            return SogliaLetta.Errore(
                ErroreDiRegola(
                    messaggio = "Non esiste un campo che si chiama «${tokens[index]}».",
                    parola = tokens[index],
                    suggerimento = suggerisciCampo(campo),
                ),
            )
        }
        return SogliaLetta.Ok(Soglia(minimo, minuti, campo), index + 1)
    }

    // --- Helpers --------------------------------------------------------------------------

    private fun fallita(messaggio: String, parola: String? = null, suggerimento: String? = null) =
        Lettura.Fallita(ErroreDiRegola(messaggio, parola, suggerimento))

    /**
     * The nearest known field, when the student was close.
     *
     * A typo on a field name is the single most common mistake, and "forse intendevi utente?"
     * turns a dead end into a correction.
     */
    private fun suggerisciCampo(scritto: String): String {
        val vicino = campiNoti.minByOrNull { distanza(it, scritto) }
        val soglia = (scritto.length / 2).coerceAtLeast(2)
        return if (vicino != null && distanza(vicino, scritto) <= soglia) {
            "Forse intendevi «$vicino»? I campi sono: ${campiNoti.joinToString(", ")}"
        } else {
            "I campi sono: ${campiNoti.joinToString(", ")}"
        }
    }

    private fun distanza(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[b.length]
    }

    private fun tokenize(testo: String): List<String> = buildList {
        var current = StringBuilder()
        var quoted = false
        testo.forEach { char ->
            when {
                char == '"' || char == '«' || char == '»' -> quoted = !quoted
                char.isWhitespace() && !quoted -> {
                    if (current.isNotEmpty()) {
                        add(current.toString())
                        current = StringBuilder()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) add(current.toString())
    }

    private companion object {
        const val CONTA = "conta"
        const val IN = "in"
        const val MINUTI = "minuti"
        const val PER = "per"
    }
}
