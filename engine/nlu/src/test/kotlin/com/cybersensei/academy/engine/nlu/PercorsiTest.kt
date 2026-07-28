package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'albero con cui il professore conduce.
 *
 * Il difetto che questo file impedisce e' silenzioso e inevitabile: qualcuno aggiunge una
 * risposta al corpus e si dimentica di metterla in un ramo. La risposta esiste, e' scritta,
 * e' verificata — e nessuno studente potra' mai raggiungerla.
 */
class PercorsiTest {

    private val knowledgeBase = KnowledgeBase.fromResources()
    private val percorsi = StudyPaths.fromResources()

    @Test
    fun `ogni risposta della scuola si raggiunge toccando`() {
        assertEquals(emptyList<String>(), percorsi.validate(knowledgeBase))
    }

    /**
     * Il primo livello sono *situazioni*, non un indice.
     *
     * Chi ha appena cliccato su un link non conosce la parola «phishing»: chiedergli di
     * conoscerla prima di poter essere aiutato e' esattamente il fallimento che questo albero
     * sostituisce. Il ramo delle situazioni deve quindi esistere, e deve essere il primo.
     */
    @Test
    fun `si comincia da cosa e' successo, non dal programma`() {
        val primo = percorsi.branches.first()

        assertEquals("successo", primo.id)
        assertTrue(
            "Chi ha un problema adesso deve trovarci dentro qualcosa: ${domandeSotto(primo)}",
            domandeSotto(primo) >= 20,
        )
    }

    private fun domandeSotto(ramo: Branch): Int =
        ramo.items.size + ramo.branches.sumOf { domandeSotto(it) }

    @Test
    fun `ogni ramo sa dire da dove si arriva`() {
        percorsi.all.forEach { ramo ->
            val strada = percorsi.trail(ramo.id)
            assertTrue("Il ramo '${ramo.id}' non ha una strada", strada.isNotEmpty())
            assertEquals(ramo.id, strada.last().id)
            assertTrue(strada.first().id in percorsi.branches.map { it.id })
        }
    }

    /** Un ramo con dentro quaranta domande e' un elenco telefonico, non una scelta. */
    @Test
    fun `nessun ramo mette davanti troppe cose insieme`() {
        percorsi.all.forEach { ramo ->
            assertTrue(
                "Il ramo '${ramo.id}' offre ${ramo.items.size} domande in una volta",
                ramo.items.size <= 20,
            )
            assertTrue(
                "Il ramo '${ramo.id}' offre ${ramo.branches.size} sottorami in una volta",
                ramo.branches.size <= 12,
            )
        }
    }

    /**
     * Nessuna domanda deve esistere due volte con parole diverse.
     *
     * Non e' pignoleria: due voci sullo stesso argomento si contraddicono appena una delle due
     * viene corretta, e nessuno si accorge che l'altra e' rimasta indietro. E' successo con la
     * VPN, scritta due volte a mesi di distanza.
     */
    @Test
    fun `nessuna domanda e' scritta due volte`() {
        val doppie = knowledgeBase.entries
            .groupBy { it.question.trim().lowercase() }
            .filterValues { it.size > 1 }
            .map { (domanda, voci) -> "«$domanda» -> ${voci.map { it.id }}" }

        assertEquals(emptyList<String>(), doppie)
    }

    /**
     * Ogni risposta deve essere una risposta, non un'alzata di spalle.
     *
     * Il minimo esiste perche' una scuola che risponde «dipende» ha risposto peggio che
     * tacendo; il massimo perche' oltre una certa lunghezza sullo schermo di un telefono non
     * legge piu' nessuno, e una risposta non letta non e' stata data.
     */
    @Test
    fun `le risposte hanno una lunghezza da risposta`() {
        val fuori = knowledgeBase.entries
            .filter { it.kind == EntryKind.LESSON }
            .filter { it.answer.length !in 150..900 }
            .map { "${it.id}: ${it.answer.length} caratteri" }

        assertEquals(emptyList<String>(), fuori)
    }

    /** Il professore parla arrivando: un elenco senza voce non e' un professore. */
    @Test
    fun `i rami principali hanno una battuta del professore`() {
        percorsi.branches.forEach { ramo ->
            assertTrue("Il ramo '${ramo.id}' arriva muto", !ramo.line.isNullOrBlank())
            assertTrue("Il ramo '${ramo.id}' non ha titolo", ramo.title.isNotBlank())
        }
    }
}
