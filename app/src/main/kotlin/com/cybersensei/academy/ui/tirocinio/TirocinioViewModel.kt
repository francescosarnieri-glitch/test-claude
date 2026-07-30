package com.cybersensei.academy.ui.tirocinio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.regole.Analizzatore
import com.cybersensei.academy.engine.regole.ErroreDiRegola
import com.cybersensei.academy.engine.regole.Esercizio
import com.cybersensei.academy.engine.regole.Esito
import com.cybersensei.academy.engine.regole.Giudizio
import com.cybersensei.academy.engine.regole.Lettura
import com.cybersensei.academy.engine.regole.LogEvent
import com.cybersensei.academy.engine.regole.LogSource
import com.cybersensei.academy.engine.regole.MotoreRegole
import com.cybersensei.academy.engine.regole.Palestra
import com.cybersensei.academy.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How one line of the log is doing after a run. */
enum class StatoRiga {
    /** Not caught, and not part of the attack: as it should be. */
    IGNORATA,

    /** Attack, caught. */
    PRESA,

    /** Attack, missed — the expensive one. */
    PERSA,

    /** Ordinary traffic the rule caught by mistake. */
    FALSO,
}

data class RigaLog(
    val evento: LogEvent,
    val testo: String,
    val stato: StatoRiga,
)

/**
 * Something to tap instead of typing.
 *
 * A rule is written on a phone keyboard, where every symbol costs three taps and a typo costs
 * a run. The vocabulary here is small and closed — six fields, five operators, the values that
 * are actually in the log — so offering it is not a crutch: it is the difference between
 * fighting the keyboard and thinking about the problem.
 */
data class Pastiglia(val testo: String, val gruppo: String)

data class TirocinioUiState(
    val caricato: Boolean = false,
    val esercizio: Esercizio? = null,
    val registro: LogSource? = null,
    val righe: List<RigaLog> = emptyList(),
    val pastiglie: List<Pastiglia> = emptyList(),
    val testoRegola: String = "",
    val errore: ErroreDiRegola? = null,
    val esito: Esito? = null,
    val giudizio: Giudizio? = null,
    val risolto: Boolean = false,
    /** True once solved, or once the student has asked to see the answer. */
    val soluzioneVisibile: Boolean = false,
    val indizioVisibile: Boolean = false,
    /** Il promemoria del linguaggio, aperto e chiuso a piacere. */
    val promemoriaAperto: Boolean = false,
    val tentativi: Int = 0,
    val problema: String? = null,
)

/**
 * One rule-writing exercise.
 *
 * The student types, the engine runs, and the log lights up line by line. Nothing here is
 * scored on a guess: what the rule caught, what it missed and what it dragged in are facts
 * computed from the same log the student is looking at.
 */
