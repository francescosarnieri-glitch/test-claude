package com.cybersensei.academy.ui.classroom

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.theme.SenseiTheme

@Composable
fun ClassroomScreen(
    onStartLesson: (String) -> Unit,
    onOpenPath: () -> Unit,
    viewModel: ClassroomViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Returning from a lesson must show what just happened, not what was true on entry.
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
            text = "Aula",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        ProfessorBubble(text = uiState.professorLine)

        val lesson = uiState.nextLesson
        if (lesson != null) {
            SenseiCard {
                SectionHeader(text = "Il prossimo passo")
                Text(
                    text = lesson.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${lesson.minutes} minuti · ${lesson.cards.size} schede",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SenseiPrimaryButton(
                    text = "Cominciamo",
                    onClick = { onStartLesson(lesson.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (uiState.everythingDone) {
            SenseiCard {
                SectionHeader(text = "Introduzione completata")
                Text(
                    text = "Hai finito tutte le lezioni disponibili. I livelli Facile, " +
                        "Intermedio e Difficile arrivano nelle fasi successive — e ti " +
                        "avviso io quando ci saranno.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SenseiPrimaryButton(
                    text = "Guarda il percorso",
                    onClick = onOpenPath,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionHeader(text = "A che punto sei")
        SenseiCard {
            StatRow("Lezioni completate", "${uiState.lessonsDone} / ${uiState.lessonsTotal}")
            StatRow("Giorni di fila", uiState.streakDays.toString())
            StatRow("Esperienza", "${uiState.experiencePoints} XP")
            if (uiState.dueReviews > 0) {
                StatRow(
                    label = "Ripassi in scadenza",
                    value = uiState.dueReviews.toString(),
                    highlight = true,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (highlight) {
                SenseiTheme.colors.warning
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
