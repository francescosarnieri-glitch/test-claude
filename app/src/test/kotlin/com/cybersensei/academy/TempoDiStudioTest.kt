package com.cybersensei.academy

import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «Tempo passato a scuola», e perche' prima era una bugia.
 *
 * Contava i minuti *dichiarati* dalle lezioni: quattro schede lette in venti secondi valevano
 * quattro minuti. Era un numero plausibile e falso, e stava in Pagella accanto a sei numeri
 * veri — che e' il posto peggiore dove mettere un numero falso, perche' si prende in prestito
 * la credibilita' degli altri.
 *
 * Adesso e' un cronometro, e misura l'unica cosa che puo' misurare onestamente: il tempo con
 * l'app davanti. Non dice di misurare lo studio — nessuno puo' saperlo da fuori — ed e' per
 * questo che come si chiama conta quanto come si conta.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class TempoDiStudioTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum

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

    /** Un orologio che si puo' spostare a mano: e' l'unico modo di provare un cronometro. */
    private class OrologioFinto(private var adesso: Instant) : TimeProvider {
        override fun now(): Instant = adesso
        override fun zone(): ZoneId = ZoneId.of("Europe/Rome")
        fun avanza(secondi: Long) {
            adesso = adesso.plusSeconds(secondi)
        }
    }

    private fun secondiRegistrati(): Long = runBlocking { repository.timeAtSchoolSeconds() }

    /** Il difetto: leggere in fretta valeva quanto leggere piano. */
    @Test
    fun `finire una lezione non regala i minuti che la lezione dichiara`() = runBlocking {
        val modulo = curriculum.levels.first().modules.first()
        val lezione = modulo.lessons.first()

        repository.completeLesson(lezione.id, modulo.id, lezione.title, lezione.minutes)

        assertEquals(
            "La durata dichiarata non e' tempo passato: va misurata, non dedotta",
            0L,
            secondiRegistrati(),
        )
    }

    @Test
    fun `il cronometro conta il tempo vero passato davanti all'app`() {
        val orologio = OrologioFinto(Instant.parse("2026-07-29T09:00:00Z"))
        val clock = SchoolClock(orologio)

        clock.enter(alreadyCounted = 0)
        orologio.avanza(90)

        assertEquals(90L, clock.totalSeconds)
        assertTrue("La sessione e' aperta", clock.running)
    }

    /** Riaprire l'app riprende da dov'era, non da zero. */
    @Test
    fun `una seconda sessione si somma alla prima`() {
        val orologio = OrologioFinto(Instant.parse("2026-07-29T09:00:00Z"))
        val clock = SchoolClock(orologio)

        clock.enter(alreadyCounted = 0)
        orologio.avanza(120)
        val dopoLaPrima = clock.leave()

        orologio.avanza(Duration.ofHours(3).seconds)
        clock.enter(alreadyCounted = dopoLaPrima)
        orologio.avanza(30)

        assertEquals("Le tre ore fuori dall'app non contano", 150L, clock.totalSeconds)
    }

    /** E il totale scritto non torna mai indietro, qualunque ordine abbiano le scritture. */
    @Test
    fun `il totale registrato non arretra mai`() = runBlocking {
        repository.setTimeAtSchool(600)
        repository.setTimeAtSchool(120)

        assertEquals("Una scrittura tardiva non puo' cancellare tempo vero", 600L, secondiRegistrati())
    }

    /**
     * Come si legge. Deve restare corto: sta all'estremita' destra di una riga e non puo'
     * spingere fuori schermo l'etichetta che dice cosa sta contando.
     */
    @Test
    fun `il cronometro si legge come un cronometro e resta corto`() {
        assertEquals("0:00", formatTimeAtSchool(0))
        assertEquals("0:07", formatTimeAtSchool(7))
        assertEquals("12:34", formatTimeAtSchool(12 * 60 + 34))
        assertEquals("1:03:45", formatTimeAtSchool(3600 + 3 * 60 + 45))
        assertEquals("16:40:00", formatTimeAtSchool(1000 * 60))
        assertEquals("Un tempo negativo non esiste", "0:00", formatTimeAtSchool(-5))

        assertTrue(
            "Cento ore devono ancora starci: ${formatTimeAtSchool(100 * 3600)}",
            formatTimeAtSchool(100 * 3600).length <= 9,
        )
    }
}
