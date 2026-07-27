package com.cybersensei.academy.ui.diploma

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.Professor
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DiplomaScreen(
    onBack: () -> Unit,
    viewModel: DiplomaViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Diploma",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (!uiState.loaded) return@Column

        val diploma = uiState.diploma
        if (diploma == null) {
            ProfessorBubble(
                text = "Il diploma non si guadagna con il tempo passato qui dentro. Si " +
                    "guadagna superando i tre esami e attraversando la notte dell'Incidente. " +
                    "Ti manca ancora qualcosa — è scritto sotto, senza giri di parole.",
            )
            SectionHeader(text = "Cosa serve")
            SenseiCard {
                uiState.requirements.forEach { requirement ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Word and symbol, never colour alone.
                        Text(
                            text = if (requirement.met) "✓" else "○",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (requirement.met) {
                                SenseiTheme.colors.correct
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = requirement.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = requirement.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            SenseiTextButton(text = "Torno in aula", onClick = onBack, modifier = Modifier.fillMaxWidth())
            return@Column
        }

        ProfessorBubble(
            text = "Ce l'hai fatta, ${diploma.studentName}. Non te l'ho regalato: sono " +
                "tre esami e una notte, e i numeri qui sotto li hai scritti tu una " +
                "risposta alla volta. Portalo con te.",
        )

        Certificate(diploma)

        SenseiPrimaryButton(
            text = "Esporta come immagine",
            onClick = { DiplomaImage.share(context, diploma) },
            modifier = Modifier.fillMaxWidth(),
        )
        SenseiTextButton(text = "Torno in aula", onClick = onBack, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun Certificate(diploma: Diploma) {
    val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "CYBER SENSEI",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "DIPLOMA",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "conferito a",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = diploma.studentName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "per aver superato gli esami dei tre livelli e attraversato " +
                    "la notte dell'Incidente",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))
            diploma.examScores.toSortedMap().forEach { (level, score) ->
                Fact(level, "$score%")
            }
            Fact("Padronanza media", "${diploma.masteryPercent}%")
            Fact("Lezioni", "${diploma.lessonsCompleted} su ${diploma.lessonsTotal}")
            Fact("Tempo di studio", "${diploma.studyMinutes} minuti")
            Fact("Iscritto da", "${diploma.daysEnrolled} giorni")

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Rilasciato il ${diploma.awardedOn.format(formatter)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = Professor.NAME,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Questo documento non ha valore legale. Ha valore per te.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
