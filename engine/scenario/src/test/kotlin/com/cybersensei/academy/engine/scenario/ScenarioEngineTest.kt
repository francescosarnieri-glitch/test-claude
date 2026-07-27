package com.cybersensei.academy.engine.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioEngineTest {

    private val scenario = Scenario.fromResources()
    private val engine = ScenarioEngine(scenario)

    /** Plays the run choosing, at each scene, the choice matching [pick]. */
    private fun play(pick: (Scene) -> Choice): RunState {
        var state = engine.start()
        var guard = 0
        while (!state.finished) {
            check(guard++ < MAX_SCENES) { "Lo scenario non termina: forse c'è un ciclo" }
            val scene = engine.currentScene(state) ?: break
            state = engine.choose(state, pick(scene).id)
        }
        return state
    }

    private fun bestRun() = play { scene ->
        scene.choices.maxByOrNull { it.effects.score }!!
    }

    private fun worstRun() = play { scene ->
        scene.choices.minByOrNull { it.effects.score }!!
    }

    // --- The scenario itself ------------------------------------------------------------

    @Test
    fun `the shipped scenario is playable`() {
        val problems = scenario.validate()
        assertTrue("Problemi nello scenario:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * The requirement the whole app is built on, applied to the capstone: there is no
     * decision a student can take that leaves them without a reason.
     */
    @Test
    fun `every choice explains itself`() {
        val silent = scenario.scenes.flatMap { it.choices }
            .filter { it.explanation.length < 60 || it.consequence.isBlank() }
            .map { it.id }
        assertTrue("Scelte senza una spiegazione degna: $silent", silent.isEmpty())
    }

    @Test
    fun `every choice is anchored to skills the student has studied`() {
        val unanchored = scenario.scenes.flatMap { it.choices }
            .filter { it.skills.isEmpty() }
            .map { it.id }
        assertTrue("Scelte che non misurano nulla: $unanchored", unanchored.isEmpty())
    }

    @Test
    fun `no scene is a trap and none is a formality`() {
        scenario.scenes.forEach { scene ->
            assertTrue(
                "La scena '${scene.id}' offre una sola strada",
                scene.choices.size >= 2,
            )
            assertTrue(
                "La scena '${scene.id}' non ha una risposta giusta",
                scene.choices.any { it.quality == ChoiceQuality.RIGHT },
            )
        }
    }

    /** A branching scenario that always converges immediately is not branching. */
    @Test
    fun `the story really branches`() {
        val branching = scenario.scenes.count { scene ->
            scene.choices.mapNotNull { it.next }.distinct().size > 1
        }
        assertTrue("Nessuna scelta cambia il seguito della storia", branching >= 2)
    }

    // --- Running it ---------------------------------------------------------------------

    @Test
    fun `a run starts at the declared scene and ends`() {
        val start = engine.start()
        assertEquals(scenario.startSceneId, start.currentSceneId)
        assertNotNull(engine.currentScene(start))

        val finished = bestRun()
        assertTrue(finished.finished)
        assertNull(finished.currentSceneId)
        assertTrue("Nessuna decisione registrata", finished.decisions.isNotEmpty())
    }

    @Test
    fun `the same decisions always produce the same night`() {
        val first = bestRun()
        val second = bestRun()
        assertEquals(first.decisions.map { it.choiceId }, second.decisions.map { it.choiceId })
        assertEquals(first.score, second.score)
    }

    @Test
    fun `an unknown choice cannot end someone's run`() {
        val start = engine.start()
        val after = engine.choose(start, "scelta_che_non_esiste")
        assertEquals(start, after)
    }

    @Test
    fun `every decision taken is recorded with its reason`() {
        val state = bestRun()
        assertTrue(state.decisions.all { it.explanation.isNotBlank() })
        assertTrue(state.decisions.all { it.consequence.isNotBlank() })
        assertTrue(state.decisions.all { it.sceneTitle.isNotBlank() })
    }

    // --- The judgement ------------------------------------------------------------------

    @Test
    fun `playing well and playing badly are told apart`() {
        val best = engine.debrief(bestRun())
        val worst = engine.debrief(worstRun())

        assertTrue(
            "Il punteggio migliore (${best.scorePercent}) non supera il peggiore (${worst.scorePercent})",
            best.scorePercent > worst.scorePercent,
        )
        assertTrue("La partita giocata bene deve superare l'80%", best.scorePercent >= 80)
        assertTrue("La partita giocata male non può passare", worst.scorePercent < 40)
    }

    @Test
    fun `a bad run still gets a verdict, never a blank`() {
        val verdict = engine.debrief(worstRun())
        assertTrue(verdict.title.isNotBlank())
        assertTrue(verdict.body.isNotBlank())
        assertTrue(verdict.closing.isNotBlank())
        assertTrue("Chi sbaglia deve ricevere delle osservazioni", verdict.notes.isNotEmpty())
    }

    @Test
    fun `the three axes are reported separately`() {
        val verdict = engine.debrief(bestRun())
        listOf(verdict.containmentPercent, verdict.evidencePercent, verdict.trustPercent)
            .forEach { assertTrue("Asse fuori scala: $it", it in 0..100) }
    }

    /**
     * The pairing that makes the exercise honest: containing perfectly while destroying the
     * record must not read as a good night.
     */
    @Test
    fun `containment without evidence is not a good outcome`() {
        var state = engine.start()
        // Shut the server down: contains, and takes the volatile memory with it.
        state = engine.choose(state, "c_spegni")
        val decision = state.decisions.first()

        assertTrue("Il contenimento c'è stato", decision.quality != ChoiceQuality.HARMFUL)
        assertTrue("Le prove sono peggiorate", state.evidence < 0)
        assertTrue(state.flags.contains("memoria_persa"))
    }

    @Test
    fun `the notes come from what the student actually did`() {
        var state = engine.start()
        state = engine.choose(state, "c_password")
        val verdict = engine.debrief(state)

        assertTrue(
            "Chi torna a dormire deve sentirselo dire",
            verdict.notes.any { it.contains("dormire") },
        )
    }

    @Test
    fun `the debriefing walks back through every decision`() {
        val state = bestRun()
        val verdict = engine.debrief(state)
        assertEquals(state.decisions.size, verdict.decisions.size)
        assertEquals(state.decisions.size, verdict.soundDecisions)
    }

    @Test
    fun `a wrong turn early changes the night that follows`() {
        val sleeping = engine.choose(engine.start(), "c_password")
        val isolating = engine.choose(engine.start(), "c_isola")
        assertFalse(
            "Tornare a dormire deve portare altrove",
            sleeping.currentSceneId == isolating.currentSceneId,
        )
    }

    private companion object {
        const val MAX_SCENES = 100
    }
}
