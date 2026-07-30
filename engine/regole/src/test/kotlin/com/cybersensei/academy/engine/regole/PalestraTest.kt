package com.cybersensei.academy.engine.regole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped exercises, against the engine that will judge them.
 *
 * The one that matters most is [ogni_esercizio_e_risolvibile]: for every exercise the answer key
 * in the content is run through the real engine and has to come out perfect. Without it an
 * exercise can ship whose stated solution scores as wrong — and the student, who is right,
 * would be told they are not. That is the one bug this whole feature cannot survive.
 */
class PalestraTest {

    private val palestra = Palestra.fromResources()

    @Test
    fun `il contenuto e' valido`() {
        val problemi = palestra.validate()
        assertTrue(problemi.toString(), problemi.isEmpty())
    }

    @Test
    fun `ogni esercizio e' risolvibile`() {
        palestra.esercizi.forEach { esercizio ->
            val registro = palestra.registroDi(esercizio)!!
            val lettura = Analizzatore(registro.campi).leggi(esercizio.regolaModello)
            assertTrue("«${esercizio.regolaModello}» non si legge", lettura is Lettura.Riuscita)

            val esito = MotoreRegole.esegui(
                (lettura as Lettura.Riuscita).regola,
                registro,
                esercizio.bersagli.toSet(),
            )
            assertTrue(
                "L'esercizio '${esercizio.id}' non è risolto dalla sua regola modello: " +
                    "${esito.presi.size} presi, ${esito.persi.size} persi, ${esito.falsi.size} falsi",
                esito.perfetto,
            )
        }
    }

    @Test
    fun `nessun esercizio si risolve senza aver capito niente`() {
        // Una regola che prende tutto non deve mai bastare: se bastasse, l'esercizio
        // premierebbe chi non ha guardato il registro.
        palestra.esercizi.forEach { esercizio ->
            val registro = palestra.registroDi(esercizio)!!
            val tutto = MotoreRegole.esegui(
                Regola(listOf(Condizione("tipo", Operatore.DIVERSO, "###")), emptyList()),
                registro,
                esercizio.bersagli.toSet(),
            )
            assertFalse(
                "In '${esercizio.id}' basterebbe prendere tutto",
                tutto.perfetto,
            )
        }
    }

    @Test
    fun `gli esercizi crescono uno sull'altro`() {
        val ids = palestra.esercizi.map { it.id }
        assertEquals(
            listOf(
                "reg_primo_filtro",
                "reg_due_condizioni",
                "reg_soglia",
                "reg_comportamento",
                "reg_raggruppa",
            ),
            ids,
        )
    }

    /**
     * La lezione del terzo esercizio, verificata invece che soltanto raccontata.
     *
     * Il briefing dice allo studente che la regola del secondo esercizio, sul registro del
     * terzo, produce falsi allarmi. Se un domani cambio i log e non è più vero, quel testo
     * diventa una bugia — e questo test lo impedisce.
     */
    @Test
    fun `la regola di ieri sul registro nuovo fa falsi allarmi`() {
        val ieri = palestra.esercizio("reg_due_condizioni")!!
        val oggi = palestra.esercizio("reg_soglia")!!
        val registro = palestra.registroDi(oggi)!!

        val lettura = Analizzatore(registro.campi).leggi(ieri.regolaModello) as Lettura.Riuscita
        val esito = MotoreRegole.esegui(lettura.regola, registro, oggi.bersagli.toSet())

        assertTrue("Deve ancora prendere l'attacco", esito.persi.isEmpty())
        assertEquals("Cinque falsi allarmi, come dice il briefing", 5, esito.falsi.size)
    }

    /**
     * La lezione dell'ultimo esercizio, che è la più forte di tutte.
     *
     * Il briefing promette che raggruppando per indirizzo la regola prende **zero** attacchi su
     * diciotto. È l'affermazione più precisa di tutta la palestra: va verificata.
     */
    @Test
    fun `raggruppare per indirizzo rende ciechi sull'ultimo attacco`() {
        val esercizio = palestra.esercizio("reg_raggruppa")!!
        val registro = palestra.registroDi(esercizio)!!

        val perIndirizzo = Analizzatore(registro.campi)
            .leggi("esito = fallito e conta > 4 in 5 minuti per indirizzo") as Lettura.Riuscita
        val esito = MotoreRegole.esegui(perIndirizzo.regola, registro, esercizio.bersagli.toSet())

        assertEquals("Zero attacchi presi", 0, esito.presi.size)
        assertEquals("Diciotto persi", 18, esito.persi.size)
    }

    @Test
    fun `ogni esercizio ha tutte e quattro le risposte del professore`() {
        // Regola 4 del progetto: ogni risposta dello studente riceve una spiegazione, giusta o
        // sbagliata che sia. Qui gli esiti possibili sono quattro, e servono tutti.
        palestra.esercizi.forEach {
            assertTrue("'${it.id}' se perfetta", it.sePerfetta.corpo.length > 40)
            assertTrue("'${it.id}' se rumorosa", it.seRumorosa.corpo.length > 40)
            assertTrue("'${it.id}' se incompleta", it.seIncompleta.corpo.length > 40)
            assertTrue("'${it.id}' se muta", it.seMuta.corpo.length > 40)
        }
    }

    @Test
    fun `il giudizio scelto e' quello che descrive la corsa`() {
        val esercizio = palestra.esercizi.first()
        val registro = palestra.registroDi(esercizio)!!
        val bersagli = esercizio.bersagli.toSet()
        fun esegui(testo: String) = MotoreRegole.esegui(
            (Analizzatore(registro.campi).leggi(testo) as Lettura.Riuscita).regola,
            registro,
            bersagli,
        )

        assertEquals(esercizio.sePerfetta, esercizio.giudizio(esegui(esercizio.regolaModello)))
        assertEquals(esercizio.seMuta, esercizio.giudizio(esegui("esito = inesistente")))
        // Prende tutto: nessun bersaglio perso, ma molto rumore.
        assertEquals(esercizio.seRumorosa, esercizio.giudizio(esegui("tipo != ###")))
        // Prende solo una fetta dell'attacco.
        assertEquals(esercizio.seIncompleta, esercizio.giudizio(esegui("esito = fallito e origine = esterna")))
    }

    @Test
    fun `ogni esercizio insegna qualcosa di dichiarato`() {
        palestra.esercizi.forEach {
            assertTrue("'${it.id}' non dichiara nessuna competenza", it.skills.isNotEmpty())
        }
    }

    @Test
    fun `i registri hanno abbastanza rumore da rendere l'esercizio vero`() {
        // Un registro in cui l'attacco è la maggioranza delle righe non allena niente.
        palestra.esercizi.forEach { esercizio ->
            val registro = palestra.registroDi(esercizio)!!
            val quota = esercizio.bersagli.size * 100 / registro.eventi.size
            assertTrue(
                "In '${esercizio.id}' i bersagli sono il $quota% del registro: troppo facile",
                quota <= 70,
            )
        }
    }
}
