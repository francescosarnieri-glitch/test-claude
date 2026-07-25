package com.cybersensei.academy.core.curriculum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeEngineTest {

    private val engine = BadgeEngine.fromResources()

    @Test
    fun `the shipped badges are well formed`() {
        val problems = engine.validate()
        assertTrue("Problemi nei badge:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `a student who has done nothing has earned nothing`() {
        assertTrue(engine.earned(BadgeContext()).isEmpty())
    }

    @Test
    fun `finishing the introduction earns the Matricola`() {
        val earned = engine.earned(BadgeContext(passedLevels = setOf(0), lessonsCompleted = 4))
        assertTrue(earned.map { it.id }.contains("matricola"))
    }

    @Test
    fun `the first lesson is worth a badge and the tenth another`() {
        assertTrue(engine.earned(BadgeContext(lessonsCompleted = 1)).map { it.id }.contains("prima_lezione"))
        assertTrue(engine.earned(BadgeContext(lessonsCompleted = 10)).map { it.id }.contains("studioso"))
        assertTrue(engine.earned(BadgeContext(lessonsCompleted = 9)).map { it.id }.none { it == "studioso" })
    }

    @Test
    fun `a badge is announced only the first time`() {
        val context = BadgeContext(lessonsCompleted = 1)
        assertEquals(1, engine.newlyEarned(context, alreadyHeld = emptySet()).size)
        assertTrue(engine.newlyEarned(context, alreadyHeld = setOf("prima_lezione")).isEmpty())
    }

    @Test
    fun `mastery badges need mastery, not attendance`() {
        assertTrue(engine.earned(BadgeContext(masteryAverage = 0.85)).map { it.id }.none { it == "memoria_solida" })
        assertTrue(engine.earned(BadgeContext(masteryAverage = 0.91)).map { it.id }.contains("memoria_solida"))
    }

    @Test
    fun `an unknown rule never hands out a badge by accident`() {
        val odd = BadgeEngine(
            listOf(
                Badge(
                    id = "misterioso",
                    name = "Misterioso",
                    icon = "?",
                    description = "Una condizione che il motore non conosce ancora affatto.",
                    condition = BadgeCondition("qualcosa_di_futuro", 1),
                ),
            ),
        )
        assertTrue(odd.earned(BadgeContext(lessonsCompleted = 999, streakDays = 999)).isEmpty())
    }
}
