package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le domande si aprono facendo le interrogazioni.
 *
 * La regola e' una frase: una domanda sulla materia si apre quando lo studente ha *sostenuto*
 * l'interrogazione che la riguarda, superata o no. Il «o no» e' la parte importante — chi
 * sbaglia un test ha appena dimostrato di non aver capito qualcosa, ed e' esattamente quello
 * il momento in cui gli serve chiedere al professore.
 *
 * Il rischio da difendere qui e' sempre lo stesso: una regola sbagliata nasconde una risposta
 * per sempre, e una cosa che non compare non si puo' segnalare.
 */
class AperturaTest {

    private val knowledgeBase = KnowledgeBase.fromResources()
    private val percorsi = StudyPaths.fromResources()
    private val apertura = StudyAvailability()

    private val tutteLeCompetenze: Set<String> =
        knowledgeBase.entries.mapNotNull { it.skillId }.toSet()

    private fun disponibili(interrogate: Set<String>): Set<String> = knowledgeBase.entries
        .filter { apertura.isOpen(it, interrogate) }
        .map { it.id }
        .toSet()

    /**
     * Prima di aver studiato niente restano le domande sulla scuola: cos'e', come funziona,
     * cosa succede ai tuoi dati, cosa sa di te. Non sono materia, sono le domande che uno fa
     * *prima* di decidere se studiare, e chiuderle sarebbe una porta sbarrata all'ingresso.
     */
    @Test
    fun `chi non ha ancora fatto niente puo' chiedere solo della scuola`() {
        val subito = disponibili(emptySet())
        val voci = knowledgeBase.entries.filter { it.id in subito }

        assertTrue("Nessuna domanda disponibile all'inizio", voci.isNotEmpty())
        assertEquals(
            "All'inizio non deve essere aperta nessuna domanda di materia",
            emptyList<String>(),
            voci.filter { it.skillId != null }.map { it.id },
        )
        assertTrue(
            "Devono restare le domande sulla scuola e sull'archivio su di te: ${voci.size}",
            voci.size >= 30,
        )
    }

    /**
     * La regola che Francesco ha scelto, e la sua ragione: chi non supera il test e' proprio
     * quello che ha bisogno di quelle risposte per superarlo la volta dopo.
     */
    @Test
    fun `fare l'interrogazione apre le domande, anche se e' andata male`() {
        val competenza = tutteLeCompetenze.first()
        val prima = disponibili(emptySet())
        val dopo = disponibili(setOf(competenza))

        val aperte = dopo - prima
        assertTrue("Sostenere un'interrogazione non ha aperto niente", aperte.isNotEmpty())
        assertTrue(
            "Ha aperto domande di altre competenze: $aperte",
            aperte.all { id -> knowledgeBase.entries.first { it.id == id }.skillId == competenza },
        )
    }

    /** E apre soltanto quello: mai le domande dei test che devono ancora venire. */
    @Test
    fun `una sola interrogazione non apre la materia che verra' dopo`() {
        val competenza = tutteLeCompetenze.first()
        val dopo = disponibili(setOf(competenza))
        val altrui = dopo
            .mapNotNull { id -> knowledgeBase.entries.first { it.id == id }.skillId }
            .filterNot { it == competenza }

        assertEquals("Aperte competenze mai interrogate: $altrui", emptyList<String>(), altrui)
    }

    /**
     * L'elenco cresce e basta. Studiare non puo' mai far sparire una domanda che c'era: e'
     * il difetto piu' difficile da scoprire dell'app, perche' lo studente darebbe la colpa
     * alla propria memoria.
     */
    @Test
    fun `sostenere altre interrogazioni non fa mai sparire una domanda`() {
        var interrogate = emptySet<String>()
        var prima = disponibili(interrogate)

        tutteLeCompetenze.sorted().forEach { competenza ->
            interrogate = interrogate + competenza
            val dopo = disponibili(interrogate)
            assertTrue("Dopo $competenza sono sparite: ${prima - dopo}", (prima - dopo).isEmpty())
            prima = dopo
        }
    }

    /** E alla fine del programma non deve restare chiusa nemmeno una risposta. */
    @Test
    fun `chi ha fatto tutte le interrogazioni ha davanti tutto`() {
        val tutte = disponibili(tutteLeCompetenze)
        val mancanti = knowledgeBase.entries.map { it.id }.filterNot { it in tutte }

        assertEquals("Voci mai raggiungibili: $mancanti", emptyList<String>(), mancanti)
    }

    /** Ogni risposta della scuola resta raggiungibile toccando, da qualche parte nell'albero. */
    @Test
    fun `ogni risposta della scuola si raggiunge toccando`() {
        assertEquals(emptyList<String>(), percorsi.validate(knowledgeBase))
    }

    /**
     * Quanto e' chiuso all'inizio, detto in numeri.
     *
     * Serve a sapere di cosa parliamo: con questa regola lo Studio si apre quasi vuoto e si
     * riempie studiando, ed e' una scelta presa sapendo che il primo soccorso aspetta il suo
     * turno come tutto il resto.
     */
    @Test
    fun `l'apertura e' graduale e parte da poco`() {
        val subito = disponibili(emptySet()).size
        val tutte = knowledgeBase.entries.size

        assertTrue("All'inizio sono aperte $subito domande su $tutte", subito in 20..60)
        assertTrue("Non resta abbastanza da aprire: $subito su $tutte", tutte - subito >= 150)
    }
}
