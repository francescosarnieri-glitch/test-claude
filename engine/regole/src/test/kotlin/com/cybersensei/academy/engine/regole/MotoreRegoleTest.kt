package com.cybersensei.academy.engine.regole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule engine.
 *
 * Everything here is a fact the student will be told to their face — "questa regola avrebbe
 * preso quattro attacchi su cinque" — so it has to be true. An engine that scores a correct
 * rule as wrong does not just break an exercise: it teaches the opposite of the lesson.
 */
class MotoreRegoleTest {

    private fun evento(id: String, ora: String, vararg campi: Pair<String, String>) =
        LogEvent(id, ora, campi.toMap())

    private fun registro(vararg eventi: LogEvent) = LogSource(
        id = "prova",
        titolo = "Prova",
        descrizione = "Un registro di prova",
        colonne = listOf("tipo", "esito"),
        eventi = eventi.toList(),
    )

    private val campi = listOf("tipo", "esito", "utente", "indirizzo", "origine", "risorsa", "byte")

    private fun leggi(testo: String): Regola {
        val lettura = Analizzatore(campi).leggi(testo)
        assertTrue("«$testo» doveva leggersi: $lettura", lettura is Lettura.Riuscita)
        return (lettura as Lettura.Riuscita).regola
    }

    private fun errore(testo: String): ErroreDiRegola {
        val lettura = Analizzatore(campi).leggi(testo)
        assertTrue("«$testo» doveva dare errore", lettura is Lettura.Fallita)
        return (lettura as Lettura.Fallita).errore
    }

    // --- Le condizioni --------------------------------------------------------------------

    @Test
    fun `uguale e diverso guardano tutto il valore`() {
        val e = evento("x", "08:00:00", "esito" to "fallito")
        assertTrue(leggi("esito = fallito").filtra(e))
        assertFalse(leggi("esito = fallit").filtra(e))
        assertFalse(leggi("esito != fallito").filtra(e))
        assertTrue(leggi("esito != riuscito").filtra(e))
    }

    @Test
    fun `il confronto non guarda le maiuscole`() {
        val e = evento("x", "08:00:00", "esito" to "Fallito")
        assertTrue(leggi("esito = fallito").filtra(e))
        assertTrue(leggi("ESITO = FALLITO").filtra(e))
    }

    @Test
    fun `contiene guarda dentro il valore`() {
        val e = evento("x", "08:00:00", "risorsa" to "/backup/scarica")
        assertTrue(leggi("risorsa contiene /backup").filtra(e))
        assertTrue(leggi("risorsa contiene scarica").filtra(e))
        assertFalse(leggi("risorsa contiene /admin").filtra(e))
    }

    @Test
    fun `un campo che l'evento non ha non corrisponde mai, tranne con diverso`() {
        val e = evento("x", "08:00:00", "esito" to "riuscito")
        // Un accesso non ha una risorsa: non deve entrare in una regola sulle risorse.
        assertFalse(leggi("risorsa contiene /backup").filtra(e))
        assertFalse(leggi("risorsa = /backup").filtra(e))
        // Ma "risorsa diversa da /backup" su un evento senza risorsa è vero, ed è giusto:
        // quell'evento non è /backup.
        assertTrue(leggi("risorsa != /backup").filtra(e))
    }

    @Test
    fun `i numeri si confrontano come numeri, non come parole`() {
        val e = evento("x", "08:00:00", "byte" to "9000")
        assertTrue(leggi("byte > 800").filtra(e))
        assertFalse(leggi("byte < 800").filtra(e))
    }

    // --- e / oppure -----------------------------------------------------------------------

    @Test
    fun `e restringe, oppure allarga`() {
        val e = evento("x", "08:00:00", "tipo" to "accesso", "esito" to "fallito")
        assertTrue(leggi("tipo = accesso e esito = fallito").filtra(e))
        assertFalse(leggi("tipo = accesso e esito = riuscito").filtra(e))
        assertTrue(leggi("tipo = richiesta oppure esito = fallito").filtra(e))
        assertFalse(leggi("tipo = richiesta oppure esito = riuscito").filtra(e))
    }

    @Test
    fun `e lega piu' stretto di oppure`() {
        // «a e b oppure c» si legge «(a e b) oppure c», come in ogni linguaggio che lo
        // studente incontrera' dopo. Con la precedenza sbagliata questo evento non passerebbe.
        val e = evento("x", "08:00:00", "tipo" to "richiesta", "esito" to "riuscito", "origine" to "esterna")
        val regola = leggi("tipo = accesso e esito = fallito oppure origine = esterna")
        assertTrue(regola.filtra(e))

        val altro = evento("y", "08:00:00", "tipo" to "accesso", "esito" to "riuscito", "origine" to "interna")
        assertFalse(regola.filtra(altro))
    }

    // --- La soglia ------------------------------------------------------------------------

    @Test
    fun `sotto la soglia non si accende niente`() {
        val log = registro(
            evento("e1", "08:00:10", "esito" to "fallito", "indirizzo" to "1.1.1.1"),
            evento("e2", "08:00:40", "esito" to "fallito", "indirizzo" to "1.1.1.1"),
            evento("e3", "08:01:10", "esito" to "fallito", "indirizzo" to "1.1.1.1"),
        )
        val esito = MotoreRegole.esegui(
            leggi("esito = fallito e conta > 4 in 5 minuti per indirizzo"),
            log,
            emptySet(),
        )
        assertTrue(esito.accesi.isEmpty())
        assertTrue("Una regola che non si accende mai va detto", esito.muta)
    }

