package com.cybersensei.academy.content

import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.scenario.ChoiceQuality
import com.cybersensei.academy.engine.scenario.RunState
import com.cybersensei.academy.engine.scenario.ScenarioEngine
import com.cybersensei.academy.engine.scenario.ScenarioLibrary
import com.cybersensei.academy.engine.scenario.score
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

    // --- il cento per cento --------------------------------------------------------------

    /**
     * La promessa: **giochi tutto giusto, prendi cento per cento.** Qualunque delle strade
     * giuste tu abbia preso.
     *
     * Prima non era cosi', e il modo in cui si rompeva e' istruttivo. Il massimo sommava la
     * scelta migliore di *ogni* scena, comprese quelle in cui si finisce solo sbagliando: chi
     * giocava pulito si vedeva dire «Decisione giusta» quattro volte su quattro e poi 86%,
     * perche' nel denominatore c'erano i punti della scena che si era guadagnato il diritto di
     * non vedere. Una schermata che si contraddice da sola non sembra sottile, sembra
     * arbitraria — e da li' in poi il punteggio non significa piu' niente.
     */
    @Test
    fun `una partita tutta giusta vale cento per cento, su tutti gli assi`() {
        casi.cases.forEach { caso ->
            val engine = ScenarioEngine(caso)
            var run = engine.start()
            var guardia = 0
            while (!run.finished) {
                check(guardia++ < MAX_SCENE) { "[${caso.id}] la partita giusta non finisce" }
                val scena = checkNotNull(engine.currentScene(run))
                val giusta = checkNotNull(scena.choices.firstOrNull { it.quality == ChoiceQuality.RIGHT }) {
                    "[${caso.id}] la scena '${scena.id}' non ha una scelta giusta"
                }
                run = engine.choose(run, giusta.id)
            }
            val verdetto = engine.debrief(run)

            assertEquals("[${caso.id}] punteggio complessivo", 100, verdetto.scorePercent)
            assertEquals("[${caso.id}] contenimento", 100, verdetto.containmentPercent)
            assertEquals("[${caso.id}] prove conservate", 100, verdetto.evidencePercent)
            assertEquals("[${caso.id}] fiducia mantenuta", 100, verdetto.trustPercent)
        }
    }

    /**
     * E vale per *qualunque* strada giusta: dentro una scena le scelte giuste devono pesare
     * uguale su tutti e tre gli assi.
     *
     * Se non pesano uguale, il professore dichiara due risposte entrambe giuste e poi ne fa
     * pagare una in silenzio — che e' peggio di dire che una e' migliore dell'altra.
     */
    @Test
    fun `due scelte giuste nella stessa scena valgono uguale`() {
        val diverse = casi.cases.flatMap { caso ->
            caso.scenes.mapNotNull { scena ->
                val giuste = scena.choices.filter { it.quality == ChoiceQuality.RIGHT }
                val pesi = giuste.map { Triple(it.effects.containment, it.effects.evidence, it.effects.trust) }
                if (pesi.distinct().size > 1) {
                    "[${caso.id}] '${scena.id}': ${giuste.map { it.id }} pesano diverso — $pesi"
                } else {
                    null
                }
            }
        }

        assertEquals(diverse.joinToString("\n"), emptyList<String>(), diverse)
    }

    /**
     * E nessuna strada puo' battere quella giusta, su nessun asse.
     *
     * E' l'altra meta' della promessa, ed e' quella che non si vede: senza, una mossa
     * sbagliata potrebbe far segnare piu' di cento su una barra, e soprattutto sbagliare
     * potrebbe convenire. Qui si controllano tutti i percorsi possibili, uno per uno.
     */
    @Test
    fun `nessun percorso batte quello giusto`() {
        casi.cases.forEach { caso ->
            val riferimento = caso.soundRun
            val engine = ScenarioEngine(caso)

            fun esplora(run: RunState, profondita: Int) {
                if (run.finished) {
                    assertTrue(
                        "[${caso.id}] un percorso segna ${run.score} contro i ${riferimento.score} " +
                            "di quello giusto: ${run.decisions.map { it.choiceId }}",
                        run.score <= riferimento.score,
                    )
                    assertTrue(
                        "[${caso.id}] un percorso batte il contenimento di riferimento",
                        run.containment <= riferimento.containment,
                    )
                    assertTrue(
                        "[${caso.id}] un percorso batte le prove di riferimento",
                        run.evidence <= riferimento.evidence,
                    )
                    assertTrue(
                        "[${caso.id}] un percorso batte la fiducia di riferimento",
                        run.trust <= riferimento.trust,
                    )
                    return
                }
                check(profondita < MAX_SCENE) { "[${caso.id}] percorso senza fine" }
                engine.currentScene(run)?.choices?.forEach { scelta ->
                    esplora(engine.choose(run, scelta.id), profondita + 1)
                }
            }

            esplora(engine.start(), 0)
        }
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

    private companion object {
        /** Un percorso piu' lungo di cosi' e' un anello, non una storia. */
        const val MAX_SCENE = 40
    }
}
