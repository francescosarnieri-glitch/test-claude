package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Piu' domande in un messaggio solo.
 *
 * «Come ti chiami e quanti anni hai» riceveva l'eta' e basta: il nome spariva. Non e' un
 * difetto di comprensione — il motore aveva capito benissimo — e' che tornava una risposta
 * sola dove le domande erano due, e chi legge non vede la differenza fra questo e un
 * professore che non ascolta.
 *
 * Qui si difendono le due meta' del problema: che il messaggio venga diviso quando contiene
 * davvero piu' domande, e che non venga diviso quando la congiunzione sta dentro una domanda
 * sola. «Differenza fra phishing e smishing» e' una domanda, non due.
 */
class PiuDomandeTest {

    private val knowledgeBase = KnowledgeBase.fromResources()
    private val answerer = QuestionAnswerer(
        knowledgeBase,
        semantic = SemanticIndex(knowledgeBase.entries, WordVectors.fromResources()),
    )

    @Test
    fun `due domande unite da una congiunzione diventano due`() {
        assertEquals(
            listOf("come ti chiami", "quanti anni hai"),
            QuestionSplitter.split("come ti chiami e quanti anni hai"),
        )
        // Il punto interrogativo finale se ne va con la divisione: e' punteggiatura, non
        // contenuto, e nessuno dei due motori lo guarda.
        assertEquals(
            listOf("Come ti chiami", "quanti anni hai"),
            QuestionSplitter.split("Come ti chiami e quanti anni hai?"),
        )
        assertEquals(
            listOf("cos'e' il phishing", "come funziona il ransomware"),
            QuestionSplitter.split("cos'e' il phishing e come funziona il ransomware"),
        )
    }

    @Test
    fun `il punto interrogativo divide da solo`() {
        assertEquals(
            listOf("chi sei", "cosa insegni"),
            QuestionSplitter.split("chi sei? cosa insegni?"),
        )
    }

    /**
     * La meta' pericolosa. Una congiunzione dentro un elenco non separa niente, e dividere
     * qui vorrebbe dire rispondere due volte male a una domanda che era una.
     */
    @Test
    fun `una congiunzione dentro una domanda sola non divide`() {
        listOf(
            "che differenza c'e' fra phishing e smishing",
            "cos'e' il phishing e come mi difendo",
            "mi spieghi backup e ripristino",
            "parlami di virus e worm",
            "come funzionano le password e i gestori",
        ).forEach { domanda ->
            assertEquals(
                "«$domanda» e' una domanda sola",
                listOf(domanda),
                QuestionSplitter.split(domanda),
            )
        }
    }

    @Test
    fun `un messaggio normale resta intero`() {
        listOf(
            "cos'e' il phishing",
            "come riconosco un'email falsa?",
            "ciao",
            "",
        ).forEach { domanda ->
            assertEquals(listOf(domanda.trim()), QuestionSplitter.split(domanda))
        }
    }

    /** Tre risposte sono gia' un muro di testo; oltre, il messaggio e' un tema. */
    @Test
    fun `non si superano mai tre risposte`() {
        val fiume = "chi sei? quanti anni hai? cosa insegni? dove abiti? come stai?"
        assertTrue(QuestionSplitter.split(fiume).size <= QuestionSplitter.MAX_PARTS)
    }

    /** Il caso della fotografia: due domande, due risposte, e il nome non si perde. */
    @Test
    fun `il professore risponde a tutte e due le domande`() {
        val risposte = answerer.askAll("Come ti chiami e quanti anni hai")

        assertEquals("Doveva rispondere due volte: $risposte", 2, risposte.size)
        risposte.forEach {
            assertTrue(
                "«${it.question}» e' rimasta senza risposta",
                it.result is AnswerResult.Found,
            )
        }
        val voci = risposte.mapNotNull { (it.result as? AnswerResult.Found)?.entry?.id }
        assertEquals("Le due risposte sono la stessa: $voci", 2, voci.toSet().size)
    }

    /**
     * La rete di sicurezza. Se dividere peggiora le cose — un pezzo senza niente dentro, o
     * due pezzi che finiscono sulla stessa voce — il messaggio va risposto com'era scritto.
     */
    @Test
    fun `se dividere peggiora le cose il messaggio resta intero`() {
        listOf(
            "cos'e' il phishing e come mi difendo",
            "che differenza c'e' fra phishing e smishing",
        ).forEach { domanda ->
            assertEquals(
                "«$domanda» doveva restare una risposta sola",
                1,
                answerer.askAll(domanda).size,
            )
        }
    }

    /** Ogni pezzo porta con se' le parole dello studente, non una versione normalizzata. */
    @Test
    fun `le domande divise restano scritte come le ha scritte lo studente`() {
        val risposte = answerer.askAll("Chi sei? E cosa sai fare?")

        risposte.forEach { assertTrue(it.question.isNotBlank()) }
        assertTrue(
            "La prima domanda ha perso le maiuscole: ${risposte.first().question}",
            risposte.first().question.startsWith("Chi"),
        )
    }
}
