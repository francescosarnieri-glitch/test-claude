package com.cybersensei.academy.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicRandomTest {

    @Test
    fun `same inputs produce the same seed`() {
        assertEquals(
            DeterministicRandom.seedOf("francesco", "answer_correct", 7),
            DeterministicRandom.seedOf("francesco", "answer_correct", 7),
        )
    }

    @Test
    fun `different inputs produce different seeds`() {
        assertNotEquals(
            DeterministicRandom.seedOf("francesco", "answer_correct", 7),
            DeterministicRandom.seedOf("francesco", "answer_correct", 8),
        )
    }

    @Test
    fun `picking avoids recently used lines`() {
        val lines = listOf("a", "b", "c", "d")
        val random = DeterministicRandom.forSeed("student", "event", 1)
        repeat(50) { round ->
            val recent = listOf("a", "b", "c")
            val picked = lines.pickAvoidingRecent(random, recent)
            assertEquals("Round $round should fall back to the only fresh line", "d", picked)
        }
    }

    @Test
    fun `picking falls back to the full pool when everything is stale`() {
        val lines = listOf("a", "b")
        val random = DeterministicRandom.forSeed("student", "event", 2)
        val picked = lines.pickAvoidingRecent(random, lines)
        assertTrue(picked in lines)
    }
}
