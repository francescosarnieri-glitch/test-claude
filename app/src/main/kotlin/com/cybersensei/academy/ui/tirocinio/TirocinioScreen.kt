package com.cybersensei.academy.ui.tirocinio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.TerminalBlock
import com.cybersensei.academy.core.ui.theme.SenseiTheme

@Composable
fun TirocinioScreen(
    onBack: () -> Unit,
    viewModel: TirocinioViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        uiState.problema?.let {
            SenseiCard { Text(text = it, color = MaterialTheme.colorScheme.onSurface) }
            SenseiTextButton(text = "Torna indietro", onClick = onBack)
            return@Column
        }

        val esercizio = uiState.esercizio ?: return@Column

        Text(
            text = esercizio.titolo,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = esercizio.sottotitolo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ProfessorBubble(text = esercizio.briefing)

        SenseiCard {
            SectionHeader(text = "Cosa devi ottenere")
            Text(
                text = esercizio.obiettivo,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // --- Il registro -------------------------------------------------------------------

        uiState.registro?.let { registro ->
            SectionHeader(text = registro.titolo)
            Text(
                text = registro.descrizione,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RegistroBlock(uiState)
            Legenda(uiState)
        }

        // --- La regola ---------------------------------------------------------------------

        SectionHeader(text = "La tua regola")
        OutlinedTextField(
            value = uiState.testoRegola,
            onValueChange = viewModel::scrivi,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("campo operatore valore") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            isError = uiState.errore != null,
            singleLine = false,
            minLines = 2,
        )

        Pastigliera(uiState, viewModel)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SenseiPrimaryButton(
                text = "Prova la regola",
                onClick = viewModel::prova,
                modifier = Modifier.weight(1f),
            )
            SenseiTextButton(text = "Cancella", onClick = viewModel::cancellaTutto)
        }

        uiState.errore?.let { errore ->
            SenseiCard {
                Text(
                    text = "Non riesco a leggere la regola",
                    style = MaterialTheme.typography.titleSmall,
                    color = SenseiTheme.colors.wrong,
                )
                Text(
                    text = errore.messaggio,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                errore.suggerimento?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Come è andata -----------------------------------------------------------------

        uiState.esito?.let { esito ->
            SenseiCard {
                SectionHeader(text = "Cosa ha fatto la tua regola")
                Punteggio("Attacchi presi", "${esito.presi.size} su ${esito.bersagli}", SenseiTheme.colors.correct)
                Punteggio(
                    label = "Attacchi sfuggiti",
                    valore = esito.persi.size.toString(),
                    colore = if (esito.persi.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        SenseiTheme.colors.wrong
                    },
                )
                Punteggio(
                    label = "Falsi allarmi",
                    valore = esito.falsi.size.toString(),
                    colore = if (esito.falsi.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        SenseiTheme.colors.warning
                    },
                )
            }

            uiState.giudizio?.let { giudizio ->
                ProfessorBubble(text = "${giudizio.titolo}\n\n${giudizio.corpo}")
            }
        }

        // --- Aiuti ------------------------------------------------------------------------

        if (!uiState.risolto) {
            if (uiState.indizioVisibile) {
                SenseiCard {
                    SectionHeader(text = "Indizio")
                    Text(
                        text = esercizio.indizio,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                SenseiTextButton(text = "Dammi un indizio", onClick = viewModel::mostraIndizio)
            }

            // Offered only once the student has actually tried: a way out that appears before
            // the first attempt is a way out most people take.
            if (uiState.tentativi >= TENTATIVI_PRIMA_DELLA_RESA && !uiState.soluzioneVisibile) {
                SenseiTextButton(text = "Mostrami la soluzione", onClick = viewModel::mostraSoluzione)
            }
        }

        if (uiState.soluzioneVisibile) {
            SenseiCard {
                SectionHeader(text = if (uiState.risolto) "Una regola che la risolve" else "La soluzione")
                TerminalBlock(text = esercizio.regolaModello)
                Text(
                    text = esercizio.spiegazione,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!uiState.risolto) {
                    Text(
                        text = "L'esercizio resta da fare: scrivila tu e il trofeo è ancora lì.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SenseiTextButton(text = "Torna al percorso", onClick = onBack)
    }
}

@Composable
private fun RegistroBlock(uiState: TirocinioUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SenseiTheme.colors.terminalBackground,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = ALTEZZA_REGISTRO.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            uiState.righe.forEach { riga ->
                val colore = when (riga.stato) {
                    StatoRiga.PRESA -> SenseiTheme.colors.correct
                    StatoRiga.PERSA -> SenseiTheme.colors.wrong
                    StatoRiga.FALSO -> SenseiTheme.colors.warning
                    StatoRiga.IGNORATA -> SenseiTheme.colors.terminalText.copy(alpha = 0.55f)
                }
                // The marker is what carries the meaning for anybody who cannot tell the
                // colours apart — and it also survives a screenshot in black and white.
                val segno = when (riga.stato) {
                    StatoRiga.PRESA -> "✓"
                    StatoRiga.PERSA -> "✗"
                    StatoRiga.FALSO -> "!"
                    StatoRiga.IGNORATA -> " "
                }
                Text(
                    text = "$segno ${riga.testo}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colore,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Legenda(uiState: TirocinioUiState) {
    if (uiState.esito == null) {
        Text(
            text = "Scrivi una regola e premi «Prova»: le righe che si accendono te le segno qui sopra.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        VoceLegenda("✓", "presa: era attacco e l'hai presa", SenseiTheme.colors.correct)
        VoceLegenda("✗", "sfuggita: era attacco e non l'hai presa", SenseiTheme.colors.wrong)
        VoceLegenda("!", "falso allarme: non era attacco", SenseiTheme.colors.warning)
    }
}

@Composable
private fun VoceLegenda(segno: String, testo: String, colore: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = segno, fontFamily = FontFamily.Monospace, color = colore)
        Text(
            text = testo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Pastigliera(uiState: TirocinioUiState, viewModel: TirocinioViewModel) {
    uiState.pastiglie.groupBy { it.gruppo }.forEach { (gruppo, pastiglie) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = gruppo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pastiglie.forEach { pastiglia ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.clickable { viewModel.aggiungi(pastiglia) },
                    ) {
                        Text(
                            text = pastiglia.testo,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Punteggio(label: String, valore: String, colore: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valore,
            style = MaterialTheme.typography.titleMedium,
            color = colore,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * How many runs before the way out is offered.
 *
 * Three, because two is where people are still learning the syntax and would take the answer
 * out of frustration with the keyboard rather than with the problem.
 */
private const val TENTATIVI_PRIMA_DELLA_RESA = 3
private const val ALTEZZA_REGISTRO = 260
