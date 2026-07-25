package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItalianTextTest {

    @Test
    fun `accents and punctuation are flattened away`() {
        assertEquals("perche e cosi importante", ItalianText.normalise("Perché è così importante?"))
        assertEquals("l app e sicura", ItalianText.normalise("L'app è sicura!"))
    }

    @Test
    fun `filler words are dropped`() {
        val tokens = ItalianText.tokenise("Come funziona la cifratura dei dati?")
        assertFalse(tokens.contains("come"))
        assertFalse(tokens.contains("la"))
        assertTrue(tokens.contains("cifratura"))
        assertTrue(tokens.contains("dati"))
    }

    @Test
    fun `singular and plural reduce to the same term`() {
        assertEquals(ItalianText.stem("password"), ItalianText.stem("password"))
        assertEquals(ItalianText.stem("aggiornamento"), ItalianText.stem("aggiornamenti"))
        assertEquals(ItalianText.stem("attacco"), ItalianText.stem("attacchi"))
    }

    @Test
    fun `short words are left alone rather than mangled`() {
        assertEquals("hash", ItalianText.stem("hash"))
        assertEquals("vpn", ItalianText.stem("vpn"))
        assertEquals("dns", ItalianText.stem("dns"))
    }

    @Test
    fun `stemming never produces a stub that would match everything`() {
        listOf("cifratura", "password", "phishing", "backup", "malware").forEach {
            assertTrue("Stem troppo corto per '$it'", ItalianText.stem(it).length >= 4)
        }
    }

    @Test
    fun `edit distance measures how far a typo is`() {
        assertEquals(0, ItalianText.editDistance("password", "password"))
        assertEquals(1, ItalianText.editDistance("pasword", "password"))
        assertEquals(2, ItalianText.editDistance("pasord", "password"))
    }

    @Test
    fun `edit distance gives up early instead of scanning the whole string`() {
        val distance = ItalianText.editDistance("phishing", "crittografia", max = 3)
        assertTrue("Oltre il limite basta sapere che è lontano", distance > 3)
    }
}
