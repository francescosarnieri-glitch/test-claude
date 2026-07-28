package com.cybersensei.academy.content

import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.scenario.ChoiceQuality
import com.cybersensei.academy.engine.scenario.ScenarioLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I casi: piccole storie a bivi, sparse lungo il programma.
 *
 * Il capstone unico aveva un difetto che nessuna riscrittura poteva togliere — si giocava una
 * volta sola e solo alla fine. Uno studente passava mesi sul programma e *decideva* qualcosa
 * una volta. I casi lo spargono: ognuno costruito solo su materia gia' spiegata, ognuno che si
 * apre quando i moduli da cui pesca sono stati letti.
 *
 * Qui si difende la promessa che rende possibile spargerli: **un caso non chiede mai niente
 * che lo studente non abbia gia' studiato.** Se salta, il gioco diventa indovinare, ed e' il
 * difetto peggiore perche' non si vede — lo studente crede di essere lui a non capire.
 */
class CasiTest {

    private val casi = ScenarioLibrary.fromResources()
    private val curriculum = Curriculum.fromResources()

    /** Le competenze insegnate fino al livello indicato, quello compreso. */
    private fun competenzeFinoA(livello: Int): Set<String> = curriculum.levels
        .filter { it.level <= livello }
        .flatMap { level -> level.modules.flatMap { it.skills } }
        .toSet()

    @Test
    fun `i casi sono tutti giocabili`() {
        assertEquals("Problemi nei casi:\n${casi.validate().joinToString("\n")}", emptyList<String>(), casi.validate())
    }

    /**
     * La regola che permette di spargerli. Un caso di livello 1 puo' usare l'introduzione e il
     * livello 1: cumulativa e non esclusiva, perche' l'etica serve dappertutto e far tornare la
     * materia vecchia dentro una storia nuova e' il ripasso travestito da gioco.
     */
    @Test
    fun `nessun caso chiede una competenza non ancora insegnata`() {
        val fuori = casi.cases.flatMap { caso ->
            val consentite = competenzeFinoA(caso.level)
            caso.scenes
                .flatMap { it.choices }
                .flatMap { scelta -> scelta.skills.map { scelta.id to it } }
                .filterNot { (_, skill) -> skill in consentite }
                .map { (scelta, skill) -> "[${caso.id}] la scelta '$scelta' usa '$skill', non ancora insegnata" }
        }

        assertEquals(fuori.joinToString("\n"), emptyList<String>(), fuori)
    }

    /** E i moduli che aprono un caso devono esistere, e stare nel suo livello o prima. */
    @Test
    fun `i moduli che aprono un caso esistono e vengono prima`() {
        val problemi = casi.cases.flatMap { caso ->
            caso.opensWith.mapNotNull { moduleId ->
                val livello = curriculum.levels.firstOrNull { level ->
                    level.modules.any { it.id == moduleId }
                }
                when {
                    livello == null -> "[${caso.id}] apre con il modulo inesistente '$moduleId'"
                    livello.level > caso.level ->
                        "[${caso.id}] aspetta '$moduleId', che sta al livello ${livello.level}"
                    else -> null
                }
            }
        }

        assertEquals(problemi.joinToString("\n"), emptyList<String>(), problemi)
    }

    /**
     * Il caso finale e' uno solo, ed e' quello che il diploma chiede.
     *
     * La trappola che questo test esiste per prendere: l'archivio conosce il caso finale per
     * id, scritto a mano perche' il modulo dei dati non deve leggere gli scenari. Se un giorno
     * il contenuto rinomina il finale, senza questo test il diploma smetterebbe in silenzio di
     * chiedere qualcosa — e nessuno se ne accorgerebbe se non ricevendo un attestato gratis.
     */
    @Test
    fun `il caso finale e' quello che il diploma chiede`() {
        val finale = casi.finale
        assertTrue("Nessun caso e' dichiarato finale", finale != null)
        assertEquals(
            "L'archivio chiede un caso finale che il contenuto non ha",
            SchoolRepository.FINAL_CASE_ID,
            finale!!.id,
        )
    }

    /**
     * Ogni caso deve avere una via d'uscita buona e una cattiva, altrimenti non e' una prova:
     * un caso dove si puo' solo andare bene non misura niente, e uno dove si puo' solo andare
     * male e' una trappola travestita da esercizio.
     */
    @Test
    fun `in ogni caso si puo' finire bene e si puo' finire male`() {
        casi.cases.forEach { caso ->
            val scelte = caso.scenes.flatMap { it.choices }
            assertTrue(
                "[${caso.id}] nessuna scelta davvero giusta",
                scelte.any { it.quality == ChoiceQuality.RIGHT },
            )
            assertTrue(
                "[${caso.id}] nessuna scelta davvero sbagliata: non e' una prova",
                scelte.any { it.quality == ChoiceQuality.WRONG || it.quality == ChoiceQuality.HARMFUL },
            )
        }
    }

    /**
     * Il requisito centrale dell'app, applicato qui: ogni decisione riceve la sua spiegazione,
     * anche quella presa bene. La validazione dello scenario lo controlla gia' — questo test
     * lo dice a voce alta, perche' e' la promessa che non va persa in una riscrittura.
     */
    @Test
    fun `ogni decisione ha la sua spiegazione, giusta o sbagliata che sia`() {
        val mute = casi.cases.flatMap { caso ->
            caso.scenes.flatMap { it.choices }
                .filter { it.explanation.isBlank() || it.consequence.isBlank() }
                .map { "[${caso.id}] ${it.id}" }
        }

        assertEquals("Decisioni senza spiegazione: $mute", emptyList<String>(), mute)
    }

    /**
     * Quanto e' lungo un caso. Sotto le quattro scene non c'e' spazio perche' una decisione
     * presa all'inizio torni addosso alla fine, che e' l'unica cosa che questi esercizi sanno
     * fare e un'interrogazione no.
     */
    @Test
    fun `un caso ha abbastanza scene da poter tornare indietro a morderti`() {
        casi.cases.forEach { caso ->
            assertTrue(
                "[${caso.id}] ha solo ${caso.scenes.size} scene: e' una domanda, non un caso",
                caso.scenes.size >= 4,
            )
            assertTrue("[${caso.id}] non dichiara una durata", caso.minutes > 0)
        }
    }
}
