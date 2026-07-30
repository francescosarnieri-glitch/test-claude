package com.cybersensei.academy.content

import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.engine.regole.Analizzatore
import com.cybersensei.academy.engine.regole.Lettura
import com.cybersensei.academy.engine.regole.Palestra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nobody reaches the workshop without having been taught the language first.
 *
 * The workshop asks the student to *write* something, which no other part of this school does.
 * The teaching for it lives in a lesson like everything else — «Scrivere una regola», the last
 * one of module 3.3 — and the ordinary padlock rule does the rest: a module counts as studied
 * only when every one of its lessons has had its interrogation sat.
 *
 * That chain is three separate facts in three separate files, and any one of them can be broken
 * by an edit that looks harmless. This is where it breaks the build instead.
 */
class TirocinioProntoTest {

    private val curriculum = Curriculum.fromResources()
    private val palestra = Palestra.fromResources()

    private val modulo = curriculum.module(MODULO)!!
    private val lezione = modulo.lessons.firstOrNull { it.id == LEZIONE }

    @Test
    fun `la lezione che insegna il linguaggio esiste`() {
        assertTrue("Manca la lezione '$LEZIONE' nel modulo '$MODULO'", lezione != null)
    }

    @Test
    fun `e' l'ultima del suo modulo`() {
        // Ultima perche' arriva dopo il ragionamento: prima si impara cos'e' una rilevazione e
        // quanto costano i falsi positivi, poi come si scrive.
        assertEquals(LEZIONE, modulo.lessons.last().id)
    }

    @Test
    fun `ogni esercizio del tirocinio si apre con quel modulo`() {
        palestra.esercizi.forEach {
            assertEquals(
                "L'esercizio '${it.id}' non passa dal modulo che insegna a scrivere le regole",
                MODULO,
                it.apreCon,
            )
        }
    }

    /**
     * Il pezzo che rende vero tutto il resto.
     *
     * Il percorso considera un modulo «studiato» solo quando **tutte** le sue lezioni sono
     * state completate — cioe' lette e interrogate. Finche' la lezione sul linguaggio sta
     * dentro questo modulo, non esiste nessun percorso che arrivi al tirocinio saltandola.
     */
    @Test
    fun `non si arriva al tirocinio senza aver fatto quella lezione`() {
        val lezioniDelModulo = modulo.lessons.map { it.id }
        assertTrue(
            "La lezione sul linguaggio deve stare nel modulo che apre il tirocinio",
            LEZIONE in lezioniDelModulo,
        )
    }

    @Test
    fun `la lezione viene interrogata sulla sua competenza`() {
        // Senza domande sulla propria competenza, l'interrogazione della lezione ripescherebbe
        // quelle del modulo: si studierebbe il linguaggio e si verrebbe esaminati sui concetti.
        val competenza = lezione!!.skills.single()
        val domande = curriculum.questionsAfter(LEZIONE)
        assertTrue("La lezione non dichiara nessuna competenza propria", competenza.isNotEmpty())
        assertTrue("Nessuna domanda misura '$competenza'", domande.isNotEmpty())
        assertTrue(
            "L'interrogazione della lezione deve chiedere del linguaggio, non d'altro",
            domande.all { it.skill == competenza },
        )
    }

    /**
     * Quello che la lezione insegna e quello che il motore accetta sono la stessa cosa.
     *
     * Ogni esempio scritto nelle schede viene dato in pasto all'analizzatore vero. Se un giorno
     * cambio il linguaggio e mi dimentico la lezione, lo studente imparerebbe a scrivere una
     * regola che l'app rifiuta — ed e' il modo piu' rapido di far smettere qualcuno.
     */
    @Test
    fun `gli esempi della lezione si leggono davvero`() {
        val campi = palestra.registri.flatMap { it.campi }.distinct()
        val analizzatore = Analizzatore(campi)

        val esempi = lezione!!.cards
            .flatMap { it.body.lines() }
            .map { it.trim() }
            // Nelle schede gli esempi sono le righe rientrate, come nel resto del programma.
            .filter { riga -> ESEMPI.any { riga.startsWith(it) } }

        assertTrue("Nessun esempio da controllare: le schede non insegnano niente", esempi.isNotEmpty())
        esempi.forEach { esempio ->
            val lettura = analizzatore.leggi(esempio)
            assertTrue(
                "L'esempio «$esempio» non e' leggibile dal motore: " +
                    (lettura as? Lettura.Fallita)?.errore?.messaggio.orEmpty(),
                lettura is Lettura.Riuscita,
            )
        }
    }

    private companion object {
        const val MODULO = "mod_detection"
        const val LEZIONE = "les_det_linguaggio"

        /** Le prime parole con cui puo' cominciare un esempio di regola nelle schede. */
        val ESEMPI = listOf("esito ", "risorsa ", "origine ", "byte ", "utente ", "tipo ", "indirizzo ")
    }
}
