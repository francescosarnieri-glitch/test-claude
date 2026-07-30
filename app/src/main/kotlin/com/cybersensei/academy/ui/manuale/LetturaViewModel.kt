package com.cybersensei.academy.ui.manuale

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.cybersensei.academy.core.curriculum.CapitoloManuale
import com.cybersensei.academy.core.curriculum.Manuale
import com.cybersensei.academy.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LetturaUiState(
    val capitolo: CapitoloManuale? = null,
    val parte: String? = null,
    /** Which page of the chapter to open on. */
    val indiceIniziale: Int = 0,
    val pagineDelLibro: Int = 0,
)

/**
 * Opens the book at a page.
 *
 * The route carries a page number rather than a chapter and an offset, because that is the thing
 * the index shows and the bookmark stores — one number that means the same in all three places.
 */
@HiltViewModel
class LetturaViewModel @Inject constructor(
    private val manuale: Manuale,
    private val segnalibro: Segnalibro,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val numeroPagina: Int =
        savedStateHandle.get<String>(Routes.ARG_PAGE)?.toIntOrNull() ?: 1

    private val _uiState = MutableStateFlow(LetturaUiState())
    val uiState: StateFlow<LetturaUiState> = _uiState.asStateFlow()

    init {
        val capitolo = manuale.capitoloDellaPagina(numeroPagina)
        _uiState.value = LetturaUiState(
            capitolo = capitolo,
            parte = capitolo?.let { manuale.parteDi(it.id)?.nome },
            indiceIniziale = capitolo
                ?.pagine
                ?.indexOfFirst { it.numero == numeroPagina }
                ?.coerceAtLeast(0)
                ?: 0,
            pagineDelLibro = manuale.pagine.size,
        )
    }

    fun segna(numero: Int) = segnalibro.segna(numero)
}
