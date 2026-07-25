package com.cybersensei.academy.ui.lesson

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.TerminalBlock

@Composable
fun LessonScreen(
    onFinished: () -> Unit,
    onQuizRequested: (String) -> Unit,
    viewModel: LessonViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = !uiState.completed) {
        viewModel.leaveEarly()
        onFinished()
    }

    val lesson = uiState.lesson
    if (lesson == null) {
        Text(
            text = "Questa lezione non esiste più.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onBackground,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(text = lesson.title)
            LinearProgressIndicator(
                progress = { uiState.progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = "Scheda ${uiState.cardIndex + 1} di ${uiState.cardCount} · ${lesson.minutes} min",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiState.completed) {
            LessonCompletedBlock(
                closingLine = uiState.closingLine,
                onFinished = onFinished,
                onQuizRequested = { onQuizRequested(uiState.moduleId) },
            )
            return@Column
        }

        val card = uiState.card ?: return@Column
        SenseiCard {
            card.title?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = card.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            card.terminal?.let { TerminalBlock(text = it) }
        }

        card.takeaway?.let { takeaway ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "▸",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = takeaway,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (uiState.cardIndex > 0) {
                SenseiTextButton(text = "Indietro", onClick = viewModel::previousCard)
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }
            SenseiPrimaryButton(
                text = if (uiState.isLastCard) "Ho capito" else "Avanti",
                onClick = viewModel::nextCard,
            )
        }
    }
}

@Composable
private fun LessonCompletedBlock(
    closingLine: String?,
    onFinished: () -> Unit,
    onQuizRequested: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        closingLine?.let { ProfessorBubble(text = it) }
        SenseiPrimaryButton(
            text = "Mettimi alla prova",
            onClick = onQuizRequested,
            modifier = Modifier.fillMaxWidth(),
        )
        SenseiTextButton(text = "Torno in aula", onClick = onFinished)
    }
}
