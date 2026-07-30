package com.cybersensei.academy.engine.regole

/**
 * The little language the student writes rules in.
 *
 * It is small on purpose and it is not a toy: field, operator, value, joined by `e` / `oppure`,
 * with an optional threshold over a time window grouped by an entity. That is the shape of
 * every real detection language — Sigma, KQL, the rest — with the keywords in Italian and the
 * corners taken off. A student who understands `conta > 5 in 2 minuti per indirizzo` has
 * understood the idea those languages are built on.
 *
 *     tipo = accesso e esito = fallito
 *     tipo = accesso e esito = fallito e conta > 5 in 2 minuti per indirizzo
 *     risorsa contiene /admin oppure risorsa contiene /backup
 */

/** How a field is compared to a value. */
enum class Operatore(val simbolo: String, val spiegazione: String) {
    UGUALE("=", "il campo è esattamente questo valore"),
    DIVERSO("!=", "il campo è qualunque cosa tranne questo valore"),
    CONTIENE("contiene", "il valore compare da qualche parte dentro il campo"),
    MAGGIORE(">", "il campo, letto come numero, è più grande"),
    MINORE("<", "il campo, letto come numero, è più piccolo");

    companion object {
        fun of(text: String): Operatore? =
            entries.firstOrNull { it.simbolo.equals(text, ignoreCase = true) }

        val simboli: List<String> get() = entries.map { it.simbolo }
    }
}

/** How two conditions are joined. */
enum class Congiunzione(val parola: String) {
    E("e"),
    OPPURE("oppure");

    companion object {
        fun of(text: String): Congiunzione? =
            entries.firstOrNull { it.parola.equals(text, ignoreCase = true) }
    }
}

/** One comparison: `esito = fallito`. */
data class Condizione(
    val campo: String,
    val operatore: Operatore,
    val valore: String,
) {
    fun matches(event: LogEvent): Boolean {
        val actual = event.campi[campo] ?: return operatore == Operatore.DIVERSO
        return when (operatore) {
            Operatore.UGUALE -> actual.equals(valore, ignoreCase = true)
            Operatore.DIVERSO -> !actual.equals(valore, ignoreCase = true)
            Operatore.CONTIENE -> actual.contains(valore, ignoreCase = true)
            Operatore.MAGGIORE -> compareNumbers(actual) { a, b -> a > b }
            Operatore.MINORE -> compareNumbers(actual) { a, b -> a < b }
        }
    }

    private fun compareNumbers(actual: String, comparison: (Long, Long) -> Boolean): Boolean {
        val left = actual.filter { it.isDigit() || it == '-' }.toLongOrNull() ?: return false
        val right = valore.filter { it.isDigit() || it == '-' }.toLongOrNull() ?: return false
        return comparison(left, right)
    }

    override fun toString() = "$campo ${operatore.simbolo} $valore"
}

/**
 * A threshold over a sliding window, grouped by an entity: `conta > 5 in 2 minuti per indirizzo`.
 *
 * This is the part that turns a filter into a rule. A single failed login is nothing; twenty
 * from the same address inside two minutes is somebody trying passwords. The grouping field is
 * where most of the thinking happens — the same events grouped by user rather than by address
 * catch an attacker who changed address, and miss one who changed account.
 */
data class Soglia(
    val minimo: Int,
    val minuti: Int,
    val perCampo: String,
) {
    override fun toString() = "conta > $minimo in $minuti minuti per $perCampo"
}

/** A parsed rule, ready to be run. */
data class Regola(
    val condizioni: List<Condizione>,
    val congiunzioni: List<Congiunzione>,
    val soglia: Soglia? = null,
) {
    /**
     * Whether one event passes the conditions, ignoring any threshold.
     *
     * `e` binds tighter than `oppure`, the way it does in every language the student will meet
     * later: `a e b oppure c` is `(a e b) oppure c`.
     */
    fun filtra(event: LogEvent): Boolean {
        if (condizioni.isEmpty()) return false
        var orResult = false
        var andRun = condizioni.first().matches(event)
        congiunzioni.forEachIndexed { index, congiunzione ->
            val next = condizioni[index + 1].matches(event)
            when (congiunzione) {
                Congiunzione.E -> andRun = andRun && next
                Congiunzione.OPPURE -> {
                    orResult = orResult || andRun
                    andRun = next
                }
            }
        }
        return orResult || andRun
    }

    override fun toString(): String = buildString {
        condizioni.forEachIndexed { index, condizione ->
            if (index > 0) append(" ${congiunzioni[index - 1].parola} ")
            append(condizione)
        }
        soglia?.let { append(" e $it") }
    }
}
