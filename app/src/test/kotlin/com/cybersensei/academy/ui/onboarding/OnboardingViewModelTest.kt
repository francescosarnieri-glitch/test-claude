package com.cybersensei.academy.ui.onboarding

import com.cybersensei.academy.core.model.LearningGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'iscrizione, che e' la seconda schermata che uno studente vede in vita sua.
 *
 * Qui si difende la data di nascita a larghezza fissa. Due cifre, due cifre, quattro: e' quello
 * che permette al cursore di passare da solo alla casella dopo, e il cursore che passa da solo
 * e' tutto il punto — sei numeri e basta, senza allungare il dito verso il riquadro successivo
 * fra un numero e l'altro. Il prezzo e' che «1» non e' piu' un giorno: si scrive «01», come su
 * qualunque modulo cartaceo esistente.
 */
class OnboardingViewModelTest {

    private fun conData(day: String, month: String, year: String) = OnboardingUiState(
        step = OnboardingStep.BIRTH_DATE,
        day = day,
        month = month,
        year = year,
    )

    @Test
    fun `una cifra sola non basta, ne' per il giorno ne' per il mese`() {
        assertFalse("«1 1 1987» non deve passare", conData("1", "1", "1987").canAdvance)
        assertFalse("Manca una cifra al giorno", conData("1", "01", "1987").canAdvance)
        assertFalse("Manca una cifra al mese", conData("01", "1", "1987").canAdvance)
        assertFalse("L'anno vuole quattro cifre", conData("01", "01", "987").canAdvance)
    }

    @Test
    fun `scritta per intero, la data passa`() {
        val stato = conData("01", "01", "1987")

        assertTrue("«01 01 1987» deve passare", stato.canAdvance)
        assertNull("E non deve lamentarsi di niente", stato.dateError)
        assertEquals(1987, stato.birthDate?.year)
        assertEquals(1, stato.birthDate?.monthValue)
        assertEquals(1, stato.birthDate?.dayOfMonth)
    }

    /**
     * Mentre si scrive non si rimprovera nessuno: l'errore compare quando la data e' completa
     * e sbagliata, non a meta' del secondo numero.
     */
    @Test
    fun `mentre si scrive il professore sta zitto`() {
        assertNull(conData("3", "", "").dateError)
        assertNull(conData("31", "0", "").dateError)
        assertEquals(
            "Una data completa e impossibile va detta",
            "Questa data non esiste. Ricontrolla.",
            conData("31", "02", "1987").dateError,
        )
    }

    /** Le cifre in piu' si buttano invece di far scrivere una data che non esiste. */
    @Test
    fun `una casella non accetta piu' cifre di quante ne vuole`() {
        val viewModel = OnboardingUiState()

        assertEquals(DAY_DIGITS, "0111".filter(Char::isDigit).take(DAY_DIGITS).length)
        assertEquals(YEAR_DIGITS, "19870".filter(Char::isDigit).take(YEAR_DIGITS).length)
        assertEquals("Nessuno stato iniziale strano", "", viewModel.day)
    }

    /** E chi non vuole dirla passa oltre senza dover inventarsi niente. */
    @Test
    fun `si puo' non dire la data`() {
        val stato = OnboardingUiState(step = OnboardingStep.BIRTH_DATE, skipBirthDate = true)

        assertTrue(stato.canAdvance)
        assertNull(stato.dateError)
    }

    /** Il resto dell'intervista non e' stato toccato: un controllo di cortesia. */
    @Test
    fun `il nome vuole almeno due lettere e l'obiettivo va scelto`() {
        assertFalse(OnboardingUiState(step = OnboardingStep.NAME, name = "F").canAdvance)
        assertTrue(OnboardingUiState(step = OnboardingStep.NAME, name = "Francesco").canAdvance)
        assertFalse(OnboardingUiState(step = OnboardingStep.GOAL).canAdvance)
        assertTrue(
            OnboardingUiState(step = OnboardingStep.GOAL, goal = LearningGoal.CURIOSITY).canAdvance,
        )
    }
}
