package com.cybersensei.academy.engine.mastery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelGateTest {

    private val required = listOf("password", "mfa", "phishing", "backup")

    private fun masteries(vararg values: Pair<String, Double>) =
        values.map { (id, v) -> Mastery(id, v, attempts = 5) }

    @Test
    fun `a strong student passes`() {
        val result = LevelGate.evaluate(
            required,
            masteries("password" to 0.9, "mfa" to 0.85, "phishing" to 0.82, "backup" to 0.8),
        )
        assertTrue(result.passed)
        assertTrue(result.weakSkills.isEmpty())
    }

    @Test
    fun `one weak skill blocks the level even with a brilliant average`() {
        val result = LevelGate.evaluate(
            required,
            masteries("password" to 1.0, "mfa" to 1.0, "phishing" to 1.0, "backup" to 0.35),
        )
        assertFalse("La media alta non deve nascondere un buco", result.passed)
        assertTrue(result.average > LevelGate.AVERAGE_REQUIRED)
        assertEquals(listOf("backup"), result.weakSkills.map { it.skillId })
    }

    @Test
    fun `a decent but unremarkable student is held back by the average`() {
        val result = LevelGate.evaluate(
            required,
            masteries("password" to 0.7, "mfa" to 0.68, "phishing" to 0.72, "backup" to 0.65),
        )
        assertFalse(result.passed)
        assertTrue("Nessuna abilità è sotto la soglia minima", result.weakSkills.isEmpty())
    }

    @Test
    fun `skills never attempted are reported as missing, not as failures`() {
        val result = LevelGate.evaluate(
            required,
            masteries("password" to 0.9, "mfa" to 0.9),
        )
        assertFalse(result.passed)
        assertEquals(listOf("phishing", "backup"), result.missingSkills)
        assertTrue(result.weakSkills.isEmpty())
    }

    @Test
    fun `weak skills come back weakest first, so revision starts where it hurts`() {
        val result = LevelGate.evaluate(
            required,
            masteries("password" to 0.5, "mfa" to 0.2, "phishing" to 0.4, "backup" to 0.9),
        )
        assertEquals(listOf("mfa", "phishing", "password"), result.weakSkills.map { it.skillId })
    }

    @Test
    fun `a student who has done nothing does not pass`() {
        val result = LevelGate.evaluate(required, emptyList())
        assertFalse(result.passed)
        assertEquals(0.0, result.average, 0.0001)
        assertEquals(required, result.missingSkills)
    }
}
