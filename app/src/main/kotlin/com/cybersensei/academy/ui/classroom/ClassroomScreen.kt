package com.cybersensei.academy.ui.classroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.BuildConfig
import com.cybersensei.academy.core.model.Level
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.TerminalBlock
import com.cybersensei.academy.core.ui.theme.CyberSenseiTheme

@Composable
fun ClassroomScreen(
    viewModel: ClassroomViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ClassroomContent(uiState = uiState)
}

@Composable
private fun ClassroomContent(
    uiState: ClassroomUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Cyber Sensei",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Scuola di white hacking difensivo",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ProfessorBubble(text = uiState.professorLine)

        SectionHeader(text = "Il percorso")
        SenseiCard {
            Level.entries.forEach { level ->
                Text(
                    text = "${level.order}. ${level.italianName} — ${level.subtitle}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "Il professore ora ragiona: sceglie cosa dirti in base a ora, " +
                    "assenze, errori ricorrenti e risposte. Lezioni e quiz arrivano in Fase 2.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader(text = "Stato della costruzione")
        TerminalBlock(
            text = buildString {
                appendLine("cybersensei@fase-1:~$ status")
                appendLine("build .................. ok (${BuildConfig.VERSION_NAME})")
                appendLine("tema notturno .......... ok")
                appendLine("navigazione ............ ok")
                appendLine("permesso INTERNET ...... assente (per scelta)")
                appendLine("motore del professore .. ok (regole + memoria)")
                appendLine("padronanza e ripassi ... ok")
                appendLine("domande libere ......... ok (offline)")
                append("lezioni e quiz ......... fase 2")
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF070B10)
@Composable
private fun ClassroomPreview() {
    CyberSenseiTheme(darkTheme = true) {
        ClassroomContent(
            uiState = ClassroomUiState(
                professorLine = "Benvenuto. Mi chiamo Hackstein White — White di cognome, " +
                    "e non è un caso: qui si impara ad attaccare solo per imparare a difendere.",
            ),
        )
    }
}
