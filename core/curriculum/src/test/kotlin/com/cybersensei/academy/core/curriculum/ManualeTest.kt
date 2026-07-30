package com.cybersensei.academy.core.curriculum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il libro, misurato contro se stesso.
 *
 * L'indice e' la cosa che si guarda per prima e la piu' facile da rompere in silenzio: basta
 * aggiungere una pagina in mezzo e tutti i numeri dopo diventano bugie. Qui la numerazione viene
 * ricontrollata dall'inizio alla fine a ogni build.
 */
class ManualeTest {

    private val manuale = Manuale.fromResources()

    @Test
    fun `il contenuto e' valido`() {
        val problemi = manuale.validate()
        assertTrue(problemi.toString(), problemi.isEmpty())
    }

    @Test
    fun `la numerazione corre dall'inizio alla fine senza salti`() {
        manuale.pagine.forEachIndexed { indice, pagina ->
            assertEquals("La pagina «${pagina.titolo}»", indice + 1, pagina.numero)
        }
    }

    @Test
    fun `ogni capitolo del programma ha il suo capitolo nel libro`() {
        // Ventisette moduli, ventisette capitoli numerati: e' la promessa fatta scegliendo
        // «segue il programma», ed e' quella che rende sensato dire «rileggi il capitolo».
        val moduliDelProgramma = Curriculum.fromResources().modules.size
        val capitoliNumerati = manuale.capitoli.count { it.numero > 0 }
        assertEquals(moduliDelProgramma, capitoliNumerati)
    }

    @Test
    fun `i capitoli sono numerati di fila`() {
        val numerati = manuale.capitoli.filter { it.numero > 0 }
        numerati.forEachIndexed { indice, capitolo ->
            assertEquals("Il capitolo «${capitolo.titolo}»", indice + 1, capitolo.numero)
        }
    }

    @Test
    fun `ogni capitolo chiude tirando le somme`() {
        // Serve a chi non ha tempo di rileggere il capitolo intero, ed e' la pagina che si
        // ritrova piu' spesso: se manca, il capitolo non ha una fine.
        manuale.capitoli.filter { it.numero > 0 }.forEach {
            assertEquals(
                "Il capitolo «${it.titolo}» non chiude con «Cosa portarti dietro»",
                "Cosa portarti dietro",
                it.pagine.last().titolo,
            )
        }
    }

    @Test
    fun `il segnalibro trova sempre il capitolo giusto`() {
        manuale.pagine.forEach { pagina ->
            val capitolo = manuale.capitoloDellaPagina(pagina.numero)
            assertTrue("La pagina ${pagina.numero} non appartiene a nessun capitolo", capitolo != null)
            assertTrue(pagina.numero in capitolo!!.daPagina..capitolo.aPagina)
        }
    }

    @Test
    fun `la prefazione e' scritta`() {
        // Le altre pagine arriveranno; questa serve subito, perche' un libro che si apre su una
        // pagina vuota non invita nessuno a tornarci.
        val prefazione = manuale.capitoli.first()
        assertTrue("La prefazione deve essere scritta", prefazione.finito)
    }
}
