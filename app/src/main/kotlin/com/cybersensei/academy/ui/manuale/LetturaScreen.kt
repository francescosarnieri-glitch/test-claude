package com.cybersensei.academy.ui.manuale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import kotlinx.coroutines.launch

/**
 * Reading the book.
 *
 * One chapter at a time, one page per screen, turned with a finger the way a book is. The pages
 * were cut by whoever wrote them, so nothing here measures text or reflows anything: the reader
 * only has to show one page and get out of the way.
 */
@Composable
fun LetturaScreen(
    onBack: () -> Unit,
    onOpenIndex: () -> Unit,
    viewModel: LetturaViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val capitolo = uiState.capitolo

    if (capitolo == null) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            SenseiCard {
                Text(
                    text = "Non riesco ad aprire questa pagina.",
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            SenseiTextButton(text = "Torna all'indice", onClick = onBack)
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = uiState.indiceIniziale,
        pageCount = { capitolo.pagine.size },
    )
    val scope = rememberCoroutineScope()

    // Il segnalibro si sposta con la pagina, non quando si esce: chi chiude l'app dal tasto di
    // sistema deve ritrovarsi dove stava leggendo, non dove era entrato.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { indice ->
            capitolo.pagine.getOrNull(indice)?.let { viewModel.segna(it.numero) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // --- testatina ---
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = uiState.parte.orEmpty().uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = capitolo.etichetta,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            pageSpacing = 8.dp,
        ) { indice ->
            val pagina = capitolo.pagine[indice]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = pagina.titolo,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Surface(
                    modifier = Modifier.width(56.dp).height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {}

                if (pagina.scritta) {
                    Text(
                        text = pagina.corpo,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    SenseiCard {
                        Text(
                            text = "Questa pagina non è ancora scritta",
                            style = MaterialTheme.typography.titleSmall,
                            color = SenseiTheme.colors.warning,
                        )
                        Text(
                            text = "C'è il titolo perché la struttura del libro viene decisa " +
                                "prima: così si può guardare l'indice intero e cambiarlo finché " +
                                "costa poco. Il testo arriva capitolo per capitolo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // --- piede: dove sei, e come ti sposti ---
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            BarraAvanzamento(
                fatte = pagerState.currentPage + 1,
                totali = capitolo.pagine.size,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SenseiTextButton(
                    text = "‹ Indietro",
                    onClick = {
                        scope.launch {
                            if (pagerState.currentPage > 0) {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        }
                    },
                )
                Text(
                    text = "pagina ${capitolo.pagine[pagerState.currentPage].numero} " +
                        "di ${uiState.pagineDelLibro}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SenseiTextButton(
                    text = "Avanti ›",
                    onClick = {
                        scope.launch {
                            if (pagerState.currentPage < capitolo.pagine.lastIndex) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                )
            }
            SenseiTextButton(text = "Torna all'indice", onClick = onOpenIndex)
        }
    }
}

@Composable
private fun BarraAvanzamento(fatte: Int, totali: Int) {
    val quota = if (totali <= 0) 0f else fatte.toFloat() / totali
    Surface(
        modifier = Modifier.fillMaxWidth().height(3.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(quota).height(3.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {}
    }
}
