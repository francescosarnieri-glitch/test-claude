package com.cybersensei.academy.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Guards the exact two mistakes that once closed the app on launch with no message: a
 * closing brace left as literal text, and a Java-only Unicode block name.
 */
class AndroidSafeRegexTest {

    @Test
    fun `the pattern that crashed the app is rejected`() {
        // Compiles on a JVM, throws on a phone. It must now fail here instead.
        val problem = problemIn("\\{([a-z_]+)}")
        assertNotNull("Doveva essere segnalata", problem)
        assertThrows(IllegalArgumentException::class.java) {
            androidSafeRegex("\\{([a-z_]+)}")
        }
    }

    @Test
    fun `the corrected pattern is accepted and still works`() {
        val regex = androidSafeRegex("\\{([a-z_]+)\\}")
        assertEquals("Ciao Francesco", regex.replace("Ciao {nome}") { "Francesco" })
    }

    @Test
    fun `java-only unicode blocks are rejected`() {
        assertNotNull(problemIn("\\p{InCombiningDiacriticalMarks}+"))
        assertThrows(IllegalArgumentException::class.java) {
            androidSafeRegex("\\p{InCombiningDiacriticalMarks}+")
        }
    }

    @Test
    fun `the portable way of writing the same class is accepted`() {
        assertNull(problemIn("\\p{Mn}+"))
        androidSafeRegex("\\p{Mn}+")
    }

    @Test
    fun `quantifiers with braces are not mistaken for literal braces`() {
        listOf("[ \\t]{2,}", "a{3}", "x{1,4}", " {2,}").forEach {
            assertNull("Falso allarme su «$it»", problemIn(it))
        }
    }

    @Test
    fun `ordinary patterns pass untouched`() {
        listOf("\\s+([,.;:!?])", "[,;:]\\s*([.!?])", "([,.;:])\\1+", "[^a-z0-9]+").forEach {
            assertNull("Falso allarme su «$it»", problemIn(it))
        }
    }

    @Test
    fun `an unterminated brace is reported rather than compiled`() {
        assertNotNull(problemIn("qualcosa{2"))
    }
}
