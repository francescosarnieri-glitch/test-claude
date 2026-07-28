package com.cybersensei.academy.ui.quiz

import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.engine.mastery.Confidence
import com.cybersensei.academy.engine.mastery.LevelGate
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lo sblocco dei livelli, misurato invece che sperato.
 *
 * Il cancello chiede l'ottanta per cento di media e nessuna competenza sotto il sessanta, e
 * quei due numeri non sono un'opinione: o sono raggiungibili con le domande che esistono, o
 * il percorso e' chiuso e nessuno se ne accorge finche' uno studente non ci sbatte contro.
 * Questo file fa il conto con il contenuto vero.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class SbloccoLivelliTest {

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

    /** Risponde bene, con certezza dichiarata, a ogni domanda del modulo indicate volte. */
    private fun studiaPerfettamente(moduleId: String, giri: Int) = runBlocking {
        val modulo = curriculum.module(moduleId)!!
        repeat(giri) {
            modulo.questions.forEach { domanda ->
                repository.recordAnswer(
                    skillId = domanda.skill,
                    correct = true,
                    confidence = Confidence.SURE,
                    responseTime = domanda.expectedSeconds.seconds,
                    expectedTime = domanda.expectedSeconds.seconds,
                    misconceptionLabel = null,
                )
            }
        }
    }

    private fun mediaDi(moduleId: String): Double = runBlocking {
        val modulo = curriculum.module(moduleId)!!
        LevelGate.evaluate(modulo.skills, repository.allMastery()).average
    }

    /**
     * Il percorso del giorno zero, quello che uno studente diligente fa davvero: ogni
     * interrogazione di lezione, quella del modulo, e l'esame. Sono due giri completi piu'
     * una domanda per competenza.
     *
     * Il risultato deve avvicinarsi al cancello senza per forza aprirlo — perche' aprirlo in
     * una seduta sola vorrebbe dire certificare una memoria che non e' stata ancora messa
     * alla prova — ma non deve nemmeno restare lontano, o il percorso sembra rotto.
     */
    @Test
    fun `un giorno di studio perfetto porta vicino al cancello, in ogni modulo`() {
        val lontani = curriculum.modules.mapNotNull { modulo ->
            runBlocking { repository.eraseEverything() }
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
            studiaPerfettamente(modulo.id, giri = 2)
            val media = mediaDi(modulo.id)
            "${modulo.id}: ${(media * 100).toInt()}%".takeIf { media < 0.70 }
        }

        assertTrue("Moduli troppo lontani dal cancello dopo una giornata piena: $lontani", lontani.isEmpty())
    }

    /**
     * E con il ripasso del giorno dopo il cancello si apre. Se questo test fallisce il
     * percorso e' chiuso: nessuna quantita' di studio farebbe passare lo studente.
     */
    @Test
    fun `con un giro di ripasso in piu' ogni modulo supera il cancello`() {
        val bloccati = curriculum.modules.mapNotNull { modulo ->
            runBlocking { repository.eraseEverything() }
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
            studiaPerfettamente(modulo.id, giri = 3)
            val esito = runBlocking {
                LevelGate.evaluate(modulo.skills, repository.allMastery())
            }
            "${modulo.id}: ${esito.averagePercent}% deboli=${esito.weakSkills.map { it.skillId }}"
                .takeIf { !esito.passed }
        }

        assertTrue("Moduli che non si aprono nemmeno studiando tutto tre volte: $bloccati", bloccati.isEmpty())
    }

    /**
     * Un livello superato non si richiude.
     *
     * Era il difetto piu' sgradevole di tutti perche' era invisibile: la padronanza decade da
     * sola, il cancello veniva ricalcolato ogni volta sui numeri correnti, e due settimane
     * lontano dall'app bastavano a richiudere un livello gia' guadagnato. Lo studente non
     * avrebbe avuto niente da indicare: nessun errore, nessun messaggio, solo un lucchetto
     * ricomparso.
     */
    @Test
    fun `un livello superato resta superato anche se la padronanza cala`() {
        val livello = curriculum.levels.first { it.modules.isNotEmpty() }
        livello.modules.forEach { studiaPerfettamente(it.id, giri = 4) }

        val superati = runBlocking { repository.passedLevels() }
        assertTrue("Il livello non risulta superato: $superati", livello.level in superati)

        // La padronanza scende sotto il cancello, come farebbe dopo qualche settimana.
        runBlocking {
            livello.modules.flatMap { it.skills }.forEach { skill ->
                repeat(6) {
                    repository.recordAnswer(
                        skillId = skill,
                        correct = false,
                        confidence = Confidence.SURE,
                        responseTime = 30.seconds,
                        expectedTime = 30.seconds,
                        misconceptionLabel = null,
                    )
                }
            }
        }

        val dopo = runBlocking { repository.passedLevels() }
        assertTrue(
            "Il livello si è richiuso: quello che uno studente ha già fatto non si toglie",
            livello.level in dopo,
        )
    }
}
