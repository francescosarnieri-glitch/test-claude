package com.cybersensei.academy

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.scheduler.ReviewScheduler
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.ui.navigation.Routes
import com.cybersensei.academy.ui.quiz.QuizPhase
import com.cybersensei.academy.ui.quiz.QuizViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Il percorso intero, fatto come lo farebbe una persona.
 *
 * Non e' un test di un pezzo: e' l'applicazione vera guidata dall'inizio alla fine — gli
 * stessi ViewModel che stanno dietro allo schermo, lo stesso archivio, lo stesso contenuto.
 * Si iscrive, legge le lezioni, risponde alle interrogazioni una domanda alla volta passando
 * per la dichiarazione di confidenza, sostiene gli esami, aspetta i ripassi e arriva al
 * diploma.
 *
 * Serve a scoprire quello che nessun test di unita' vede: che il percorso *si chiuda*. Un
 * modulo che non porta a niente, un esame che non si puo' sostenere, un livello che non si
 * apre — sono difetti che vivono fra i pezzi, e fra i pezzi non guarda nessuno.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class PercorsoCompletoTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var tutor: TutorEngine
    @Inject lateinit var scheduler: ReviewScheduler
    @Inject lateinit var timeProvider: TimeProvider

    private val diario = StringBuilder()

    @Before
    fun iscrizione() {
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

    private fun quiz(chiave: String, valore: String): QuizViewModel = QuizViewModel(
        repository, curriculum, tutor, scheduler, timeProvider,
        SavedStateHandle(mapOf(chiave to valore)),
    ).also { attendi { it.uiState.value.questions.isNotEmpty() } }

    private fun attendi(condizione: () -> Boolean) {
        val fine = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < fine) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condizione()) return
            Thread.sleep(10)
        }
        error("Condizione mai raggiunta")
    }

    /**
     * Risponde a un'interrogazione intera come farebbe uno studente preparato: sceglie
     * l'opzione giusta, dichiara di esserne sicuro, legge la spiegazione e va avanti.
     * Restituisce quante domande ha ricevuto.
     */
    private fun rispondi(model: QuizViewModel, sbagliaLaPrima: Boolean = false): Int {
        var quante = 0
        while (true) {
            val stato = model.uiState.value
            val domanda = stato.question ?: break
            quante++
            val scelta = if (sbagliaLaPrima && quante == 1) {
                domanda.options.first { !it.correct }
            } else {
                domanda.correctOption
            }
            model.onOptionSelected(scelta.id)
            model.onAnswerConfirmed()
            attendi { model.uiState.value.phase == QuizPhase.DECLARING_CONFIDENCE }
            model.onConfidenceChosen(Confidence.SURE)
            attendi { model.uiState.value.phase == QuizPhase.FEEDBACK }

            val ritorno = model.uiState.value.feedback
            assertTrue(
                "Ogni risposta deve ricevere una spiegazione: ${domanda.id}",
                !ritorno?.explanation.isNullOrBlank(),
            )
            val ultima = model.uiState.value.isLast
            model.onContinue()
            if (ultima) {
                attendi { model.uiState.value.phase == QuizPhase.FINISHED }
                break
            }
            attendi { model.uiState.value.phase == QuizPhase.CHOOSING }
        }
        return quante
    }

    /** Legge tutte le lezioni di un modulo e sostiene l'interrogazione che segue ciascuna. */
    private fun studiaModulo(moduleId: String) {
        val modulo = curriculum.module(moduleId)!!
        modulo.lessons.forEach { lezione ->
            runBlocking {
                repository.startLesson(lezione.id, lezione.title)
                repository.completeLesson(lezione.id, modulo.id, lezione.title, lezione.minutes)
            }
            val n = rispondi(quiz(Routes.ARG_LESSON_ID, lezione.id))
            diario.append("      «${lezione.title}» -> $n domande\n")
        }
        val n = rispondi(quiz(Routes.ARG_MODULE_ID, modulo.id))
        diario.append("      interrogazione del modulo -> $n domande\n")
    }

    @Test
    fun `un percorso intero porta dall'iscrizione al diploma`() {
        val livelli = curriculum.levels.filter { it.modules.isNotEmpty() }

        livelli.forEach { livello ->
            val sbloccati = runBlocking { repository.unlockedLevels() }
            diario.append("\n=== LIVELLO ${livello.level}: ${livello.title}  " +
                "(sbloccato: ${livello.level in sbloccati})\n")
            assertTrue(
                "Livello ${livello.level} mai sbloccato: il percorso e' chiuso qui",
                livello.level in sbloccati,
            )

            livello.modules.forEach { modulo ->
                diario.append("   modulo: ${modulo.title}\n")
                studiaModulo(modulo.id)
            }

            // L'esame di livello, che si sostiene quando le lezioni sono finite.
            val esame = quiz(Routes.ARG_LEVEL, livello.level.toString())
            val n = rispondi(esame)
            attendi { esame.uiState.value.summary != null }
            val esito = esame.uiState.value.summary!!
            diario.append("   ESAME: $n domande, ${esito.solid} solide, ${esito.wrong} errate, " +
                "media ${esito.averageMasteryPercent}%, cancello ${esito.gatePassed}\n")
            assertTrue("L'esame del livello ${livello.level} non ha domande", n > 0)

            // Il ripasso: e' la parte che apre il cancello, e deve esistere davvero.
            val ripasso = runBlocking { repository.dueReviews() }
            diario.append("   ripassi in scadenza subito dopo: ${ripasso.size}\n")

            val passati = runBlocking { repository.passedLevels() }
            diario.append("   livello superato: ${livello.level in passati}\n")
        }

        // Uno studente vero passa dall'Aula in continuazione, ed e' li' che i badge vengono
        // assegnati. Il banco non ci passava mai, e infatti non ne vedeva nessuno: il difetto
        // era nel banco, ma vale la pena tenerlo scritto — i riconoscimenti dipendono dal
        // fatto che una schermata venga aperta, e questo e' un filo sottile.
        val nuovi = runBlocking { repository.awardBadges() }
        diario.append("\n   badge assegnati rientrando in Aula: ${nuovi.size}\n")

        val superati = runBlocking { repository.passedLevels() }
        diario.append("\n=== ESITO FINALE\n")
        diario.append("   livelli superati: ${superati.sorted()}\n")
        diario.append("   badge: ${runBlocking { repository.earnedBadges() }.size}\n")
        val snap = runBlocking { repository.snapshot() }
        diario.append("   padronanza media: ${(snap.masteryAverage * 100).toInt()}%\n")
        diario.append("   minuti studiati: ${snap.totalStudyMinutes}\n")
        println(diario)

        assertTrue(
            "Non tutti i livelli risultano superati dopo aver fatto tutto: $superati",
            livelli.all { it.level in superati },
        )
        val badge = runBlocking { repository.earnedBadges() }
        assertTrue(
            "Chi ha superato tutti i livelli deve avere qualcosa da mostrare: ${badge.size}",
            badge.size >= 5,
        )
    }

    /**
     * La promessa centrale del prodotto, verificata sul percorso vero e non su un caso
     * costruito: **ogni** risposta riceve una spiegazione, giusta o sbagliata che sia.
     */
    @Test
    fun `ogni risposta riceve una spiegazione, anche quando e' sbagliata`() {
        val modulo = curriculum.levels.first { it.modules.isNotEmpty() }.modules.first()
        val lezione = modulo.lessons.first()
        runBlocking { repository.completeLesson(lezione.id, modulo.id, lezione.title, lezione.minutes) }

        val model = quiz(Routes.ARG_LESSON_ID, lezione.id)
        val domanda = model.uiState.value.question!!
        val sbagliata = domanda.options.first { !it.correct }

        model.onOptionSelected(sbagliata.id)
        model.onAnswerConfirmed()
        attendi { model.uiState.value.phase == QuizPhase.DECLARING_CONFIDENCE }
        model.onConfidenceChosen(Confidence.SURE)
        attendi { model.uiState.value.phase == QuizPhase.FEEDBACK }

        val ritorno = model.uiState.value.feedback!!
        assertTrue("Manca la spiegazione", ritorno.explanation.isNotBlank())
        assertTrue(
            "Manca la confutazione dell'errore preciso che ha fatto",
            !sbagliata.rebuttal.isNullOrBlank(),
        )
    }
}
