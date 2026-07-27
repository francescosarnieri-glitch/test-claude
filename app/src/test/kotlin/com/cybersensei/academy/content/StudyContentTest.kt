package com.cybersensei.academy.content

import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.engine.nlu.AnswerResult
import com.cybersensei.academy.engine.nlu.EntryKind
import com.cybersensei.academy.engine.nlu.ItalianText
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.QuestionAnswerer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The study is only as honest as the link between what the professor answers and what he
 * actually teaches.
 *
 * These are the two claims the screen makes and cannot verify at runtime: that every answer
 * belongs to a real part of the syllabus, and that the level it is filed under is the level
 * where that topic is taught — because that is what decides whether the professor says
 * "ci arriveremo" or answers as if it were already covered.
 */
class StudyContentTest {

    private val knowledgeBase = KnowledgeBase.fromResources()
    private val curriculum = Curriculum.fromResources()
    private val answerer = QuestionAnswerer(knowledgeBase)

    private val levelOfSkill: Map<String, Int> = buildMap {
        curriculum.levels.forEach { level ->
            level.modules.forEach { module ->
                module.skills.forEach { skill -> put(skill, level.level) }
            }
        }
    }

    /**
     * Only the answers about the subject. The professor also answers questions about
     * himself and about the app, and those belong to no lesson by construction: filing
     * "chi sei" under a competence of the syllabus would be a lie about what it teaches.
     */
    @Test
    fun `every answer is filed under a skill the syllabus actually teaches`() {
        val orphans = knowledgeBase.entries
            .filter { it.kind == EntryKind.LESSON }
            .filter { it.skillId !in levelOfSkill }
            .map { "${it.id} -> ${it.skillId}" }
        assertTrue(
            "Risposte agganciate a competenze inesistenti:\n${orphans.joinToString("\n")}",
            orphans.isEmpty(),
        )
    }

    @Test
    fun `the level of an answer matches the level that teaches it`() {
        val mismatched = knowledgeBase.entries.mapNotNull { entry ->
            val taughtAt = levelOfSkill[entry.skillId] ?: return@mapNotNull null
            if (taughtAt == entry.level) {
                null
            } else {
                "${entry.id}: dichiarato livello ${entry.level}, insegnato al $taughtAt"
            }
        }
        assertTrue(
            "Il professore direbbe «ci arriveremo» su argomenti già fatti, o il contrario:\n" +
                mismatched.joinToString("\n"),
            mismatched.isEmpty(),
        )
    }

    /**
     * Il lessico di dominio deve coprire il programma che la scuola insegna davvero.
     *
     * E' la lista che decide se una domanda e' materia del professore o no: se resta
     * indietro rispetto al programma, una domanda su un argomento che insegniamo riceve
     * «non e' il mio reparto».
     *
     * Il controllo e' per modulo, non per singola competenza. Molte competenze si chiamano
     * "scrivere_una_regola" o "decisioni_sotto_pressione": parole della lingua, e metterle
     * nel lessico rende "che regola c'e' nel calcio" una domanda di sicurezza — provato,
     * succede. Cio' che deve essere riconoscibile e' l'argomento del modulo.
     */
    @Test
    fun `ogni modulo del programma e' riconoscibile dal lessico di dominio`() {
        val scoperti = curriculum.modules.filterNot { module ->
            val parole = (module.id + " " + module.title + " " + module.skills.joinToString(" "))
                .split(' ', '_')
                .map { ItalianText.stem(ItalianText.normalise(it)) }
            parole.any { it in knowledgeBase.domainStems }
        }

        assertTrue(
            "Moduli che il professore direbbe non essere materia sua:\n" +
                scoperti.joinToString("\n") { "${it.id} — ${it.title}" },
            scoperti.isEmpty(),
        )
    }

    @Test
    fun `every level of the syllabus can be asked about`() {
        val covered = knowledgeBase.entries.map { it.level }.toSet()
        val uncovered = curriculum.levels.map { it.level }.filterNot { it in covered }
        assertTrue("Livelli senza nessuna risposta nello studio: $uncovered", uncovered.isEmpty())
    }

    /**
     * The intermediate and hard levels were written long after the retrieval engine was
     * tuned on a much smaller corpus. These are the phrasings a student who has just
     * finished those modules would actually type.
     */
    @Test
    fun `questions from the later levels reach the right answer`() {
        assertAnswers("faq_lucchetto", "il lucchetto vuol dire che il sito è sicuro?")
        assertAnswers("faq_furto_sessione", "mi hanno rubato la sessione")
        assertAnswers("faq_dns_chi_vede", "il mio operatore vede i siti che apro?")
        assertAnswers("faq_zero_trust", "cosa vuol dire zero trust")
        assertAnswers("faq_pagare_riscatto", "conviene pagare per riavere i file?")
        assertAnswers("faq_container_vm", "un container isola come una macchina virtuale")
        assertAnswers("faq_gdpr_72_ore", "entro quanto devo notificare una violazione")
    }

    @Test
    /**
     * Growing the corpus must never turn "non lo so" into a lesson picked at random. Saying
     * out loud that a subject is not his — which is now written content, not silence — is
     * the one answer allowed here.
     */
    fun `a bigger corpus has not made the professor start inventing`() {
        listOf(
            "qual è la ricetta della carbonara?",
            "che tempo fa domani a Napoli",
            "come si pota un ulivo",
            "quanto costa un volo per Tokyo",
        ).forEach { question ->
            val entry = (answerer.ask(question) as? AnswerResult.Found)?.entry
            assertTrue(
                "«$question» ha ricevuto una lezione come risposta (${entry?.id})",
                entry == null || entry.kind == EntryKind.CONVERSATION,
            )
        }
    }

    private fun assertAnswers(expectedId: String, question: String) {
        val result = answerer.ask(question)
        assertTrue(
            "«$question» non ha trovato risposta " +
                "(${(result as? AnswerResult.NotUnderstood)?.bestScore})",
            result is AnswerResult.Found,
        )
        assertEquals("«$question»", expectedId, (result as AnswerResult.Found).entry.id)
    }
}
