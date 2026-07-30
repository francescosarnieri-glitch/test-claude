package com.cybersensei.academy.ui.manuale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.curriculum.CapitoloManuale
import com.cybersensei.academy.core.curriculum.PaginaManuale
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.theme.SenseiTheme

@Composable
fun ManualeScreen(
    onOpenPage: (Int) -> Unit,
    viewModel: ManualeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    // Una lista pigra e non una colonna che scorre: duecentosettantatre voci disegnate tutte
    // insieme le sente anche un telefono nuovo.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Frontespizio(uiState, onOpenPage) }

        uiState.parti.forEach { parte ->
            item(key = "parte-${parte.nome}") { ParteTesta(parte.nome, parte.titolo, parte.occhiello) }

            items(parte.capitoli, key = { it.id }) { capitolo ->
                CapitoloRiga(
                    capitolo = capitolo,
                    aperto = capitolo.id in uiState.aperti,
                    onClick = { viewModel.apriChiudi(capitolo.id) },
                    onOpenPage = onOpenPage,
                )
            }
        }

        item { Chiusura() }
    }
}

@Composable
private fun Frontespizio(uiState: ManualeUiState, onOpenPage: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = uiState.manuale?.titolo.orEmpty(),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = uiState.manuale?.sottotitolo.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SenseiCard {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Misura("4", "parti")
                Misura("27", "capitoli")
                Misura(uiState.pagineTotali.toString(), "pagine")
            }
            Text(
                text = "Un capitolo per ogni modulo del programma, nello stesso ordine: finita " +
                    "una lezione sai sempre dove rileggerla più a fondo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Scritte finora: ${uiState.pagineScritte} pagine su ${uiState.pagineTotali}. " +
                    "Le altre le stiamo scrivendo — questo è l'indice, e serve a decidere " +
                    "insieme com'è fatto il libro prima di riempirlo.",
                style = MaterialTheme.typography.bodySmall,
                color = SenseiTheme.colors.warning,
            )
        }

        // Il segnalibro compare solo quando c'e' qualcosa da riprendere: un bottone «riprendi»
        // a libro mai aperto e' un invito a niente.
        uiState.capitoloDelSegnalibro?.let { capitolo ->
            SenseiCard {
                Text(
                    text = "Dove eri rimasto",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${capitolo.etichetta} · pagina ${uiState.segnalibro}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SenseiPrimaryButton(
                    text = "Riprendi la lettura",
                    onClick = { onOpenPage(uiState.segnalibro) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Misura(numero: String, cosa: String) {
    Column {
        Text(
            text = numero,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = cosa,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ParteTesta(nome: String, titolo: String, occhiello: String) {
    Column(
        modifier = Modifier.padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {}
        Text(
            text = nome.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = titolo,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (occhiello.isNotBlank()) {
            Text(
                text = occhiello,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CapitoloRiga(
    capitolo: CapitoloManuale,
    aperto: Boolean,
    onClick: () -> Unit,
    onOpenPage: (Int) -> Unit,
) {
    SenseiCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (capitolo.numero > 0) {
                Text(
                    text = capitolo.numero.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = SenseiTheme.colors.warning,
                    modifier = Modifier.width(34.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = capitolo.titolo,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${capitolo.pagine.size} pagine · da ${capitolo.daPagina} a ${capitolo.aPagina}" +
                        if (capitolo.finito) " · scritto" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (capitolo.finito) {
                        SenseiTheme.colors.correct
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = if (aperto) "−" else "+",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (aperto) {
            if (capitolo.fonte.isNotBlank()) {
                Text(
                    text = capitolo.fonte,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            capitolo.pagine.forEach { pagina -> VoceIndice(pagina, onOpenPage) }
        }
    }
}

/**
 * One line of the table of contents.
 *
 * The dotted leader between title and page number is the only flourish here, and it is the thing
 * that makes a list read as an index instead of as a menu.
 */
@Composable
private fun VoceIndice(pagina: PaginaManuale, onOpenPage: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPage(pagina.numero) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = pagina.titolo,
            style = MaterialTheme.typography.bodyMedium,
            color = if (pagina.scritta) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .padding(bottom = 4.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(1.dp),
                color = MaterialTheme.colorScheme.outline,
            ) {}
        }
        Text(
            text = pagina.numero.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Chiusura() {
    SenseiCard {
        Text(
            text = "Questo è l'indice",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "La struttura viene prima della scrittura: cambiarla adesso costa un'ora, " +
                "con cento pagine scritte costa settimane. Tocca una voce qualunque per vedere " +
                "come si sfoglia — la Prefazione è già scritta per intero.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
