package com.cybersensei.academy.content

import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.engine.nlu.EntryKind
import com.cybersensei.academy.engine.nlu.ItalianText
import com.cybersensei.academy.engine.nlu.KnowledgeBase
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

}
