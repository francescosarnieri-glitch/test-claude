package com.cybersensei.academy.ui.study

import android.os.Looper
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.QuestionAnswerer
import com.cybersensei.academy.engine.tutor.TutorEngine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The study's promise is narrow and absolute: the professor answers what he knows, admits
 * what he does not, and never leaves the student staring at nothing.
 *
 * A student who asks a security question and gets an invented answer is worse off than one
 * who gets none — so "non lo so" being reachable is a feature under test, not an edge case.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class StudyViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var knowledgeBase: KnowledgeBase
    @Inject lateinit var answerer: QuestionAnswerer
    @Inject lateinit var tutor: TutorEngine
    @Inject lateinit var facts: SchoolFacts

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            repository.saveProfile(
                StudentProfile(
                    name = "Francesco",
                    birthDate = LocalDate.of(1990, 11, 3),
                    enrolledOn = LocalDate.of(2026, 1, 10),
                    ethicalPactSigned = true,
                ),
            )
        }
    }

    private fun viewModel(): StudyViewModel =
        StudyViewModel(repository, curriculum, knowledgeBase, answerer, tutor, facts)
            .also { model -> model.awaitLoaded() }

    /**
     * The initial load bounces between the main dispatcher and Room's own threads, so a
     * single idle of the looper catches it only by luck. Pump until the state is actually
     * there — a test that passes when the timing happens to work is not a test.
     */
    private fun StudyViewModel.awaitLoaded() {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (uiState.value.openingLine.isNotBlank()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Lo studio non ha finito di caricare entro ${LOAD_TIMEOUT_MILLIS}ms")
    }

    @Test
    fun `the professor greets the student when the study opens`() {
        val state = viewModel().uiState.value
        assertTrue("Il professore non può restare muto", state.openingLine.isNotBlank())
        assertEquals(knowledgeBase.entries.size, state.corpusSize)
    }

    @Test
    fun `a known question is answered, and the topic understood is shown`() {
        val model = viewModel()
        model.ask("come faccio una password sicura")
        val exchange = model.uiState.value.exchanges.first()

        assertTrue(exchange.understood)
        assertTrue("La risposta non può essere vuota", exchange.answer.isNotBlank())
        assertNotNull(
            "Lo studente deve poter vedere su cosa gli è stato risposto",
            exchange.answeredTopic,
        )
    }

    @Test
    fun `a question outside the syllabus is admitted instead of invented`() {
        val model = viewModel()
        model.ask("qual è la ricetta della carbonara")
        val exchange = model.uiState.value.exchanges.first()

        // The refusal is now written down rather than improvised, so this arrives as an
        // answer — but it has to *be* a refusal, and it has to name the reason.
        assertTrue("Nemmeno un rifiuto può essere silenzio", exchange.answer.isNotBlank())
        assertTrue(
            "Il rifiuto deve dire che non è materia sua: «${exchange.answer}»",
            exchange.answer.contains("materia", ignoreCase = true) ||
                exchange.answer.contains("non lo so", ignoreCase = true),
        )
    }

    /**
     * The FAQ answers the whole syllabus, but a student on the introduction has not studied
     * the hard level yet. He gets the answer — refusing it would be pedantry — with the
     * topic placed where it belongs.
     */
    @Test
    fun `a topic from a level not yet unlocked is answered but flagged`() {
        val model = viewModel()
        model.ask("cosa vuol dire zero trust")
        val exchange = model.uiState.value.exchanges.first()

        assertTrue(exchange.understood)
        assertNotNull("Va detto che l'argomento arriva più avanti", exchange.aheadOfLevel)
    }

    @Test
    fun `the suggested questions stay within what the student has unlocked`() {
        val unlocked = runBlocking { repository.unlockedLevels() }
        val suggestions = viewModel().uiState.value.suggestions

        assertTrue("Lo studente deve avere da dove partire", suggestions.isNotEmpty())
        val ahead = suggestions.filterNot { it.level in unlocked }
        assertTrue("Suggerimenti oltre il livello sbloccato: $ahead", ahead.isEmpty())
    }

    @Test
    fun `the newest answer is the one on top`() {
        val model = viewModel()
        model.ask("che cos'è il white hacking")
        model.ask("come faccio una password sicura")

        val exchanges = model.uiState.value.exchanges
        assertEquals(2, exchanges.size)
        assertEquals("come faccio una password sicura", exchanges.first().question)
    }

    @Test
    fun `an empty question is not sent to the professor`() {
        val model = viewModel()
        model.ask("   ")
        assertTrue(model.uiState.value.exchanges.isEmpty())
    }

    @Test
    fun `tapping a suggestion asks the question it names`() {
        val model = viewModel()
        val suggestion = model.uiState.value.suggestions.first()
        model.askSuggestion(suggestion)

        val exchange = model.uiState.value.exchanges.first()
        assertTrue("Un suggerimento deve trovare la propria risposta", exchange.understood)
        assertEquals(suggestion.text, exchange.answeredTopic)
    }

    @Test
    fun `there is always something to say about the student`() {
        val notes = viewModel().uiState.value.notes
        assertTrue("Anche «non ho ancora osservazioni» è un'osservazione", notes.isNotEmpty())
    }


    // --- Quello che il professore ricorda, e quello che sa di te -------------------------

    /** L'attesa serve perche' ora la risposta si compone leggendo l'archivio. */
    private fun StudyViewModel.chiedi(domanda: String): Exchange {
        val prima = uiState.value.exchanges.size
        ask(domanda)
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (uiState.value.exchanges.size > prima) return uiState.value.exchanges.first()
            Thread.sleep(POLL_MILLIS)
        }
        error("Nessuna risposta a «$domanda»")
    }

    /**
     * Il caso che ha fatto nascere tutto questo: l'app conosceva la data dell'iscrizione e
     * rispondeva con il depliant delle quattro sezioni.
     */
    @Test
    fun `una domanda sui fatti dello studente riceve i dati dello studente`() {
        val model = viewModel()

        val risposta = model.chiedi("quando e' stata installata questa applicazione").answer

        assertTrue(
            "Deve citare la data d'iscrizione, non spiegare com'e' fatta l'app: «$risposta»",
            risposta.contains("gennaio") && risposta.contains("2026"),
        )
    }

    @Test
    fun `il professore sa come si chiama lo studente`() {
        val risposta = viewModel().chiedi("ti ricordi il mio nome").answer

        assertTrue("Deve dire il nome: «$risposta»", risposta.contains("Francesco"))
    }

    /**
     * Un archivio vuoto non deve produrre una frase con un buco dentro: la voce dichiara
     * cosa dire quando non c'e' ancora niente da dire.
     */
    @Test
    fun `senza niente da misurare il professore lo dice invece di lasciare un vuoto`() {
        val risposta = viewModel().chiedi("a che punto sono").answer

        assertFalse("Nessun segnaposto deve arrivare allo studente: «$risposta»", risposta.contains("{"))
        assertTrue("Deve ammettere che non ha ancora misurato: «$risposta»", risposta.isNotBlank())
    }

    @Test
    fun `il professore elenca le domande che gli sono state fatte`() {
        val model = viewModel()
        model.chiedi("cos'e' il phishing")
        model.chiedi("cos'e' il ransomware")

        val risposta = model.chiedi("quali domande ti ho fatto finora").answer

        assertTrue("Deve ricordare la prima: «$risposta»", risposta.contains("phishing"))
        assertTrue("E anche la seconda: «$risposta»", risposta.contains("ransomware"))
    }

    @Test
    fun `ripeti restituisce l'ultima risposta davvero data`() {
        val model = viewModel()
        val prima = model.chiedi("cos'e' il phishing").answer

        val ripetuta = model.chiedi("ripeti").answer

        assertTrue("Deve contenere la risposta di prima", ripetuta.contains(prima))
    }

    /** La memoria e' della sessione: chiuderla la cancella davvero. */
    @Test
    fun `pulire la conversazione fa dimenticare anche le domande`() {
        val model = viewModel()
        model.chiedi("cos'e' il phishing")
        model.clearHistory()
        model.awaitLoaded()

        val risposta = model.chiedi("quali domande ti ho fatto finora").answer

        assertFalse("Non deve ricordare niente di prima: «$risposta»", risposta.contains("phishing"))
    }


    /**
     * Nessun segnaposto deve mai raggiungere lo studente.
     *
     * Un {nome} scritto male nel contenuto non fa fallire niente: fa arrivare una parentesi
     * graffa in mezzo a una frase, oppure — peggio — fa scattare per sempre la versione
     * "non ho ancora dati" anche a chi i dati ce li ha. Questo test chiede al professore
     * ogni voce dinamica che esiste e guarda cosa esce.
     */
    @Test
    fun `nessuna risposta dinamica arriva con un segnaposto dentro`() {
        val model = viewModel()

        val rotte = knowledgeBase.templateEntries.mapNotNull { entry ->
            val risposta = model.chiedi(entry.question).answer
            if (risposta.contains('{') || risposta.isBlank()) "${entry.id}: «$risposta»" else null
        }

        assertTrue("Voci dinamiche mal formate:\n${rotte.joinToString("\n")}", rotte.isEmpty())
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
