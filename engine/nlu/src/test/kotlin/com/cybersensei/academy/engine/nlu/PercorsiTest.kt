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
        assertTrue("Il ramo delle situazioni e' vuoto", primo.items.size >= 8)
        assertTrue(
            "Le situazioni devono essere scritte con le parole di chi le vive",
            primo.items.all { !it.text.isNullOrBlank() },
        )
    }

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

    /** Il professore parla arrivando: un elenco senza voce non e' un professore. */
    @Test
    fun `i rami principali hanno una battuta del professore`() {
        percorsi.branches.forEach { ramo ->
            assertTrue("Il ramo '${ramo.id}' arriva muto", !ramo.line.isNullOrBlank())
            assertTrue("Il ramo '${ramo.id}' non ha titolo", ramo.title.isNotBlank())
        }
    }
}
