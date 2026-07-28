package com.cybersensei.academy

import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.mastery.Confidence
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
 * «Tempo di studio», e perche' restava fermo.
 *
 * Contava solo le lezioni finite, alla durata dichiarata dalla lezione. Chi leggeva le quattro
 * dell'introduzione vedeva 17 minuti — 4+5+4+4 — e da li' non si muoveva piu': interrogazioni,
 * esami, ripassi e casi non valevano un secondo. Un contatore che sta fermo mentre lavori non
 * si legge come una piccola dimenticanza, si legge come un'app che non ti sta guardando; ed era
 * anche l'unico numero della Pagella che poteva mentire senza che nessuno se ne accorgesse.
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

    private fun minutiStudiati(): Int = runBlocking { repository.stats().studiedMinutes }

    private fun rispondi(secondi: Int, volte: Int = 1) = runBlocking {
        val skill = curriculum.levels.first().modules.first().skills.first()
        repeat(volte) {
            repository.recordAnswer(
                skillId = skill,
                correct = true,
                confidence = Confidence.SURE,
                responseTime = secondi.seconds,
                expectedTime = 40.seconds,
            )
        }
    }

    /** Il difetto: rispondere alle domande non contava niente. */
    @Test
    fun `il tempo speso sulle interrogazioni conta`() {
        assertEquals("Si parte da zero", 0, minutiStudiati())

        rispondi(secondi = 30, volte = 10)

        assertEquals("Cinque minuti di risposte sono cinque minuti", 5, minutiStudiati())
    }

    /** Le lezioni continuano a contare come prima: nessuno perde quello che aveva. */
    @Test
    fun `le lezioni contano ancora la loro durata`() = runBlocking {
        val modulo = curriculum.levels.first().modules.first()
        modulo.lessons.forEach { lezione ->
            repository.completeLesson(lezione.id, modulo.id, lezione.title, lezione.minutes)
        }

        assertEquals(modulo.lessons.sumOf { it.minutes }, minutiStudiati())
    }

    /** E le due cose si sommano, che e' il punto: il numero si muove qualunque cosa tu faccia. */
    @Test
    fun `lezioni e risposte si sommano`() {
        val lezione = curriculum.levels.first().modules.first().lessons.first()
        runBlocking {
            repository.completeLesson(lezione.id, "mod_intro", lezione.title, lezione.minutes)
        }
        rispondi(secondi = 60, volte = 3)

        assertEquals(lezione.minutes + 3, minutiStudiati())
    }

    /**
     * Il telefono lasciato acceso non e' studio.
     *
     * Una domanda aperta e abbandonata sul tavolo tornerebbe indietro con ore di «studio» che
     * nessuno ha fatto, e sarebbe il modo piu' silenzioso di rendere finto un numero vero.
     */
    @Test
    fun `una domanda lasciata aperta per ore non regala ore di studio`() {
        rispondi(secondi = 4.minutes.inWholeSeconds.toInt())
        val dopoUnaLunga = minutiStudiati()

        rispondi(secondi = 3.hours())

        assertTrue("Quattro minuti veri vanno contati", dopoUnaLunga >= 4)
        assertTrue(
            "Tre ore su una domanda sola non possono valere tre ore: ${minutiStudiati()}",
            minutiStudiati() - dopoUnaLunga <= 5,
        )
    }

    private fun Int.hours(): Int = this * 3600
}
