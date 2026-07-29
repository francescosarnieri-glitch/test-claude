package com.cybersensei.academy.core.model

/**
 * The four stages of the school. [INTRO] is the short "first day of class" that
 * exists mainly so the professor can get to know the student.
 */
enum class Level(val order: Int, val italianName: String, val subtitle: String) {
    INTRO(0, "Introduzione", "Chi sei, chi sono io, e come funziona questa scuola"),
    EASY(1, "Le fondamenta", "Quello che serve a chiunque abbia un telefono e un conto"),
    INTERMEDIATE(2, "Il mestiere", "Come stanno in piedi le difese, e dove cedono"),
    HARD(3, "Il difensore", "Attacchi veri, risposta agli incidenti, e cosa si dice dopo");

    /**
     * Come si legge nel percorso: il numero dice quanti gradini mancano, il nome cosa c'e'
     * dentro.
     *
     * «Facile», «Intermedio» e «Difficile» sono state tolte perche' descrivevano male la
     * cosa che nominavano. Il livello che si chiamava facile contiene phishing, ransomware e
     * furto d'identita' ed e' il piu' utile della scuola per una persona qualunque: chiamarlo
     * facile lo sminuiva. E all'altro capo, «difficile» faceva sembrare l'ultimo livello roba
     * per specialisti, che e' il modo migliore per far smettere qualcuno a due terzi.
     */
    val label: String get() = if (order == 0) italianName else "$order · $italianName"

    val next: Level?
        get() = entries.firstOrNull { it.order == order + 1 }

    companion object {
        fun fromOrder(order: Int): Level? = entries.firstOrNull { it.order == order }
    }
}
