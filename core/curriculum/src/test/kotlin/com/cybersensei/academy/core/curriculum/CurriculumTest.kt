package com.cybersensei.academy.core.curriculum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The content validator for the syllabus. Every rule here is one of the promises the app
 * makes to the student, turned into something a build can check.
 */
class CurriculumTest {

    private val curriculum = Curriculum.fromResources()

    @Test
    fun `the shipped syllabus is structurally sound`() {
        val problems = curriculum.validate()
        assertTrue("Problemi nel programma:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every wrong answer explains itself`() {
        // The central promise of the product: a student who picks the wrong option is told
        // what *their* reasoning got wrong, not merely what the right answer was.
        val silent = curriculum.questions.flatMap { question ->
            question.options.filterNot { it.correct }
                .filter { it.rebuttal.isNullOrBlank() }
                .map { "${question.id}/${it.id}" }
        }
        assertTrue("Opzioni sbagliate senza confutazione: $silent", silent.isEmpty())
    }

    @Test
    fun `every wrong answer is tied to a named misconception`() {
        val untagged = curriculum.questions.flatMap { question ->
            question.options.filterNot { it.correct }
                .filter { it.misconception.isNullOrBlank() }
                .map { "${question.id}/${it.id}" }
        }
        assertTrue("Opzioni senza misconcezione dichiarata: $untagged", untagged.isEmpty())
    }

    @Test
    fun `rebuttals argue instead of just saying no`() {
        val tooShort = curriculum.questions.flatMap { question ->
            question.options.filterNot { it.correct }
                .filter { (it.rebuttal?.length ?: 0) < 80 }
                .map { "${question.id}/${it.id}" }
        }
        assertTrue("Confutazioni troppo sbrigative: $tooShort", tooShort.isEmpty())
    }

    @Test
    fun `every question can be verified from more than one angle`() {
        val lonely = curriculum.questions.filter { it.variantIds.isEmpty() }.map { it.id }
        assertTrue(
            "Domande senza varianti isomorfe: chi indovina non verrebbe mai smascherato ($lonely)",
            lonely.isEmpty(),
        )
    }

    @Test
    fun `variants point at questions measuring the same skill`() {
        val mismatched = curriculum.questions.flatMap { question ->
            question.variantIds.mapNotNull { curriculum.question(it) }
                .filter { it.skill != question.skill }
                .map { "${question.id} -> ${it.id}" }
        }
        assertTrue("Varianti che misurano un'altra abilità: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun `livello zero is short enough to be finished in one sitting`() {
        val intro = curriculum.level(0)
        assertNotNull(intro)
        val minutes = intro!!.modules.sumOf { it.totalMinutes }
        assertTrue("L'introduzione dovrebbe stare in mezz'ora, sono $minutes minuti", minutes <= 30)
    }

    @Test
    fun `livello zero covers the skills the project promises`() {
        val skills = curriculum.level(0)!!.modules.flatMap { it.skills }.toSet()
        assertEquals(
            setOf("etica_hacking", "legalita", "triade_cia", "metodo_studio"),
            skills,
        )
    }

    @Test
    fun `lessons and questions can be looked up by id`() {
        val lesson = curriculum.lessons.first()
        assertEquals(lesson, curriculum.lesson(lesson.id))
        assertNotNull(curriculum.moduleOfLesson(lesson.id))

        val question = curriculum.questions.first()
        assertEquals(question, curriculum.question(question.id))
    }

    @Test
    fun `cards stay short enough to read on a phone`() {
        val walls = curriculum.lessons.flatMap { lesson ->
            lesson.cards.filter { it.body.length > 700 }.map { lesson.id }
        }
        assertTrue("Schede troppo lunghe per uno schermo: $walls", walls.isEmpty())
    }
    /**
     * Quattro lezioni non possono avere la stessa identica interrogazione.
     *
     * E' quello che succedeva: l'interrogazione era del *modulo*, e un modulo tiene quattro
     * lezioni. Lo studente leggeva di etica e riceveva le stesse nove domande che aveva gia'
     * risposto dopo le tre lezioni precedenti — e a quel punto la seconda volta e' un ripasso
     * involontario, la terza e' un difetto, la quarta e' una scuola che non ha guardato cosa
     * sta facendo.
     */
    @Test
    fun `ogni lezione ha la sua interrogazione, diversa da quella delle altre`() {
        val ripetute = curriculum.modules.flatMap { modulo ->
            val perLezione = modulo.lessons.associate { lezione ->
                lezione.id to curriculum.questionsAfter(lezione.id).map { it.id }.toSet()
            }
            // Una lezione per modulo puo' essere quella di sintesi: chiude il capitolo su
            // tutto, e li' ripetere e' esattamente il punto. Le altre no.
            val senzaSintesi = perLezione.filterKeys { id ->
                modulo.lessons.first { it.id == id }.skills.size <= 1
            }
            senzaSintesi.entries.flatMap { (id, domande) ->
                senzaSintesi.entries
                    .filter { it.key != id && it.value == domande }
                    .map { "${modulo.id}: ${id} e ${it.key} hanno la stessa interrogazione" }
            }
        }

        assertEquals(emptyList<String>(), ripetute)
    }

    /** E la sintesi puo' essere una sola: due lezioni «di tutto» sono due volte la stessa. */
    @Test
    fun `ogni modulo ha al massimo una lezione di sintesi`() {
        val troppe = curriculum.modules.mapNotNull { modulo ->
            val sintesi = modulo.lessons.filter { it.skills.size > 1 }.map { it.id }
            "${modulo.id}: $sintesi".takeIf { sintesi.size > 1 }
        }

        assertEquals(emptyList<String>(), troppe)
    }

    /** E nessuna lezione puo' finire senza niente da chiedere. */
    @Test
    fun `nessuna lezione resta senza domande`() {
        val mute = curriculum.lessons
            .filter { curriculum.questionsAfter(it.id).isEmpty() }
            .map { it.id }

        assertEquals(emptyList<String>(), mute)
    }

    /**
     * Ogni competenza dichiarata da un modulo deve essere insegnata da almeno una delle sue
     * lezioni, altrimenti l'interrogazione finale chiede cose che nessuno ha spiegato.
     */
    @Test
    fun `le lezioni coprono tutte le competenze del loro modulo`() {
        val scoperte = curriculum.modules.mapNotNull { modulo ->
            val insegnate = modulo.lessons.flatMap { it.skills }.toSet()
            val mancanti = modulo.skills.toSet() - insegnate
            "${modulo.id}: $mancanti".takeIf { mancanti.isNotEmpty() }
        }

        assertEquals(emptyList<String>(), scoperte)
    }

}
