package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the professor is allowed to remember, and what he must forget.
 *
 * The interesting cases are the two asymmetries: a question he could not answer is still
 * something the student asked — so it belongs in "quali domande ti ho fatto" — but it is not
 * something he said, so "ripeti" must skip over it and repeat the last real answer.
 */
class ConversationMemoryTest {

    private fun turn(question: String, entryId: String? = "faq_x", answer: String = "risposta") =
        Turn(question = question, answer = answer, entryId = entryId, topic = entryId)

    @Test
    fun `una conversazione appena aperta non ricorda niente`() {
        val memory = ConversationMemory()

        assertTrue(memory.isEmpty)
        assertNull(memory.lastAnswered())
        assertTrue(memory.questionsAsked().isEmpty())
    }

    @Test
    fun `le domande restano nell'ordine in cui sono state fatte`() {
        val memory = ConversationMemory()
        memory.remember(turn("cos'e' il phishing"))
        memory.remember(turn("e la crittografia"))

        assertEquals(listOf("cos'e' il phishing", "e la crittografia"), memory.questionsAsked())
    }

    /** Chiedere due volte la stessa cosa non la fa comparire due volte nell'elenco. */
    @Test
    fun `la stessa domanda ripetuta compare una volta sola`() {
        val memory = ConversationMemory()
        memory.remember(turn("cos'e' il phishing"))
        memory.remember(turn("cos'e' il phishing"))

        assertEquals(1, memory.questionsAsked().size)
    }

    @Test
    fun `ripeti salta le domande a cui non ha saputo rispondere`() {
        val memory = ConversationMemory()
        memory.remember(turn("cos'e' il phishing", entryId = "faq_phishing", answer = "La risposta buona"))
        memory.remember(turn("ricetta della carbonara", entryId = null, answer = "Non e' materia mia"))

        assertEquals("La risposta buona", memory.lastAnswered()?.answer)
        // Ma la domanda rifiutata resta fra quelle fatte: e' comunque successa.
        assertEquals(2, memory.questionsAsked().size)
    }

    @Test
    fun `prima significa la penultima risposta davvero data`() {
        val memory = ConversationMemory()
        memory.remember(turn("uno", entryId = "a", answer = "prima risposta"))
        memory.remember(turn("due", entryId = "b", answer = "seconda risposta"))
        memory.remember(turn("tre", entryId = null, answer = "non lo so"))

        assertEquals("seconda risposta", memory.lastAnswered()?.answer)
        assertEquals("prima risposta", memory.previousAnswered()?.answer)
    }

    /** La memoria e' corta per scelta: un seguito riguarda l'ultima cosa detta, non quella di ieri. */
    @Test
    fun `oltre la capienza le battute piu' vecchie cadono`() {
        val memory = ConversationMemory(capacity = 3)
        repeat(5) { memory.remember(turn("domanda $it")) }

        assertEquals(3, memory.size)
        assertEquals(listOf("domanda 2", "domanda 3", "domanda 4"), memory.questionsAsked())
    }

    @Test
    fun `chiudere lo studio cancella tutto`() {
        val memory = ConversationMemory()
        memory.remember(turn("qualcosa"))
        memory.clear()

        assertTrue(memory.isEmpty)
    }
}
