package com.cybersensei.academy.ui.study

import android.os.Looper
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.StudyAvailability
import com.cybersensei.academy.engine.nlu.StudyPaths
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
 * Lo studio non interpreta piu' niente.
 *
 * Per mesi la promessa era «il professore capisce come parli tu», e per mesi il difetto e'
 * stato lo stesso: capiva quasi sempre, e il quasi rendeva inutili anche le volte giuste,
 * perche' lo studente non aveva modo di distinguerle. Qui si difende la promessa nuova, piu'
 * piccola e mantenibile: lo studente sceglie, e la risposta che arriva e' quella scritta per
 * quella domanda. Non esiste un caso in cui possa arrivare la risposta sbagliata.
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
    @Inject lateinit var paths: StudyPaths
    @Inject lateinit var availability: StudyAvailability
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
        StudyViewModel(repository, curriculum, knowledgeBase, paths, availability, tutor, facts)
            .also { model -> model.awaitLoaded() }

    /**
     * Il caricamento iniziale rimbalza fra il thread principale e quelli di Room, quindi un
     * solo giro del looper lo prende soltanto per fortuna. Un test che passa quando i tempi
     * vanno bene non e' un test.
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

    /**
     * Scende fino alla prima stanza che contiene domande.
     *
     * L'albero ha due livelli dove serve — le emergenze sono sei situazioni diverse, non un
     * elenco unico — quindi un test che si ferma al primo bivio non trova niente da chiedere.
     */
    private fun StudyViewModel.entraFinoAlleDomande(): String {
        var giri = 0
        while (uiState.value.questions.isEmpty() && giri++ < 5) {
            val prossima = uiState.value.places.firstOrNull() ?: break
            goTo(prossima.id)
        }
        check(uiState.value.questions.isNotEmpty()) { "Nessuna stanza con domande" }
        return uiState.value.trail.last().id
    }

    /** Tocca la voce indicata, ovunque stia nell'albero, e aspetta la risposta. */
    private fun StudyViewModel.tocca(entryId: String): Exchange {
        val entry = knowledgeBase.entries.first { it.id == entryId }
        val prima = uiState.value.exchanges.size
        askSuggestion(Suggestion(entry.id, entry.question, entry.level))
        return attendiRisposta(prima)
    }

    private fun StudyViewModel.attendiRisposta(quantePrima: Int): Exchange {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (uiState.value.exchanges.size > quantePrima) return uiState.value.exchanges.first()
            Thread.sleep(POLL_MILLIS)
        }
        error("Nessuna risposta arrivata")
    }

    @Test
    fun `il professore saluta quando si apre lo studio`() {
        val state = viewModel().uiState.value
        assertTrue("Il professore non può restare muto", state.openingLine.isNotBlank())
        assertEquals(knowledgeBase.entries.size, state.corpusSize)
    }

    // --- muoversi -----------------------------------------------------------------------

    /**
     * Si comincia dalle situazioni e non dall'indice: chi ha appena cliccato su un link non
     * conosce la parola «phishing», e chiedergliela prima di aiutarlo era il difetto da
     * togliere.
     */
    @Test
    fun `lo studio si apre sui posti dove il professore puo' portarti`() {
        val state = viewModel().uiState.value

        assertTrue("Nessun posto dove andare", state.places.isNotEmpty())
        assertEquals("Mi è successo qualcosa", state.places.first().title)
        assertTrue("Al primo livello non ci sono domande sciolte", state.questions.isEmpty())
    }

    @Test
    fun `entrare in una stanza mostra le sue domande, e si torna indietro`() {
        val model = viewModel()
        val primo = model.uiState.value.places.first()

        model.goTo(primo.id)
        assertEquals(primo.title, model.uiState.value.trail.last().title)
        assertNotNull("Il professore deve dire qualcosa arrivando", model.uiState.value.line)

        model.entraFinoAlleDomande()
        assertTrue("La stanza deve avere domande", model.uiState.value.questions.isNotEmpty())

        repeat(model.uiState.value.trail.size) { model.goBack() }
        assertTrue("Si deve poter tornare in cima", model.uiState.value.trail.isEmpty())
    }

    /** Il difetto fotografato: la domanda toccata restava li'. */
    @Test
    fun `una domanda gia' chiesta sparisce dall'elenco`() {
        val model = viewModel()
        model.entraFinoAlleDomande()
        val domanda = model.uiState.value.questions.first()

        model.askSuggestion(domanda)
        model.attendiRisposta(0)

        assertFalse(
            "«${domanda.text}» e' rimasta nell'elenco dopo essere stata chiesta",
            domanda.id in model.uiState.value.questions.map { it.id },
        )
    }

    /** Le domande tornano al loro posto quando si pulisce: il catalogo non si consuma. */
    @Test
    fun `pulire rimette le domande al loro posto`() {
        val model = viewModel()
        val stanza = model.entraFinoAlleDomande()
        val quante = model.uiState.value.questions.size
        model.askSuggestion(model.uiState.value.questions.first())
        model.attendiRisposta(0)

        model.clearHistory()
        model.awaitLoaded()
        model.goTo(stanza)

        assertEquals(quante, model.uiState.value.questions.size)
    }

    // --- rispondere ---------------------------------------------------------------------

    @Test
    fun `una domanda toccata riceve la risposta scritta per lei`() {
        val exchange = viewModel().tocca("faq_password_sicura")

        assertTrue(exchange.understood)
        assertEquals("faq_password_sicura", exchange.entryId)
        assertEquals(
            knowledgeBase.entries.first { it.id == "faq_password_sicura" }.answer,
            exchange.answer,
        )
    }

    /**
     * Il programma copre tutti i livelli, ma uno studente all'introduzione non ha ancora
     * studiato il livello difficile. La risposta la riceve — rifiutarla sarebbe pedanteria —
     * con l'argomento messo dove sta.
     */
    @Test
    fun `un argomento di un livello non ancora sbloccato viene segnalato`() {
        val exchange = viewModel().tocca("faq_zero_trust")

        assertTrue(exchange.understood)
        assertNotNull("Va detto che l'argomento arriva più avanti", exchange.aheadOfLevel)
    }

    @Test
    fun `la risposta piu' recente sta in cima`() {
        val model = viewModel()
        model.tocca("faq_white_hacking")
        model.tocca("faq_password_sicura")

        val exchanges = model.uiState.value.exchanges
        assertEquals(2, exchanges.size)
        assertEquals("faq_password_sicura", exchanges.first().entryId)
    }

    /**
     * Il catalogo cresce studiando, e chi comincia non deve trovarsi davanti duecento domande
     * di cui non gli importa niente — ne' una schermata vuota.
     */
    @Test
    fun `chi comincia trova le emergenze aperte e il resto ancora chiuso`() {
        val model = viewModel()
        val stato = model.uiState.value

        assertTrue("Nessuna domanda aperta al primo giorno", stato.openQuestions > 50)
        assertTrue(
            "Non resta niente da sbloccare: ${stato.openQuestions} su ${stato.corpusSize}",
            stato.openQuestions < stato.corpusSize,
        )

        model.goTo("succ_cliccato")
        assertTrue("Le emergenze devono essere aperte subito", model.uiState.value.ahead.isEmpty())
        assertTrue(model.uiState.value.questions.isNotEmpty())
    }

    /** Quello che e' ancora avanti si piega, non si toglie: chi lo cerca lo trova. */
    @Test
    fun `le domande ancora chiuse restano raggiungibili e dichiarate`() {
        val model = viewModel()
        model.goTo("cap_zoo")
        val stato = model.uiState.value

        assertTrue("Qui doveva esserci qualcosa da sbloccare", stato.ahead.isNotEmpty())
        assertTrue("Va detto quale modulo le apre", stato.opensWith.isNotEmpty())

        model.toggleAhead()
        assertTrue(model.uiState.value.showingAhead)

        val exchange = model.tocca(stato.ahead.first().id)
        assertNotNull("Va detto che sta correndo avanti", exchange.aheadOfLevel)
        assertTrue("La risposta arriva lo stesso", exchange.answer.isNotBlank())
    }

    @Test
    fun `c'e' sempre qualcosa da dire sullo studente`() {
        val notes = viewModel().uiState.value.notes
        assertTrue("Anche «non ho ancora osservazioni» è un'osservazione", notes.isNotEmpty())
    }

    // --- quello che la scuola sa di te ---------------------------------------------------

    /**
     * Il caso che ha fatto nascere lo Studio: l'app conosceva la data dell'iscrizione e
     * rispondeva con il depliant delle quattro sezioni.
     */
    @Test
    fun `una domanda sui fatti dello studente riceve i dati dello studente`() {
        val risposta = viewModel().tocca("fatto_iscrizione").answer

        assertTrue(
            "Deve citare la data d'iscrizione: «$risposta»",
            risposta.contains("gennaio") && risposta.contains("2026"),
        )
    }

    @Test
    fun `il professore sa come si chiama lo studente`() {
        val risposta = viewModel().tocca("fatto_nome").answer

        assertTrue("Deve dire il nome: «$risposta»", risposta.contains("Francesco"))
    }

    /**
     * Un archivio vuoto non deve produrre una frase con un buco dentro: la voce dichiara
     * cosa dire quando non c'e' ancora niente da dire.
     */
    @Test
    fun `senza niente da misurare il professore lo dice invece di lasciare un vuoto`() {
        val risposta = viewModel().tocca("fatto_a_che_punto").answer

        assertFalse("Nessun segnaposto deve arrivare allo studente: «$risposta»", risposta.contains("{"))
        assertTrue("Deve dire qualcosa: «$risposta»", risposta.isNotBlank())
    }

    /**
     * Nessun segnaposto deve mai raggiungere lo studente.
     *
     * Un {nome} scritto male nel contenuto non fa fallire niente: fa arrivare una parentesi
     * graffa in mezzo a una frase, oppure — peggio — fa scattare per sempre la versione
     * "non ho ancora dati" anche a chi i dati ce li ha.
     */
    @Test
    fun `nessuna risposta dinamica arriva con un segnaposto dentro`() {
        val model = viewModel()

        val rotte = knowledgeBase.templateEntries.mapNotNull { entry ->
            val risposta = model.tocca(entry.id).answer
            if (risposta.contains('{') || risposta.isBlank()) "${entry.id}: «$risposta»" else null
        }

        assertTrue("Voci dinamiche mal formate:\n${rotte.joinToString("\n")}", rotte.isEmpty())
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
