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
import com.cybersensei.academy.ui.lesson.LessonViewModel
import com.cybersensei.academy.ui.navigation.Routes
import com.cybersensei.academy.ui.quiz.QuizPhase
import com.cybersensei.academy.ui.quiz.QuizViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Leggere non e' fare, e la strada percorsa a mano da chi l'ha scoperto.
 *
 * Francesco ha aperto «Chi e' davvero un hacker», ha letto le quattro schede, e alla fine ha
 * scelto «Torno in aula» invece di «Mettimi alla prova». Il patto etica e legge si e' sbloccato
 * lo stesso. Ripetuta lezione per lezione, quella scorciatoia porta uno studente in fondo a
 * tutti e quattro i livelli senza che la scuola gli abbia mai chiesto niente — e la scuola,
 * intanto, continua a segnare progressi che non misurano nulla.
 *
 * Qui il difetto viene rifatto per intero attraverso le schermate vere: si legge, si esce, si
 * controlla che niente si sia aperto; poi si fa l'interrogazione e si controlla che si apra.
 * Un test sul repository non sarebbe bastato — il difetto stava nel punto in cui una schermata
 * decideva per conto suo che la lezione era finita.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class LeggereNonEFareTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var tutor: TutorEngine
    @Inject lateinit var scheduler: ReviewScheduler
    @Inject lateinit var timeProvider: TimeProvider

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

    private fun attendi(condizione: () -> Boolean) {
        val fine = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < fine) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condizione()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Condizione mai raggiunta")
    }

    private val primaLezione get() = curriculum.level(0)!!.modules.first().lessons.first()
    private val secondaLezione get() = curriculum.level(0)!!.modules.first().lessons[1]

    /** Apre la lezione e passa tutte le schede fino all'ultima, come chi legge davvero. */
    private fun leggiFinoInFondo(lessonId: String): LessonViewModel {
        val model = LessonViewModel(
            repository,
            curriculum,
            tutor,
            SavedStateHandle(mapOf(Routes.ARG_LESSON_ID to lessonId)),
        )
        attendi { model.uiState.value.lesson != null }
        // Una scheda alla volta e poi si aspetta: chiamare nextCard in un ciclo stretto
        // rilancia «finish» a ogni giro e affoga il looper, che in Robolectric e' uno solo.
        repeat(model.uiState.value.cardCount) {
            if (!model.uiState.value.completed) {
                model.nextCard()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        attendi { model.uiState.value.completed }
        return model
    }

    private fun rispondiATutto(lessonId: String) {
        val model = QuizViewModel(
            repository, curriculum, tutor, scheduler, timeProvider,
            SavedStateHandle(mapOf(Routes.ARG_LESSON_ID to lessonId)),
        )
        attendi { model.uiState.value.questions.isNotEmpty() }
        while (true) {
            val domanda = model.uiState.value.question ?: break
            model.onOptionSelected(domanda.correctOption.id)
            model.onAnswerConfirmed()
            attendi { model.uiState.value.phase == QuizPhase.DECLARING_CONFIDENCE }
            model.onConfidenceChosen(Confidence.SURE)
            attendi { model.uiState.value.phase == QuizPhase.FEEDBACK }

            val ultima = model.uiState.value.isLast
            model.onContinue()
            if (ultima) {
                attendi { model.uiState.value.phase == QuizPhase.FINISHED }
                break
            }
            attendi { model.uiState.value.phase == QuizPhase.CHOOSING }
        }
    }

    private fun fatte(): Set<String> = runBlocking { repository.completedLessonIds() }

    /** Il difetto, rifatto tale e quale. */
    @Test
    fun `leggere tutte le schede e uscire non chiude la lezione`() {
        leggiFinoInFondo(primaLezione.id)

        assertFalse(
            "«${primaLezione.title}» risulta fatta senza aver sostenuto l'interrogazione",
            primaLezione.id in fatte(),
        )
        assertTrue(
            "Ma la lettura va scritta da qualche parte, o sembra che l'app l'abbia persa",
            primaLezione.id in runBlocking { repository.readLessonIds() },
        )
    }

    /** E l'Aula continua a proporre quella lezione, invece di mandarti avanti. */
    @Test
    fun `dopo aver solo letto, la prossima lezione resta la stessa`() {
        leggiFinoInFondo(primaLezione.id)

        val prossima = curriculum.level(0)!!.modules.flatMap { it.lessons }
            .first { it.id !in fatte() }

        assertTrue(
            "L'Aula manda avanti chi non ha fatto l'interrogazione: propone «${prossima.title}»",
            prossima.id == primaLezione.id,
        )
    }

    /** Fatta l'interrogazione, la lezione si chiude e il percorso avanza. */
    @Test
    fun `l'interrogazione chiude la lezione`() {
        leggiFinoInFondo(primaLezione.id)
        rispondiATutto(primaLezione.id)

        assertTrue("Adesso deve risultare fatta", primaLezione.id in fatte())
        assertTrue(
            "E la prossima deve essere la seconda",
            curriculum.level(0)!!.modules.flatMap { it.lessons }
                .first { it.id !in fatte() }.id == secondaLezione.id,
        )
    }

    /**
     * Rifare una lezione non gonfia il tempo di studio.
     *
     * Prima i minuti si sommavano a ogni rilettura, e il tempo di studio e' un numero che allo
     * studente viene chiesto di guardare con soddisfazione: un numero cosi' o e' vero o e'
     * meglio non mostrarlo.
     */
    @Test
    fun `rifare una lezione non regala minuti`() {
        leggiFinoInFondo(primaLezione.id)
        rispondiATutto(primaLezione.id)
        val dopoLaPrimaVolta = runBlocking { repository.stats().totalStudyMinutes }

        leggiFinoInFondo(primaLezione.id)
        rispondiATutto(primaLezione.id)

        assertTrue(
            "I minuti delle lezioni sono raddoppiati rifacendo la stessa lezione",
            runBlocking { repository.stats().totalStudyMinutes } == dopoLaPrimaVolta,
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 10L
    }
}