@HiltViewModel
class TirocinioViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val palestra: Palestra,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val esercizioId: String = checkNotNull(savedStateHandle[Routes.ARG_EXERCISE_ID])

    private val _uiState = MutableStateFlow(TirocinioUiState())
    val uiState: StateFlow<TirocinioUiState> = _uiState.asStateFlow()

    init {
        val esercizio = palestra.esercizio(esercizioId)
        val registro = esercizio?.let { palestra.registroDi(it) }
        if (esercizio == null || registro == null) {
            _uiState.value = TirocinioUiState(
                caricato = true,
                problema = "Non riesco ad aprire questo esercizio.",
            )
        } else {
            _uiState.value = TirocinioUiState(
                caricato = true,
                esercizio = esercizio,
                registro = registro,
                righe = registro.eventi.map {
                    RigaLog(it, it.riga(registro.colonne), StatoRiga.IGNORATA)
                },
                pastiglie = pastiglieDi(registro),
            )
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    risolto = esercizioId in repository.solvedExercises(),
                    soluzioneVisibile = esercizioId in repository.solvedExercises(),
                )
            }
        }
    }

    fun scrivi(testo: String) {
        // The old verdict is cleared the moment the rule changes: leaving it on screen next to
        // a rule that no longer produced it is the fastest way to confuse somebody.
        _uiState.value = _uiState.value.copy(
            testoRegola = testo,
            errore = null,
            esito = null,
            giudizio = null,
            righe = _uiState.value.righe.map { it.copy(stato = StatoRiga.IGNORATA) },
        )
    }

    fun aggiungi(pastiglia: Pastiglia) {
        val corrente = _uiState.value.testoRegola
        val separatore = if (corrente.isEmpty() || corrente.endsWith(" ")) "" else " "
        scrivi(corrente + separatore + pastiglia.testo + " ")
    }

    fun cancellaTutto() = scrivi("")

    fun apriPromemoria() {
        _uiState.value = _uiState.value.copy(promemoriaAperto = !_uiState.value.promemoriaAperto)
    }

    fun mostraIndizio() {
        _uiState.value = _uiState.value.copy(indizioVisibile = true)
    }

    /**
     * Gives up and shows the answer.
     *
     * Deliberately available, and deliberately not the same as solving it: the exercise stays
     * unsolved in the record. A student stuck at midnight learns more from reading the rule
     * than from closing the app, and the trophy is still there for the day they write it.
     */
    fun mostraSoluzione() {
        _uiState.value = _uiState.value.copy(soluzioneVisibile = true, indizioVisibile = true)
    }

    fun prova() {
        val stato = _uiState.value
        val esercizio = stato.esercizio ?: return
        val registro = stato.registro ?: return

        when (val lettura = Analizzatore(registro.campi).leggi(stato.testoRegola)) {
            is Lettura.Fallita -> {
                _uiState.value = stato.copy(
                    errore = lettura.errore,
                    esito = null,
                    giudizio = null,
                    righe = stato.righe.map { it.copy(stato = StatoRiga.IGNORATA) },
                )
            }

            is Lettura.Riuscita -> {
                val bersagli = esercizio.bersagli.toSet()
                val esito = MotoreRegole.esegui(lettura.regola, registro, bersagli)
                val accesi = esito.accesi.toSet()

                _uiState.value = stato.copy(
                    errore = null,
                    esito = esito,
                    giudizio = esercizio.giudizio(esito),
                    tentativi = stato.tentativi + 1,
                    righe = stato.righe.map { riga ->
                        riga.copy(
                            stato = when {
                                riga.evento.id in accesi && riga.evento.id in bersagli -> StatoRiga.PRESA
                                riga.evento.id in accesi -> StatoRiga.FALSO
                                riga.evento.id in bersagli -> StatoRiga.PERSA
                                else -> StatoRiga.IGNORATA
                            },
                        )
                    },
                )

                if (esito.perfetto) {
                    viewModelScope.launch {
                        repository.solveExercise(esercizio.id)
                        repository.awardTrophies()
                        _uiState.value = _uiState.value.copy(risolto = true, soluzioneVisibile = true)
                    }
                }
            }
        }
    }

    /**
     * The chips, in the order they are needed.
     *
     * Fields first because a condition starts with one, then the operators, then the values —
     * and the values are read out of the log itself, so a student can build a whole condition
     * without inventing a single word.
     */
    private fun pastiglieDi(registro: LogSource): List<Pastiglia> = buildList {
        registro.campi.forEach { add(Pastiglia(it, GRUPPO_CAMPI)) }
        listOf("=", "!=", "contiene", ">", "<").forEach { add(Pastiglia(it, GRUPPO_OPERATORI)) }
        add(Pastiglia("e", GRUPPO_UNIONI))
        add(Pastiglia("oppure", GRUPPO_UNIONI))
        add(Pastiglia("conta >", GRUPPO_SOGLIA))
        add(Pastiglia("in", GRUPPO_SOGLIA))
        add(Pastiglia("minuti", GRUPPO_SOGLIA))
        add(Pastiglia("per", GRUPPO_SOGLIA))
        // Only the fields with few enough values to be worth offering: an address per line
        // would bury the useful ones.
        registro.campi.forEach { campo ->
            val valori = registro.valori(campo)
            if (valori.size in 1..MAX_VALORI) {
                valori.forEach { add(Pastiglia(it, GRUPPO_VALORI)) }
            }
        }
    }

    companion object {
        const val GRUPPO_CAMPI = "Campi"
        const val GRUPPO_OPERATORI = "Operatori"
        const val GRUPPO_UNIONI = "Unioni"
        const val GRUPPO_SOGLIA = "Soglia"
        const val GRUPPO_VALORI = "Valori nel registro"

        private const val MAX_VALORI = 6
    }
}
