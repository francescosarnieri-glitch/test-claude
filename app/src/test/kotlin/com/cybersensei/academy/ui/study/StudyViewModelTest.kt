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
import org.junit.Assert.assertNull
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
        // Con l'apertura legata alle interrogazioni, all'inizio le uniche stanze con domande
        // sono quelle che non parlano di materia — la scuola e l'archivio su di te. Vanno
        // cercate in tutto l'albero, non seguendo il primo bivio.
        fun cerca(id: String?): String? {
            id?.let { goTo(it) } ?: goHome()
            if (uiState.value.questions.isNotEmpty()) return uiState.value.trail.lastOrNull()?.id
            uiState.value.places.map { it.id }.forEach { figlio ->
                cerca(figlio)?.let { return it }
                id?.let { goTo(it) } ?: goHome()
            }
            return null
        }
        return checkNotNull(cerca(null)) { "Nessuna stanza con domande" }
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
     * Il difetto che Francesco non riusciva a superare: entrava in una stanza, leggeva
     * «diciassette ancora da sbloccare» e usciva, dieci volte, prima di trovare le quattro
     * stanze che avevano qualcosa. L'albero e' ordinato per argomento, ma quello che e' aperto
     * lo taglia di traverso, quindi la separazione va fatta in cima e una volta sola.
     */
    @Test
    fun `in cima ci sono solo le stanze con qualcosa dentro`() {
        val state = viewModel().uiState.value

        assertTrue("Nessun posto dove andare", state.places.isNotEmpty())
        assertTrue(
            "Una stanza senza niente di aperto non deve comparire: " +
                state.places.filter { it.open == 0 }.map { it.title },
            state.places.all { it.open > 0 },
        )
        assertTrue(
            "Le stanze in cima devono essere meno di tutte quante",
            state.places.size < paths.branches.size,
        )
        assertTrue("Al primo livello non ci sono domande sciolte", state.questions.isEmpty())
    }

    /**
     * E quello che e' chiuso viene detto una volta, in fondo, invece di essere sparso per
     * l'albero: il conto della sezione e quello delle stanze insieme fanno il catalogo intero.
     */
    @Test
    fun `quello che si apre studiando sta tutto in un elenco solo`() {
        val state = viewModel().uiState.value

        assertTrue("L'elenco di cosa si apre studiando non c'è", state.locked.isNotEmpty())
        assertEquals(
            "Il totale dichiarato non corrisponde ai gruppi",
            state.lockedTotal,
            state.locked.sumOf { it.count },
        )
        assertEquals(
            "Aperte più chiuse devono fare il catalogo intero",
            state.corpusSize,
            state.openQuestions + state.lockedTotal,
        )
        assertTrue(
            "Ogni gruppo deve dire cosa lo apre",
            state.locked.all { it.title.isNotBlank() && it.detail.isNotBlank() && it.count > 0 },
        )
        assertEquals("In cima il conto per stanza non va ripetuto", 0, state.ahead)
    }

    /**
     * Due domande in fondo a tre sottostanze si mostrano subito: scendere per trovarle e'
     * cercare, non studiare.
     */
    @Test
    fun `una stanza con poche domande aperte le mostra senza farle cercare`() {
        val model = viewModel()
        model.goTo("famiglia")
        val state = model.uiState.value

        assertTrue("Le poche domande aperte vanno mostrate qui", state.questions.isNotEmpty())
        assertTrue("Con le domande in vista non servono sottostanze", state.places.isEmpty())
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
    /**
     * Chi comincia trova solo le domande sulla scuola: cos'e', come funziona, cosa sa di te.
     * La materia aspetta la sua interrogazione, primo soccorso compreso.
     */
    @Test
    fun `chi comincia puo' chiedere solo della scuola`() {
        val stato = viewModel().uiState.value

        assertTrue("Nessuna domanda aperta all'inizio", stato.openQuestions > 20)
        assertTrue(
            "Non resta abbastanza da aprire: ${stato.openQuestions} su ${stato.corpusSize}",
            stato.openQuestions < stato.corpusSize / 2,
        )
    }

    /**
     * Quello che e' chiuso non si mostra e non si tocca: resta il numero e il modo di aprirlo.
     * Una domanda per cui non sei pronto non e' un'offerta, e metterla sullo schermo sarebbe
     * solo un modo di dire no due volte.
     */
    @Test
    fun `le domande chiuse non compaiono, ma il professore dice quante sono`() {
        val model = viewModel()
        model.goTo("succ_cliccato")
        val stato = model.uiState.value

        assertTrue("Qui non doveva essere aperto niente", stato.questions.isEmpty())
        assertTrue("Il numero di quelle chiuse va detto", stato.ahead > 0)
        assertTrue("E va detto cosa le apre", stato.opensWith.isNotEmpty())
    }

    /** E dopo aver sostenuto l'interrogazione — anche sbagliandola — si aprono. */
    @Test
    fun `sostenere l'interrogazione apre le domande di quella competenza`() {
        val model = viewModel()
        model.goTo("succ_cliccato")
        val chiusePrima = model.uiState.value.ahead

        runBlocking {
            val modulo = curriculum.module("mod_phishing")!!
            modulo.questions.forEach { domanda ->
                repository.recordAnswer(
                    skillId = domanda.skill,
                    correct = false,
                    confidence = com.cybersensei.academy.engine.mastery.Confidence.UNSURE,
                    responseTime = kotlin.time.Duration.parse("30s"),
                    expectedTime = kotlin.time.Duration.parse("30s"),
                    misconceptionLabel = null,
                )
            }
        }

        val dopo = viewModel()
        dopo.goTo("succ_cliccato")
        assertTrue(
            "Dopo l'interrogazione qualcosa doveva aprirsi, anche sbagliandola",
            dopo.uiState.value.questions.isNotEmpty(),
        )
        assertTrue("E le chiuse devono essere meno", dopo.uiState.value.ahead < chiusePrima)
    }

    /**
     * L'annuncio di cio' che si e' aperto.
     *
     * Due difetti possibili e opposti, ed entrambi rovinano la cosa: tacere quando qualcosa
     * si e' aperto la rende un elenco che si allunga da solo, e ripeterlo a ogni apertura la
     * trasforma in un assillo. Al primo giorno non si annuncia niente, perche' non c'e' un
     * «prima» con cui confrontare.
     */
    @Test
    fun `il professore annuncia le domande appena aperte, e una volta sola`() {
        val primo = viewModel()
        assertNull("Al primo giorno non c'e' niente da annunciare", primo.uiState.value.justOpened)
        val apertePrima = primo.uiState.value.openQuestions

        // Non basta piu' leggere la lezione: quello che apre le domande e' aver sostenuto
        // l'interrogazione, superata o no.
        runBlocking {
            curriculum.module("mod_password")!!.questions.forEach { domanda ->
                repository.recordAnswer(
                    skillId = domanda.skill,
                    correct = true,
                    confidence = com.cybersensei.academy.engine.mastery.Confidence.SURE,
                    responseTime = kotlin.time.Duration.parse("30s"),
                    expectedTime = kotlin.time.Duration.parse("30s"),
                    misconceptionLabel = null,
                )
            }
        }

        val dopo = viewModel()
        val notizia = dopo.uiState.value.justOpened
        assertNotNull("Aver studiato deve aprire qualcosa, e va detto", notizia)
        assertTrue(
            "Deve dire quale modulo le ha aperte: «$notizia»",
            notizia!!.contains(moduloTitolo(), ignoreCase = true),
        )
        assertTrue(
            "Le domande aperte devono essere aumentate",
            dopo.uiState.value.openQuestions > apertePrima,
        )

        // Riaprire lo studio senza aver studiato altro non deve ripetere la notizia.
        val terzo = viewModel()
        assertNull("La notizia non va ripetuta a ogni apertura", terzo.uiState.value.justOpened)
    }

    private fun moduloTitolo(): String =
        curriculum.modules.first { it.id == "mod_password" }.title

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
