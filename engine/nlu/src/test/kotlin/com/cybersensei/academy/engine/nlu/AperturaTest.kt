package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le domande che si aprono mentre studi.
 *
 * Il guadagno è che un principiante non si trova davanti duecentocinquanta domande di cui
 * duecento non lo riguardano. Il rischio è di gran lunga peggiore del guadagno, e va difeso
 * qui: una regola sbagliata nasconde una risposta *per sempre*, senza che nessuno se ne
 * accorga, perché una cosa che non compare non si può segnalare.
 *
 * Tre garanzie, e sono tutte e tre necessarie. Che chi ha appena installato l'app trovi
 * comunque tutto ciò che serve adesso. Che l'elenco cresca e basta, senza mai stringersi.
 * E che chi arriva in fondo al programma abbia davanti tutto quanto.
 */
class AperturaTest {

    private val knowledgeBase = KnowledgeBase.fromResources()
    private val percorsi = StudyPaths.fromResources()

    /**
     * Il programma finto, ma con la stessa forma di quello vero: ogni voce dichiara una
     * competenza, e questo test la aggancia a un modulo con una lezione dentro. Il programma
     * vero e' verificato altrove; qui si misura la *regola*.
     */
    private val competenze: List<String> =
        knowledgeBase.entries.mapNotNull { it.skillId }.distinct().sorted()

    private val moduloDi: Map<String, String> =
        competenze.associateWith { "mod_$it" }

    private val lezioniDi: Map<String, Set<String>> =
        moduloDi.values.associateWith { setOf("$it/lezione") }

    private val apertura = StudyAvailability(moduloDi, lezioniDi)

    /** Ogni voce con il ramo in cui sta, perché è il ramo a dichiararsi aperto. */
    private val collocazioni: List<Pair<FaqEntry, Boolean>> = percorsi.all.flatMap { ramo ->
        val aperto = percorsi.trail(ramo.id).any { it.alwaysOpen }
        ramo.items.mapNotNull { voce ->
            knowledgeBase.entries.firstOrNull { it.id == voce.faq }?.let { it to aperto }
        }
    }

    private fun disponibili(lezioniFatte: Set<String>): Set<String> {
        val iniziati = apertura.startedModules(lezioniFatte)
        return collocazioni
            .filter { (voce, aperto) -> apertura.isOpen(voce, aperto, iniziati) }
            .map { it.first.id }
            .toSet()
    }

    /**
     * La garanzia che conta più di tutte.
     *
     * Chi ha appena cliccato su un link non ha studiato niente, e non deve studiare niente per
     * essere aiutato. Se questo test fallisce, l'app ha smesso di servire proprio nel momento
     * per cui esiste.
     */
    @Test
    fun `chi non ha ancora studiato niente trova comunque le emergenze`() {
        val subito = disponibili(emptySet())
        val emergenze = percorsi.branch("successo")!!

        val chiuse = domandeSotto(emergenze).filterNot { it in subito }
        assertEquals("Emergenze non disponibili al primo giorno: $chiuse", emptyList<String>(), chiuse)
    }

    /** E anche tutto il resto che non ha senso far aspettare: truffe, miti, la scuola. */
    @Test
    fun `le stanze dichiarate aperte lo sono davvero dal primo minuto`() {
        val subito = disponibili(emptySet())

        percorsi.branches.filter { it.alwaysOpen }.forEach { ramo ->
            val chiuse = domandeSotto(ramo).filterNot { it in subito }
            assertEquals(
                "«${ramo.title}» è dichiarata aperta ma nasconde: $chiuse",
                emptyList<String>(),
                chiuse,
            )
        }
    }

    /**
     * L'elenco cresce e basta.
     *
     * Studiare non può mai far sparire una domanda che c'era: sarebbe il difetto più difficile
     * da scoprire dell'intera applicazione, perché lo studente darebbe la colpa alla propria
     * memoria. Qui si simula il percorso modulo per modulo e si controlla ogni passo.
     */
    @Test
    fun `studiare non fa mai sparire una domanda`() {
        var fatte = emptySet<String>()
        var prima = disponibili(fatte)

        lezioniDi.forEach { (modulo, lezioni) ->
            fatte = fatte + lezioni
            val dopo = disponibili(fatte)
            val sparite = prima - dopo
            assertTrue(
                "Dopo aver studiato $modulo sono sparite: $sparite",
                sparite.isEmpty(),
            )
            prima = dopo
        }
    }

    /** E alla fine del percorso non deve restare niente di chiuso. */
    @Test
    fun `chi finisce il programma ha davanti tutto`() {
        val tutte = disponibili(lezioniDi.values.flatten().toSet())
        val mancanti = knowledgeBase.entries.map { it.id }.filterNot { it in tutte }

        assertEquals("Voci mai raggiungibili: $mancanti", emptyList<String>(), mancanti)
    }

    /**
     * Nessuna stanza principale deve presentarsi vuota a chi comincia.
     *
     * Una schermata con zero domande e una riga di spiegazione è indistinguibile da un errore,
     * e la prima impressione di un'app si forma lì.
     */
    @Test
    fun `nessuna stanza principale e' vuota per chi comincia`() {
        val subito = disponibili(emptySet())

        percorsi.branches.forEach { ramo ->
            val aperte = domandeSotto(ramo).count { it in subito }
            assertTrue("«${ramo.title}» si presenta vuota a chi comincia", aperte > 0)
        }
    }

    /**
     * Una stanza sempre aperta non puo' contenere materia avanzata.
     *
     * E' la contraddizione che uno studente vede subito: il professore dice «le altre si
     * aprono studiando» e intanto una domanda con l'etichetta «Difficile» e' li' pronta. Le
     * stanze aperte esistono per il primo soccorso, e il primo soccorso e' materia da primo
     * giorno per definizione — se una voce li' dentro e' di livello due o tre, o e' nel posto
     * sbagliato o e' agganciata alla competenza sbagliata.
     */
    @Test
    fun `nelle stanze sempre aperte non c'e' materia avanzata`() {
        val fuoriposto = percorsi.all.flatMap { ramo ->
            val aperto = percorsi.trail(ramo.id).any { it.alwaysOpen }
            if (!aperto) emptyList() else ramo.items.mapNotNull { voce ->
                knowledgeBase.entries.firstOrNull { it.id == voce.faq }
                    ?.takeIf { it.level >= 2 }
                    ?.let { "${ramo.id} -> ${it.id} (livello ${it.level})" }
            }
        }

        assertEquals(emptyList<String>(), fuoriposto)
    }

    /** Il conto vero, per sapere di cosa stiamo parlando quando diciamo «cresce». */
    @Test
    fun `l'apertura e' graduale, non tutto o niente`() {
        val subito = disponibili(emptySet()).size
        val tutte = knowledgeBase.entries.size

        assertTrue("Al primo giorno sono aperte $subito domande su $tutte", subito in 60..160)
        assertTrue("Non resta niente da sbloccare: $subito su $tutte", tutte - subito >= 60)
    }

    private fun domandeSotto(ramo: Branch): List<String> =
        ramo.items.map { it.faq } + ramo.branches.flatMap { domandeSotto(it) }
}
