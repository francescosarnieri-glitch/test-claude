package com.cybersensei.academy.engine.tutor

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The content validator, run as a test so that a broken script cannot reach a build.
 *
 * These checks are about the *shape* of the professor's material, not its wording: no rule
 * pointing at a missing pool, no event he could not answer, no placeholder the app would not
 * know how to fill.
 */
class DialogueContentTest {

    private val library = DialogueLibrary.fromResources()

    @Test
    fun `the shipped script is structurally sound`() {
        val problems = library.validate()
        assertTrue(
            "Problemi nel copione del professore:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `every event the app can raise has at least one rule`() {
        val uncovered = TutorEvent.ALL_KEYS.filter { library.rulesFor(it).isEmpty() }
        assertTrue("Eventi senza risposta: $uncovered", uncovered.isEmpty())
    }

    @Test
    fun `no line uses a placeholder the app cannot fill`() {
        val unknown = library.pools.flatMap { pool ->
            pool.lines.flatMap { line ->
                SLOT_PATTERN.findAll(line)
                    .map { it.groupValues[1] }
                    .filterNot { it in SlotResolver.KNOWN_SLOTS }
                    .map { "${pool.id}: {$it}" }
            }
        }
        assertTrue("Segnaposto sconosciuti: $unknown", unknown.isEmpty())
    }

    @Test
    fun `every pool offers enough variety to avoid sounding scripted`() {
        val thin = library.pools.filter { it.lines.size < 2 }.map { it.id }
        assertTrue("Pool con meno di due battute: $thin", thin.isEmpty())
    }

    @Test
    fun `no two pools share an id and no two rules share an id`() {
        val poolIds = library.pools.map { it.id }
        val ruleIds = library.rules.map { it.id }
        assertTrue("Pool duplicati", poolIds.size == poolIds.toSet().size)
        assertTrue("Regole duplicate", ruleIds.size == ruleIds.toSet().size)
    }

    @Test
    fun `lines are written for a phone screen, not for an essay`() {
        val tooLong = library.pools.flatMap { pool ->
            pool.lines.filter { it.length > MAX_LINE_LENGTH }.map { "${pool.id}: ${it.take(40)}…" }
        }
        assertTrue("Battute troppo lunghe per una bolla: $tooLong", tooLong.isEmpty())
    }

    @Test
    fun `the professor never addresses the student with a slur or a taunt`() {
        // The tone is authoritative and direct, never mocking. Cheap guard, high value:
        // it makes the rule explicit for whoever writes the next thousand lines.
        val banned = listOf("stupido", "idiota", "scemo", "imbecille", "cretino", "somaro")
        val offenders = library.pools.flatMap { pool ->
            pool.lines.filter { line -> banned.any { line.contains(it, ignoreCase = true) } }
                .map { pool.id }
        }
        assertTrue("Battute offensive verso lo studente: $offenders", offenders.isEmpty())
    }

    private companion object {
        val SLOT_PATTERN = Regex("\\{([a-z_]+)}")
        const val MAX_LINE_LENGTH = 260
    }
}
