package com.cybersensei.academy.ui.manuale

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.cybersensei.academy.core.curriculum.CapitoloManuale
import com.cybersensei.academy.core.curriculum.Manuale
import com.cybersensei.academy.core.curriculum.ParteManuale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the reader left off.
 *
 * A bookmark changes every time a page is turned, which is far too often for the school's diary
 * and far too trivial for its records: nothing about it is worth surviving a reinstall. Ordinary
 * preferences are exactly the right weight for it.
 */
@Singleton
class Segnalibro @Inject constructor(@ApplicationContext context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _pagina = MutableStateFlow(preferences.getInt(CHIAVE, 0))
    val pagina: StateFlow<Int> = _pagina.asStateFlow()

    fun segna(numero: Int) {
        preferences.edit().putInt(CHIAVE, numero).apply()
        _pagina.value = numero
    }

    private companion object {
        const val FILE = "cybersensei.manuale"
        const val CHIAVE = "ultima_pagina"
    }
}

data class ManualeUiState(
    val manuale: Manuale? = null,
    val parti: List<ParteManuale> = emptyList(),
    /** Which chapters are open on the index. */
    val aperti: Set<String> = emptySet(),
    val segnalibro: Int = 0,
    val capitoloDelSegnalibro: CapitoloManuale? = null,
    val pagineTotali: Int = 0,
    val pagineScritte: Int = 0,
)

/**
 * The index of the book.
 *
 * Folded the same way the Percorso is, chapter by chapter, for the same reason: two hundred and
 * seventy-three page titles in one column is not a table of contents, it is a wall — and a
 * reader who has to hunt for the chapter has already been told the book is not for them.
 */
@HiltViewModel
class ManualeViewModel @Inject constructor(
    private val manuale: Manuale,
    private val segnalibro: Segnalibro,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualeUiState())
    val uiState: StateFlow<ManualeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val ultima = segnalibro.pagina.value
        _uiState.value = _uiState.value.copy(
            manuale = manuale,
            parti = manuale.parti,
            segnalibro = ultima,
            capitoloDelSegnalibro = manuale.capitoloDellaPagina(ultima),
            pagineTotali = manuale.pagine.size,
            pagineScritte = manuale.pagineScritte,
        )
    }

    fun apriChiudi(capitoloId: String) {
        val aperti = _uiState.value.aperti
        _uiState.value = _uiState.value.copy(
            aperti = if (capitoloId in aperti) aperti - capitoloId else aperti + capitoloId,
        )
    }
}
