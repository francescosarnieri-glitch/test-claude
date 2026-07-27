package com.cybersensei.academy.engine.scenario

/** One decision, kept so the debriefing can walk back through the whole night. */
data class DecisionRecord(
    val sceneId: String,
    val sceneTitle: String,
    val question: String,
    val choiceId: String,
    val choiceText: String,
    val quality: ChoiceQuality,
    val consequence: String,
    val explanation: String,
    val skills: List<String>,
)

/** Where the run stands: current scene, tallies, and everything decided so far. */
data class RunState(
    val currentSceneId: String?,
    val decisions: List<DecisionRecord> = emptyList(),
    val containment: Int = 0,
    val evidence: Int = 0,
    val trust: Int = 0,
    val hours: Int = 0,
    val flags: Set<String> = emptySet(),
) {
    val finished: Boolean get() = currentSceneId == null
    val score: Int get() = containment + evidence + trust
}

/** The professor's judgement at the end, and the reasons behind it. */
data class Verdict(
    val opening: String,
    val title: String,
    val body: String,
    val closing: String,
    val scorePercent: Int,
    val containmentPercent: Int,
    val evidencePercent: Int,
    val trustPercent: Int,
    val hours: Int,
    /** Remarks earned by specific things the student did. */
    val notes: List<String>,
    val decisions: List<DecisionRecord>,
) {
    val soundDecisions: Int get() = decisions.count { it.quality.isSound }
}

/**
 * Runs a branching scenario.
 *
 * Deliberately a plain state machine with no randomness: two students who make the same
 * decisions must get the same night, otherwise the debriefing cannot be discussed and the
 * exercise stops being a shared reference.
 */
class ScenarioEngine(private val scenario: Scenario) {

    fun start(): RunState = RunState(currentSceneId = scenario.startSceneId)

    fun currentScene(state: RunState): Scene? = state.currentSceneId?.let(scenario::scene)

    /**
     * Applies a decision. Unknown choices leave the state untouched rather than throwing:
     * a stale tap from a recomposition must not be able to end someone's run.
     */
    fun choose(state: RunState, choiceId: String): RunState {
        val scene = currentScene(state) ?: return state
        val choice = scene.choices.firstOrNull { it.id == choiceId } ?: return state

        return state.copy(
            currentSceneId = choice.next,
            decisions = state.decisions + DecisionRecord(
                sceneId = scene.id,
                sceneTitle = scene.title,
                question = scene.question,
                choiceId = choice.id,
                choiceText = choice.text,
                quality = choice.quality,
                consequence = choice.consequence,
                explanation = choice.explanation,
                skills = choice.skills,
            ),
            containment = state.containment + choice.effects.containment,
            evidence = state.evidence + choice.effects.evidence,
            trust = state.trust + choice.effects.trust,
            hours = state.hours + choice.effects.hours,
            flags = state.flags + choice.effects.flags,
        )
    }

    /**
     * The debriefing.
     *
     * The band is chosen on the total, but the notes come from what the student actually did
     * — which is the part that makes it feel like a person reviewed the night rather than a
     * scoreboard printing a grade.
     */
    fun debrief(state: RunState): Verdict {
        val maximum = scenario.maximumScore.coerceAtLeast(1)
        val percent = ((state.score.toDouble() / maximum) * 100).toInt().coerceIn(0, 100)

        val band = scenario.debriefing.bands
            .sortedByDescending { it.minScore }
            .firstOrNull { percent >= it.minScore }
            ?: scenario.debriefing.bands.minByOrNull { it.minScore }
            ?: Band(0, "Senza giudizio", "Manca la scala di valutazione.")

        val perAxisMaximum = perAxisMaxima()

        return Verdict(
            opening = scenario.debriefing.opening,
            title = band.title,
            body = band.body,
            closing = scenario.debriefing.closing,
            scorePercent = percent,
            containmentPercent = percentOf(state.containment, perAxisMaximum.first),
            evidencePercent = percentOf(state.evidence, perAxisMaximum.second),
            trustPercent = percentOf(state.trust, perAxisMaximum.third),
            hours = state.hours,
            notes = scenario.debriefing.flagNotes
                .filterKeys { it in state.flags }
                .values
                .toList(),
            decisions = state.decisions,
        )
    }

    /** Best achievable on each axis separately, so one weak axis is visible on its own. */
    private fun perAxisMaxima(): Triple<Int, Int, Int> {
        var containment = 0
        var evidence = 0
        var trust = 0
        scenario.scenes.forEach { scene ->
            containment += scene.choices.maxOfOrNull { it.effects.containment } ?: 0
            evidence += scene.choices.maxOfOrNull { it.effects.evidence } ?: 0
            trust += scene.choices.maxOfOrNull { it.effects.trust } ?: 0
        }
        return Triple(containment, evidence, trust)
    }

    private fun percentOf(value: Int, maximum: Int): Int =
        if (maximum <= 0) 0 else ((value.toDouble() / maximum) * 100).toInt().coerceIn(0, 100)
}
