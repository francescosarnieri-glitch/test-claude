package com.cybersensei.academy.engine.regole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser, and above all what it says when the student is wrong.
 *
 * Somebody writing their first rule gets it wrong four times before getting it right. What the
 * parser says in those four moments is the whole difference between an exercise that teaches
 * and one that is abandoned — so the messages are tested like any other behaviour.
 */
class AnalizzatoreTest {

    private val campi = listOf("tipo", "esito", "utente", "indirizzo", "origine", "risorsa")
    private val analizzatore = Analizzatore(campi)

    private fun ok(testo: String): Regola {
        val lettura = analizzatore.leggi(testo)
        assertTrue("«$testo» doveva leggersi, invece: $lettura", lettura is Lettura.Riuscita)
        return (lettura as Lettura.Riuscita).regola
    }

    private fun ko(testo: String): ErroreDiRegola {
        val lettura = analizzatore.leggi(testo)
        assertTrue("«$testo» doveva dare errore, invece è passato", lettura is Lettura.Fallita)
        return (lettura as Lettura.Fallita).errore
    }

    // --- Quello che deve funzionare --------------------------------------------------------

    @Test
    fun `una condizione sola`() {
        val regola = ok("esito = fallito")
        assertEquals(1, regola.condizioni.size)
        assertEquals("esito", regola.condizioni.first().campo)
        assertEquals(Operatore.UGUALE, regola.condizioni.first().operatore)
        assertEquals("fallito", regola.condizioni.first().valore)
    }

    @Test
    fun `spazi in piu' e maiuscole non danno fastidio`() {
        val regola = ok("   ESITO   =   fallito   ")
        assertEquals("esito", regola.condizioni.first().campo)
    }

    @Test
    fun `un valore con gli spazi si scrive fra virgolette`() {
        val regola = ok("""risorsa = "/clienti/scheda cliente"""")
        assertEquals("/clienti/scheda cliente", regola.condizioni.first().valore)
    }

    @Test
    fun `la soglia si legge tutta`() {
        val regola = ok("esito = fallito e conta > 4 in 5 minuti per indirizzo")
        assertEquals(1, regola.condizioni.size)
        assertTrue("La «e» prima di «conta» non è una congiunzione", regola.congiunzioni.isEmpty())
        assertEquals(Soglia(4, 5, "indirizzo"), regola.soglia)
    }

    @Test
    fun `la soglia si puo' scrivere anche senza la e`() {
        assertEquals(Soglia(4, 5, "utente"), ok("esito = fallito conta > 4 in 5 minuti per utente").soglia)
    }

    @Test
    fun `la regola si rilegge come l'ha scritta lo studente`() {
        val testo = "esito = fallito e origine = esterna"
        assertEquals(testo, ok(testo).toString())
        assertEquals(
            "esito = fallito e conta > 4 in 5 minuti per indirizzo",
            ok("esito = fallito e conta > 4 in 5 minuti per indirizzo").toString(),
        )
    }

    // --- Quello che deve dare errore, e come lo dice ----------------------------------------

    @Test
    fun `una casella vuota non e' una regola`() {
        assertTrue(ko("").messaggio.contains("Non hai scritto niente"))
    }

    @Test
    fun `un campo inventato viene sempre respinto`() {
        // Mai ignorato in silenzio: una condizione ignorata darebbe una regola che non si
        // accende mai, e lo studente non saprebbe perché.
        val errore = ko("username = m.rossi")
        assertTrue(errore.messaggio.contains("username"))
        assertTrue(errore.suggerimento.orEmpty().contains("utente"))
    }

    @Test
    fun `un campo scritto quasi giusto riceve il suggerimento`() {
        assertTrue(ko("utnte = m.rossi").suggerimento.orEmpty().contains("Forse intendevi «utente»"))
        assertTrue(ko("indirizo = 1.1.1.1").suggerimento.orEmpty().contains("Forse intendevi «indirizzo»"))
    }

    @Test
    fun `un campo lontanissimo riceve l'elenco, non un suggerimento a caso`() {
        val suggerimento = ko("banana = 3").suggerimento.orEmpty()
        assertTrue(suggerimento.contains("I campi sono"))
        assertTrue("Non deve inventare un suggerimento", !suggerimento.contains("Forse intendevi"))
    }

    @Test
    fun `un operatore che non esiste viene spiegato`() {
        val errore = ko("esito == fallito")
        assertTrue(errore.messaggio.contains("non è un operatore"))
        assertTrue(errore.suggerimento.orEmpty().contains("contiene"))
    }

    @Test
    fun `una condizione a meta' lo dice`() {
        assertTrue(ko("esito").messaggio.contains("manca l'operatore"))
        assertTrue(ko("esito =").messaggio.contains("manca il valore"))
        assertTrue(ko("esito = fallito e").messaggio.contains("non c'è più niente"))
    }

    @Test
    fun `una congiunzione al posto del valore viene riconosciuta`() {
        // «esito = e origine = esterna» è un errore di distrazione frequentissimo.
        assertTrue(ko("esito = e origine = esterna").messaggio.contains("è una congiunzione"))
    }

    @Test
    fun `due condizioni senza congiunzione lo dicono`() {
        assertTrue(ko("esito = fallito origine = esterna").messaggio.contains("ci vuole «e» oppure «oppure»"))
    }

    @Test
    fun `la soglia da sola non e' una regola`() {
        assertTrue(
            ko("conta > 4 in 5 minuti per indirizzo").messaggio.contains("prima devi dire quali eventi contare"),
        )
    }

    @Test
    fun `ogni pezzo mancante della soglia ha il suo messaggio`() {
        assertTrue(ko("esito = fallito e conta 4 in 5 minuti per indirizzo").messaggio.contains("ci vuole «>»"))
        assertTrue(ko("esito = fallito e conta > molti in 5 minuti per indirizzo").messaggio.contains("un numero"))
        assertTrue(ko("esito = fallito e conta > 4 5 minuti per indirizzo").messaggio.contains("«in»"))
        assertTrue(ko("esito = fallito e conta > 4 in 5 per indirizzo").messaggio.contains("«minuti»"))
        assertTrue(ko("esito = fallito e conta > 4 in 5 minuti").messaggio.contains("Manca «per»"))
        assertTrue(ko("esito = fallito e conta > 4 in 5 minuti per").messaggio.contains("manca il campo"))
    }

    @Test
    fun `una finestra di zero minuti viene fermata`() {
        assertTrue(ko("esito = fallito e conta > 4 in 0 minuti per indirizzo").messaggio.contains("almeno un minuto"))
    }

    @Test
    fun `dopo la soglia non ci puo' essere altro`() {
        val errore = ko("esito = fallito e conta > 4 in 5 minuti per indirizzo e origine = esterna")
        assertTrue(errore.messaggio.contains("la regola è finita"))
        assertTrue(errore.suggerimento.orEmpty().contains("in fondo"))
    }

    @Test
    fun `ogni errore indica la parola su cui si e' fermato`() {
        // Serve alla schermata per puntare il dito nel punto giusto invece che sull'intera riga.
        listOf(
            "username = m.rossi",
            "esito == fallito",
            "esito = fallito origine = esterna",
        ).forEach { testo ->
            assertTrue("«$testo» non dice su quale parola", ko(testo).parola != null)
        }
    }
}
