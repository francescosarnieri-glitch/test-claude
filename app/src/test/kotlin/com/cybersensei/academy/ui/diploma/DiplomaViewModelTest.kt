package com.cybersensei.academy.ui.diploma

import android.os.Looper
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
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
 * A diploma is only worth something if it can be withheld.
 *
 * So what is defended here is the refusal: months of study, a full syllabus of lessons and a
 * long streak still produce nothing until the three exams are passed and the incident has
 * been faced. Everything the certificate prints must be a fact the school actually measured.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class DiplomaViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum
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

    private fun viewModel(): DiplomaViewModel =
        DiplomaViewModel(repository, curriculum, timeProvider).also { it.awaitLoaded() }

    private fun DiplomaViewModel.awaitLoaded() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (uiState.value.loaded) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Il diploma non ha finito di caricare")
    }

    /** Passes every level exam of the syllabus, plus the capstone. */
    private fun graduate(exceptLevel: Int? = null, capstone: Boolean = true) = runBlocking {
        curriculum.levels
            .filter { it.level != exceptLevel && it.level > 0 }
            .forEach { repository.recordExam(it.level, passed = true, scorePercent = 90) }
        if (capstone) repository.completeCase(SchoolRepository.FINAL_CASE_ID, 100)
    }

    @Test
    fun `a new student has no diploma and is told exactly what is missing`() {
        val state = viewModel().uiState.value

        assertNull("Nessun diploma all'iscrizione", state.diploma)
        assertFalse(state.earned)
        assertTrue("Ogni requisito è ancora scoperto", state.requirements.none { it.met })
        assertEquals(
            "Un requisito per livello valutato, più l'Incidente",
            curriculum.levels.count { it.level > 0 } + 1,
            state.requirements.size,
        )
    }

    /**
     * The one that matters: the certificate cannot be reached by persistence. Lessons,
     * minutes and days are printed *on* the diploma, but they never earn it.
     */
    @Test
    fun `finishing every lesson is not enough`() = runBlocking {
        curriculum.modules.forEach { module ->
            module.lessons.forEach { lesson ->
                repository.completeLesson(lesson.id, module.id, lesson.title, lesson.minutes)
            }
        }

        val state = viewModel().uiState.value

        assertNull("Le lezioni non comprano il diploma", state.diploma)
        assertTrue("E il professore lo dice", state.missing.isNotEmpty())
    }

    @Test
    fun `the last exam missing is still no diploma`() {
        val lastLevel = curriculum.levels.last().level
        graduate(exceptLevel = lastLevel)

        val state = viewModel().uiState.value

        assertNull(state.diploma)
        assertEquals("Manca esattamente quel livello", 1, state.missing.size)
    }

    @Test
    fun `passing every exam without facing the incident is still no diploma`() {
        graduate(capstone = false)

        val state = viewModel().uiState.value

        assertNull(state.diploma)
        assertEquals(1, state.missing.size)
        assertTrue(state.missing.single().label.contains("Incidente"))
    }

    /**
     * La trappola aperta dai casi sparsi lungo il programma, chiusa qui.
     *
     * Da quando i casi sono piu' d'uno, «un capstone qualsiasi» non e' piu' una domanda
     * sensata: il dilemma da dodici minuti dell'Introduzione avrebbe spuntato il requisito
     * della notte finale, e l'attestato sarebbe diventato gratis senza che nessuno lo notasse
     * — perche' un requisito che si spunta da solo non assomiglia affatto a un difetto.
     */
    @Test
    fun `un caso qualsiasi non vale come la notte dell'Incidente`() = runBlocking {
        curriculum.levels.filter { it.level > 0 }
            .forEach { repository.recordExam(it.level, passed = true, scorePercent = 90) }
        repository.completeCase("caso_falla", 100)

        val state = viewModel().uiState.value

        assertNull("Il caso dell'Introduzione non è la prova finale", state.diploma)
        assertEquals(1, state.missing.size)
        assertTrue(state.missing.single().label.contains("Incidente"))
    }

    @Test
    fun `three exams and the incident earn it`() {
        graduate()

        val state = viewModel().uiState.value
        val diploma = state.diploma

        assertNotNull("Requisiti soddisfatti, diploma dovuto", diploma)
        assertTrue(state.earned)
        assertEquals("Francesco", diploma!!.studentName)
        assertEquals(timeProvider.today(), diploma.awardedOn)
        assertEquals(curriculum.lessons.size, diploma.lessonsTotal)
    }

    /**
     * Re-sitting an exam badly must not overwrite what the student already proved. The
     * certificate records the best result, not the most recent one.
     */
    @Test
    fun `the certificate keeps the best score of each exam`() = runBlocking {
        graduate()
        val level = curriculum.levels.last().level
        repository.recordExam(level, passed = true, scorePercent = 100)
        repository.recordExam(level, passed = true, scorePercent = 82)

        val scores = viewModel().uiState.value.diploma!!.examScores

        assertTrue("Il punteggio migliore resta", scores.values.contains(100))
        assertFalse("Non quello dell'ultimo tentativo distratto", scores.values.contains(82))
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 10L
    }
}
