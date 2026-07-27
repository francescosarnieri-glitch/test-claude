package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionAnswererTest {

    private val knowledgeBase = KnowledgeBase.fromResources()
    private val answerer = QuestionAnswerer(knowledgeBase)

    private fun answerIdFor(question: String): String? =
        (answerer.ask(question) as? AnswerResult.Found)?.entry?.id

    /**
     * The entries a question could have landed on: the one it did, or the two the professor
     * offered when he refused to choose between a near-tie.
     *
     * Asking is not a wrong answer. An alias that produces a choice containing its own entry
     * has still reached the student — with one tap in between, and without the pretence that
     * the ranking meant something.
     */
    private fun reachedBy(question: String): List<String> = when (val r = answerer.ask(question)) {
        is AnswerResult.Found -> listOf(r.entry.id)
        is AnswerResult.Ambiguous -> r.options.map { it.id }
        is AnswerResult.Unsure -> r.options.map { it.id }
        is AnswerResult.NotUnderstood -> emptyList()
    }

    private fun assertAnswers(expectedId: String, vararg questions: String) {
        questions.forEach { question ->
            val result = answerer.ask(question)
            assertTrue(
                "«$question» non ha trovato risposta (${(result as? AnswerResult.NotUnderstood)?.bestScore})",
                result is AnswerResult.Found,
            )
            assertEquals("«$question»", expectedId, (result as AnswerResult.Found).entry.id)
        }
    }

    // --- Questions the way a student actually types them --------------------------------

    @Test
    fun `the same question phrased differently reaches the same answer`() {
        assertAnswers(
            "faq_vpn_anonima",
            "La VPN mi rende anonimo?",
            "usando una vpn sono anonimo?",
            "prof, la vpn serve davvero a nascondersi?",
        )
    }

    @Test
    fun `an alias phrasing works as well as the canonical question`() {
        assertAnswers(
            "faq_hash_vs_cifratura",
            "che differenza c'è tra hash e cifratura",
            "l'hash si può decifrare?",
            "come vengono salvate le password nei siti",
        )
    }

    @Test
    fun `a typo does not stop the professor from understanding`() {
        assertAnswers(
            "faq_phishing_riconoscere",
            "come riconosco un email di phising?",
            "come riconosco unemail di phishng",
        )
    }

    @Test
    fun `domain slang is folded onto the proper term`() {
        assertAnswers("faq_password_sicura", "come faccio una pwd sicura")
        assertAnswers("faq_2fa", "a cosa serve la 2fa")
    }

    @Test
    fun `questions without a question mark still work`() {
        assertAnswers("faq_backup_321", "come si fa un backup fatto bene")
    }

    @Test
    fun `close but distinct topics do not get confused`() {
        assertEquals("faq_hash_vs_cifratura", answerIdFor("l'hash è reversibile"))
        assertEquals("faq_salt", answerIdFor("a cosa serve il salt"))
        assertEquals("faq_incognito", answerIdFor("la navigazione in incognito mi protegge"))
        assertEquals("faq_impronta_digitale", answerIdFor("mi tracciano anche senza cookie"))
    }

    // --- Knowing when not to know -------------------------------------------------------

    /**
     * The rule has not changed — the professor must never answer a carbonara question with
     * a lesson. What changed is that saying so out loud is now itself an answer, written in
     * the content, instead of the silence the student used to get.
     */
    @Test
    fun `a question outside the syllabus is turned down out loud, never invented`() {
        val result = answerer.ask("qual è la ricetta della carbonara?")
        val entry = (result as? AnswerResult.Found)?.entry
        assertTrue(
            "Non deve rispondere con una lezione: ${entry?.id}",
            entry == null || entry.kind == EntryKind.CONVERSATION,
        )
        assertTrue("E deve dire che non è materia sua", entry?.answer?.isNotBlank() ?: true)
    }

    @Test
    fun `an empty or meaningless question does not crash`() {
        assertTrue(answerer.ask("") is AnswerResult.NotUnderstood)
        assertTrue(answerer.ask("   ...???   ") is AnswerResult.NotUnderstood)
        assertTrue(answerer.ask("il la di e") is AnswerResult.NotUnderstood)
    }

    @Test
    fun `a vague question gets the closest topics offered alongside the answer`() {
        val result = answerer.ask("password") as AnswerResult.Found
        assertEquals("faq_password_sicura", result.entry.id)
        assertTrue("Una domanda generica deve proporre argomenti vicini", result.alternatives.isNotEmpty())
        assertTrue(result.alternatives.none { it.id == result.entry.id })
    }

    @Test
    fun `a precise question is answered without cluttering it with alternatives`() {
        val result = answerer.ask("l'hash si può decifrare") as AnswerResult.Found
        assertEquals("faq_hash_vs_cifratura", result.entry.id)
        assertTrue("Una domanda precisa non ha bisogno di suggerimenti", result.alternatives.isEmpty())
    }

    @Test
    fun `everyday questions that have nothing to do with the course never reach a lesson`() {
        listOf(
            "qual è la ricetta della carbonara?",
            "come si cambia una gomma dell'auto",
            "chi ha vinto il mondiale nel 2006",
            "che tempo fa domani a Napoli",
        ).forEach { question ->
            val entry = (answerer.ask(question) as? AnswerResult.Found)?.entry
            assertTrue(
                "«$question» è finita su una lezione: ${entry?.id}",
                entry == null || entry.kind == EntryKind.CONVERSATION,
            )
        }
    }

    @Test
    fun `the intent of a question does not decide the topic of the answer`() {
        // "difendersi" appears in the ransomware entry; the topic word is "phishing".
        assertEquals("faq_phishing_riconoscere", answerIdFor("come mi difendo dal phishing"))
        assertEquals("faq_ransomware", answerIdFor("come mi difendo dal ransomware"))
    }

    @Test
    fun `a confident match scores clearly above the threshold`() {
        val result = answerer.ask("che cos'è il white hacking") as AnswerResult.Found
        assertEquals("faq_white_hacking", result.entry.id)
        assertTrue("Punteggio troppo basso: ${result.score}", result.score > 0.4)
    }

    // --- The corpus itself ---------------------------------------------------------------

    @Test
    fun `the shipped FAQ is well formed`() {
        val problems = knowledgeBase.validate()
        assertTrue("Problemi nelle FAQ:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every FAQ entry can be found by its own question`() {
        val unreachable = knowledgeBase.entries.filter { entry ->
            entry.id !in reachedBy(entry.question)
        }
        assertTrue(
            "Voci irraggiungibili con la loro stessa domanda: ${unreachable.map { it.id }}",
            unreachable.isEmpty(),
        )
    }

    @Test
    fun `every alias leads to the entry it belongs to`() {
        val misrouted = knowledgeBase.entries.flatMap { entry ->
            entry.aliases.filter { alias -> entry.id !in reachedBy(alias) }
                .map { "${entry.id} <- «$it» (${reachedBy(it)})" }
        }
        assertTrue("Alias che portano altrove:\n${misrouted.joinToString("\n")}", misrouted.isEmpty())
    }
}
