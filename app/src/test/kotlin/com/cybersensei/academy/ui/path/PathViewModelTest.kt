package com.cybersensei.academy.ui.path

import android.os.Looper
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.scenario.ScenarioLibrary
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Il percorso si apre un passo alla volta.
 *
 * L'Aula proponeva sempre la lezione giusta; il Percorso lasciava scegliere fra centootto in
 * qualsiasi ordine. Due schermate che non vanno d'accordo sull'ordine sono peggio di un ordine
 * sbagliato: chi comincia dal mezzo trova una lezione scritta sopra a quattro che non ha letto,
 * conclude che la scuola e' fatta male, e da dove sta non ha nemmeno torto.
 *
 * Il rischio opposto e' altrettanto vero e piu' difficile da vedere: un lucchetto sbagliato
 * chiude per sempre una lezione che non torna piu', e lo studente non ha modo di accorgersene.
 * Per questo qui si difende sia che sia chiuso quello che deve, sia che si apra tutto.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class PathViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
    @Inject lateinit var library: ScenarioLibrary

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

    private fun viewModel(): PathViewModel = PathViewModel(repository, curriculum, library).also { model ->
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (model.uiState.value.levels.isNotEmpty()) return@also
            Thread.sleep(POLL_MILLIS)
        }
        error("Il percorso non ha finito di caricare")
    }

    /** Segna come lette le lezioni indicate, nell'ordine in cui stanno nel programma. */
    private fun leggi(count: Int) = runBlocking {
        curriculum.level(0)!!.modules.flatMap { module -> module.lessons.map { module to it } }
            .take(count)
            .forEach { (module, lesson) ->
                repository.completeLesson(lesson.id, module.id, lesson.title, lesson.minutes)
            }
    }

    private fun introduzione() = viewModel().uiState.value.levels.first { it.order == 0 }

    /** Il difetto fotografato: quattro lezioni tutte aperte, e l'ordine solo un suggerimento. */
    @Test
    fun `all'inizio e' aperta soltanto la prima lezione`() {
        val livello = introduzione()
        val lezioni = livello.modules.flatMap { it.lessons }

        assertTrue("Il primo modulo deve essere aperto", livello.modules.first().unlocked)
        assertTrue("La prima lezione deve essere aperta", lezioni.first().unlocked)
        assertEquals(
            "Aperte più della prima: ${lezioni.filter { it.unlocked }.map { it.title }}",
            1,
            lezioni.count { it.unlocked },
        )
    }

    /** Leggerla apre la successiva, e non la richiude: rileggere non e' saltare avanti. */
    @Test
    fun `finire una lezione apre la prossima e lascia aperta quella fatta`() {
        leggi(1)
        val lezioni = introduzione().modules.flatMap { it.lessons }

        assertTrue("La lezione letta deve restare aperta", lezioni[0].unlocked)
        assertTrue("La lezione letta va segnata come fatta", lezioni[0].done)
        assertTrue("La seconda deve essersi aperta", lezioni[1].unlocked)
        assertFalse("La terza non deve aprirsi ancora", lezioni[2].unlocked)
    }

    /**
     * L'interrogazione del modulo chiude il modulo. Farla prima di aver letto non misura
     * niente e insegna allo studente che le interrogazioni sono rumore.
     */
    @Test
    fun `l'interrogazione si apre quando il modulo e' stato letto`() {
        val modulo = curriculum.level(0)!!.modules.first()
        assertFalse(
            "L'interrogazione non può essere aperta prima delle lezioni",
            introduzione().modules.first().quizUnlocked,
        )

        leggi(modulo.lessons.size)
        assertTrue(
            "Letto il modulo, l'interrogazione deve aprirsi",
            introduzione().modules.first().quizUnlocked,
        )
    }

    /** E un modulo piu' avanti resta chiuso finche' quello prima non e' finito. */
    @Test
    fun `un modulo successivo resta chiuso finche' il precedente non e' finito`() {
        val moduli = curriculum.levels.first { it.modules.size > 1 }.modules
        val livello = curriculum.levels.first { it.modules.size > 1 }.level
        val stato = viewModel().uiState.value.levels.first { it.order == livello }

        assertTrue("Il modulo dopo il primo doveva essere chiuso", stato.modules.size > 1)
        assertFalse(
            "«${moduli[1].title}» non può essere aperto prima di «${moduli[0].title}»",
            stato.modules[1].unlocked,
        )
    }

    /**
     * Le due schermate non possono litigare: la lezione che l'Aula propone deve essere
     * esattamente quella che il Percorso lascia toccare.
     */
    @Test
    fun `la lezione proposta dall'Aula e' quella aperta nel Percorso`() {
        repeat(3) { fatte ->
            leggi(fatte)
            val lezioni = introduzione().modules.flatMap { it.lessons }
            val prossimaSecondoAula = curriculum.level(0)!!.modules.flatMap { it.lessons }
                .first { it.id !in runBlocking { repository.completedLessonIds() } }

            assertTrue(
                "L'Aula propone «${prossimaSecondoAula.title}», che il Percorso tiene chiusa",
                lezioni.first { it.id == prossimaSecondoAula.id }.unlocked,
            )
        }
    }

    /**
     * E alla fine non deve restare chiuso niente. Un lucchetto che non si apre mai e' il
     * difetto peggiore di tutti: toglie contenuto senza dirlo a nessuno.
     */
    @Test
    fun `letto tutto il livello, non resta chiuso niente`() {
        val livello = curriculum.level(0)!!
        leggi(livello.modules.sumOf { it.lessons.size })
        val stato = introduzione()

        assertTrue(
            "Moduli ancora chiusi: ${stato.modules.filterNot { it.unlocked }.map { it.title }}",
            stato.modules.all { it.unlocked && it.quizUnlocked },
        )
        assertTrue(
            "Lezioni ancora chiuse",
            stato.modules.flatMap { it.lessons }.all { it.unlocked },
        )
        assertTrue("L'esame del livello deve essersi aperto", stato.lessonsFinished)
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
