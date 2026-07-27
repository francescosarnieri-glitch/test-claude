package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perché il professore non ha saputo rispondere.
 *
 * Per mesi c'e' stata una risposta sola per «non lo so», scritta per un argomento di
 * sicurezza non ancora in programma. Applicata a «parlami della carbonara» faceva promettere
 * al professore una lezione sulla carbonara al momento giusto. Qui si difende la distinzione:
 * fuori materia, in materia ma non coperto, e non ho capito la frase sono tre cose diverse.
 */
class RifiutoTest {

    private val answerer = QuestionAnswerer(KnowledgeBase.fromResources())

    private fun motivo(domanda: String): Miss? =
        (answerer.ask(domanda) as? AnswerResult.NotUnderstood)?.reason

    @Test
    fun `una domanda che non c'entra niente e' fuori materia, non in arrivo`() {
        listOf(
            "parlami della carbonara",
            "come si pota un ulivo",
            "chi ha vinto il mondiale nel 2006",
            "quanto costa un volo per tokyo",
            "come si cambia una gomma dell'auto",
            // Il lessico e' cresciuto per coprire il programma, e ogni parola generica che
            // ci entra e' una frase innocua che rischia di sembrare una domanda di sicurezza.
            "consigliami un film di paura",
            "che regola c'e' nel calcio per il fuorigioco",
            "come si prepara la carbonara",
            "che voto daresti a questo piatto",
        ).forEach { domanda ->
            val motivo = motivo(domanda)
            assertTrue(
                "«$domanda» doveva essere fuori materia o una risposta scritta, non $motivo",
                motivo == null || motivo == Miss.OFF_TOPIC,
            )
        }
    }

    /**
     * Il rovescio, e la parte piu' delicata: una domanda di sicurezza che il programma non
     * copre deve restare "ci arriveremo". Se finisse fra i fuori materia, il professore
     * direbbe "non e' il mio reparto" su una cosa che e' esattamente il suo reparto.
     */
    @Test
    fun `una domanda di sicurezza non coperta resta in materia`() {
        listOf(
            "cos'e' il protocollo kerberos",
            "come funziona il sandboxing del browser",
            "cos'e' un rootkit a livello di firmware",
        ).forEach { domanda ->
            val esito = answerer.ask(domanda)
            val motivo = (esito as? AnswerResult.NotUnderstood)?.reason
            assertTrue(
                "«$domanda» e' materia sua, e invece: $motivo",
                esito !is AnswerResult.NotUnderstood || motivo == Miss.NOT_COVERED,
            )
        }
    }

    @Test
    fun `una frase senza niente dentro chiede di essere riformulata`() {
        assertEquals(Miss.UNPARSEABLE, motivo(""))
        assertEquals(Miss.UNPARSEABLE, motivo("   ...???   "))
        assertEquals(Miss.UNPARSEABLE, motivo("il la di e"))
    }

    /**
     * Il criterio, isolato: una parola che il programma usa dappertutto non rende una frase
     * una domanda di sicurezza. "Parlami" compare in mezzo corpus.
     */
    @Test
    fun `le parole di lingua non fanno diventare una frase una domanda di sicurezza`() {
        assertTrue(answerer.isAboutTheSubject("cos'e' il phishing"))
        assertTrue(answerer.isAboutTheSubject("come funziona il ransomware"))

        assertTrue("«parlami della carbonara» non parla di sicurezza", !answerer.isAboutTheSubject("parlami della carbonara"))
        assertTrue("«dimmi qualcosa di bello» nemmeno", !answerer.isAboutTheSubject("dimmi qualcosa di bello"))
    }
}
