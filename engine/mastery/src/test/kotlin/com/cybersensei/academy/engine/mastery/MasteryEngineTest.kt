package com.cybersensei.academy.engine.mastery

import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasteryEngineTest {

    private val engine = MasteryEngine()
    private val skill = "distinguere_hash_da_cifratura"
    private val start = Instant.parse("2026-07-25T10:00:00Z")

    private fun mastery(value: Double, streak: Int = 0, practised: Instant? = start) =
        Mastery(skill, value, attempts = 3, consecutiveCorrect = streak, lastPracticed = practised)

    private fun answer(
        correct: Boolean,
        confidence: Confidence,
        seconds: Long = 40,
        expected: Long = 40,
    ) = AnswerRecord(
        skillId = skill,
        correct = correct,
        confidence = confidence,
        responseTime = seconds.seconds,
        expectedTime = expected.seconds,
        answeredAt = start,
    )

    // --- How an answer is read -------------------------------------------------------

    @Test
    fun `right and confident is a solid answer`() {
        val update = engine.register(mastery(0.5), answer(true, Confidence.SURE))
        assertEquals(AnswerVerdict.SOLID, update.verdict)
        assertTrue(update.delta > 0)
    }

    @Test
    fun `right but hesitant is fragile, and worth less`() {
        val fragile = engine.register(mastery(0.5), answer(true, Confidence.UNSURE))
        val solid = engine.register(mastery(0.5), answer(true, Confidence.SURE))

        assertEquals(AnswerVerdict.CORRECT_BUT_FRAGILE, fragile.verdict)
        assertTrue(
            "Una risposta esitante deve valere meno di una sicura",
            fragile.delta < solid.delta,
        )
        assertTrue(fragile.experiencePoints < solid.experiencePoints)
    }

    @Test
    fun `an admitted guess barely counts even when it is right`() {
        val lucky = engine.register(mastery(0.5), answer(true, Confidence.GUESS))
        assertEquals(AnswerVerdict.SUSPECTED_LUCK, lucky.verdict)
        assertTrue("La fortuna non deve far salire la padronanza", lucky.delta < 0.02)
    }

    /**
     * Il caso che ha fatto cambiare la regola.
     *
     * Chi ha appena installato l'app ha padronanza zero su tutto, per definizione. Se sa gia'
     * la risposta e la da' in due secondi dichiarando di esserne sicuro, il professore gli
     * diceva che aveva tirato a indovinare e non gli contava il punto. La lezione che uno
     * studente impara da li' e' «aspetta prima di rispondere anche quando lo sai», ed e' una
     * strategia che gli ha insegnato la scuola.
     */
    @Test
    fun `chi dichiara di essere sicuro e ha ragione viene creduto, anche se e' stato veloce`() {
        val update = engine.register(
            mastery(0.0),
            answer(true, Confidence.SURE, seconds = 2, expected = 40),
        )
        assertEquals(
            "Sapere una cosa il primo giorno non e' sospetto",
            AnswerVerdict.SOLID,
            update.verdict,
        )
        assertTrue("E deve valere il punto pieno", update.delta > 0.1)
    }

    /**
     * Il freno resta, dove il segnale c'e' davvero: chi risponde d'istinto senza dichiarare
     * niente su una competenza ancora debole sta cliccando, non ragionando.
     */
    @Test
    fun `una risposta istantanea e incerta su una competenza debole resta sospetta`() {
        val update = engine.register(
            mastery(0.2),
            answer(true, Confidence.UNSURE, seconds = 2, expected = 40),
        )
        assertEquals(AnswerVerdict.SUSPECTED_LUCK, update.verdict)
    }

    /**
     * E dichiarare il falso costa: e' il motivo per cui la dichiarazione si puo' credere.
     * Un tiratore a indovinare che si dichiara sicuro sbaglia tre volte su quattro, e ogni
     * volta prende il verdetto piu' duro che il motore abbia.
     */
    @Test
    fun `dichiararsi sicuri e sbagliare e' il verdetto piu' pesante`() {
        val sbagliata = engine.register(mastery(0.5), answer(false, Confidence.SURE))
        val onesta = engine.register(mastery(0.5), answer(false, Confidence.GUESS))

        assertEquals(AnswerVerdict.ROOTED_MISCONCEPTION, sbagliata.verdict)
        assertTrue(
            "Sbagliare da sicuri deve costare piu' che sbagliare ammettendolo",
            sbagliata.delta < onesta.delta,
        )
    }

    @Test
    fun `a fast answer on a mastered skill is simply a fast answer`() {
        val update = engine.register(
            mastery(0.85),
            answer(true, Confidence.SURE, seconds = 2, expected = 40),
        )
        assertEquals(
            "Chi sa davvero una cosa ha il diritto di rispondere in fretta",
            AnswerVerdict.SOLID,
            update.verdict,
        )
    }

    @Test
    fun `wrong and confident is the most serious case`() {
        val rooted = engine.register(mastery(0.7), answer(false, Confidence.SURE))
        val honest = engine.register(mastery(0.7), answer(false, Confidence.UNSURE))

        assertEquals(AnswerVerdict.ROOTED_MISCONCEPTION, rooted.verdict)
        assertEquals(AnswerVerdict.HONEST_MISS, honest.verdict)
        assertTrue(
            "Sbagliare da convinti deve costare più che sbagliare dubitando",
            rooted.delta < honest.delta,
        )
    }

    @Test
    fun `admitting you do not know costs almost nothing`() {
        val update = engine.register(mastery(0.6), answer(false, Confidence.GUESS))
        assertEquals(AnswerVerdict.HONEST_MISS, update.verdict)
        assertTrue("L'onestà non deve essere punita", update.delta > -0.06)
    }

    @Test
    fun `every verdict except a solid one sends the question back into the queue`() {
        AnswerVerdict.entries.forEach { verdict ->
            assertEquals(verdict != AnswerVerdict.SOLID, verdict.needsRequeue)
        }
    }

    // --- Bounds and streaks -----------------------------------------------------------

    @Test
    fun `mastery never leaves the zero to one range`() {
        var state = Mastery(skill)
        repeat(60) {
            state = engine.register(state, answer(true, Confidence.SURE)).mastery
        }
        assertTrue(state.value <= 1.0)

        repeat(60) {
            state = engine.register(state, answer(false, Confidence.SURE)).mastery
        }
        assertTrue(state.value >= 0.0)
    }

    @Test
    fun `a wrong answer breaks the streak, a lucky one does not`() {
        val afterLuck = engine.register(mastery(0.5, streak = 4), answer(true, Confidence.GUESS))
        assertEquals(5, afterLuck.mastery.consecutiveCorrect)

        val afterMiss = engine.register(mastery(0.5, streak = 4), answer(false, Confidence.UNSURE))
        assertEquals(0, afterMiss.mastery.consecutiveCorrect)
    }

    @Test
    fun `twenty lucky answers never reach the level threshold`() {
        var state = Mastery(skill)
        repeat(20) {
            state = engine.register(state, answer(true, Confidence.GUESS)).mastery
        }
        assertTrue(
            "Indovinando venti volte non si deve sbloccare nulla (era ${state.percent}%)",
            state.value < LevelGate.MINIMUM_PER_SKILL,
        )
    }

    // --- Forgetting -------------------------------------------------------------------

    @Test
    fun `knowledge fades while the app is closed`() {
        val fresh = mastery(0.9, streak = 0)
        val later = engine.decay(fresh, start.plusSeconds(30 * 86_400))
        assertTrue("Dopo un mese deve essere calata", later.value < fresh.value)
        assertTrue("Ma non deve azzerarsi", later.value > 0.1)
    }

    @Test
    fun `a skill confirmed many times fades more slowly`() {
        val month = start.plusSeconds(30 * 86_400)
        val shaky = engine.decay(mastery(0.9, streak = 0), month)
        val rehearsed = engine.decay(mastery(0.9, streak = 6), month)
        assertTrue(rehearsed.value > shaky.value)
    }

    @Test
    fun `a skill never practised does not decay`() {
        val untouched = Mastery(skill, value = 0.0, lastPracticed = null)
        assertEquals(untouched, engine.decay(untouched, start.plusSeconds(999_999)))
    }
}