    @Test
    fun `la soglia accende tutta la raffica, non solo l'evento che la supera`() {
        // Chi guarda l'allarme deve vedere la raffica intera, non la quinta riga da sola.
        val log = registro(
            *(1..6).map { evento("e$it", "08:0${it - 1}:00", "esito" to "fallito", "indirizzo" to "1.1.1.1") }
                .toTypedArray(),
        )
        val esito = MotoreRegole.esegui(
            leggi("esito = fallito e conta > 4 in 6 minuti per indirizzo"),
            log,
            emptySet(),
        )
        assertEquals(6, esito.accesi.size)
    }

    @Test
    fun `la soglia conta separatamente per ogni gruppo`() {
        // Tre tentativi da tre indirizzi diversi non sono nove tentativi.
        val eventi = mutableListOf<LogEvent>()
        listOf("1.1.1.1", "2.2.2.2", "3.3.3.3").forEachIndexed { g, ind ->
            (0..2).forEach { n ->
                eventi += evento("e$g$n", "08:0$n:00", "esito" to "fallito", "indirizzo" to ind)
            }
        }
        val log = registro(*eventi.toTypedArray())
        assertTrue(
            MotoreRegole.esegui(
                leggi("esito = fallito e conta > 4 in 5 minuti per indirizzo"),
                log,
                emptySet(),
            ).accesi.isEmpty(),
        )
    }

    @Test
    fun `cambiare il campo del raggruppamento cambia tutto`() {
        // La lezione dell'ultimo esercizio: stesso attacco, sei indirizzi, un solo account.
        val eventi = mutableListOf<LogEvent>()
        listOf("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5", "6.6.6.6")
            .forEachIndexed { g, ind ->
                (0..2).forEach { n ->
                    eventi += evento(
                        "e$g$n",
                        "22:${(40 + g * 2 + n).toString().padStart(2, '0')}:00",
                        "esito" to "fallito",
                        "indirizzo" to ind,
                        "utente" to "direzione",
                    )
                }
            }
        val log = registro(*eventi.toTypedArray())

        val perIndirizzo = MotoreRegole.esegui(
            leggi("esito = fallito e conta > 4 in 5 minuti per indirizzo"),
            log,
            eventi.map { it.id }.toSet(),
        )
        assertEquals("Raggruppando per indirizzo non si vede niente", 0, perIndirizzo.presi.size)

        val perUtente = MotoreRegole.esegui(
            leggi("esito = fallito e conta > 4 in 5 minuti per utente"),
            log,
            eventi.map { it.id }.toSet(),
        )
        assertEquals("Raggruppando per utente si vede tutto", eventi.size, perUtente.presi.size)
    }

    @Test
    fun `la finestra scorre e non e' allineata all'orologio`() {
        // Cinque tentativi a cavallo di un minuto tondo devono contare come cinque tentativi.
        val log = registro(
            evento("e1", "08:58:30", "esito" to "fallito", "indirizzo" to "1.1.1.1"),
            evento("e2", "08:59:00", "esito" to "fallito", "indirizzo" to "1.1.1.1"),
            evento("e3", "08:59:30", "esito" to "fallito", "indirizzo" to "1.1.1.1"),
            evento("e4", "09:00:10", "esito" to "fallito", "indirizzo" to "1.1.1.1"),
            evento("e5", "09:00:40", "esito" to "fallito", "indirizzo" to "1.1.1.1"),
        )
        val esito = MotoreRegole.esegui(
            leggi("esito = fallito e conta > 4 in 5 minuti per indirizzo"),
            log,
            emptySet(),
        )
        assertEquals(5, esito.accesi.size)
    }

    // --- Il punteggio ---------------------------------------------------------------------

    @Test
    fun `presi persi e falsi contano quello che dicono`() {
        val log = registro(
            evento("attacco1", "08:00:00", "esito" to "fallito", "origine" to "esterna"),
            evento("attacco2", "08:01:00", "esito" to "fallito", "origine" to "esterna"),
            evento("collega", "08:02:00", "esito" to "fallito", "origine" to "interna"),
            evento("normale", "08:03:00", "esito" to "riuscito", "origine" to "interna"),
        )
        val bersagli = setOf("attacco1", "attacco2")

        val larga = MotoreRegole.esegui(leggi("esito = fallito"), log, bersagli)
        assertEquals(listOf("attacco1", "attacco2"), larga.presi)
        assertTrue(larga.persi.isEmpty())
        assertEquals(listOf("collega"), larga.falsi)
        assertEquals(100, larga.copertura)
        assertEquals(33, larga.rumore)
        assertFalse(larga.perfetto)

        val giusta = MotoreRegole.esegui(leggi("esito = fallito e origine = esterna"), log, bersagli)
        assertTrue(giusta.perfetto)
        assertEquals(100, giusta.copertura)
        assertEquals(0, giusta.rumore)

        val stretta = MotoreRegole.esegui(leggi("esito = fallito e origine = interna"), log, bersagli)
        assertEquals(listOf("attacco1", "attacco2"), stretta.persi)
        assertEquals(0, stretta.copertura)
        assertFalse(stretta.perfetto)
    }

    @Test
    fun `una regola che non si accende mai non e' perfetta`() {
        val log = registro(evento("attacco", "08:00:00", "esito" to "fallito"))
        val esito = MotoreRegole.esegui(leggi("esito = inesistente"), log, setOf("attacco"))
        assertFalse("Zero righe non è un successo", esito.perfetto)
        assertTrue(esito.muta)
    }

    @Test
    fun `i persi sono elencati nell'ordine del registro`() {
        val log = registro(
            evento("a", "08:00:00", "esito" to "fallito"),
            evento("b", "08:01:00", "esito" to "fallito"),
            evento("c", "08:02:00", "esito" to "fallito"),
        )
        val esito = MotoreRegole.esegui(leggi("esito = riuscito"), log, setOf("c", "a", "b"))
        assertEquals(listOf("a", "b", "c"), esito.persi)
    }
}
