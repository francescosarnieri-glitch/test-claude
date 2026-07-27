package com.cybersensei.academy.ui.study

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.SenseiTextField
import com.cybersensei.academy.core.ui.theme.SenseiTheme

@Composable
fun StudyScreen(viewModel: StudyViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The observations must reflect the lesson that just ended, not the app launch.
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
            text = "Studio del Prof.",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        ProfessorBubble(text = uiState.openingLine)

        SenseiCard {
            SenseiTextField(
                value = uiState.draft,
                onValueChange = viewModel::onDraftChange,
                label = "Chiedimi quello che vuoi",
                imeAction = ImeAction.Send,
                supportingText = "Scrivi con parole tue. So rispondere su " +
                    "${uiState.corpusSize} argomenti del programma.",
            )
            SenseiPrimaryButton(
                text = "Chiedi al professore",
                onClick = { viewModel.ask() },
                enabled = uiState.draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (uiState.suggestions.isNotEmpty()) {
            SectionHeader(text = "Se non sai da dove partire")
            uiState.suggestions.forEach { suggestion ->
                SuggestionRow(
                    suggestion = suggestion,
                    onClick = { viewModel.askSuggestion(suggestion) },
                )
            }
        }

        if (uiState.exchanges.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionHeader(text = "La conversazione")
                SenseiTextButton(text = "Pulisci", onClick = viewModel::clearHistory)
            }
            uiState.exchanges.forEach { exchange ->
                ExchangeCard(
                    exchange = exchange,
                    onFollowUp = { viewModel.askSuggestion(it) },
                )
            }
        }

        SectionHeader(text = "Cosa ho notato su di te")
        SenseiCard {
            uiState.notes.forEach { note -> NoteRow(note) }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ExchangeCard(exchange: Exchange, onFollowUp: (Suggestion) -> Unit) {
    SenseiCard {
        Text(
            text = "Tu: ${exchange.question}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Retrieval is not telepathy. Showing which question was actually answered lets the
        // student catch a wrong match instead of trusting an answer to something else.
        if (exchange.understood && exchange.answeredTopic != null && !exchange.choosing) {
            Text(
                // A hedge, and a visible one: the professor got here by resemblance, on a
                // question that named nothing of his subject.
                text = if (exchange.uncertain) {
                    "Non sono sicuro di aver capito. Ti rispondo su: ${exchange.answeredTopic}"
                } else {
                    "Ti rispondo su: ${exchange.answeredTopic}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ProfessorBubble(text = exchange.answer, animate = false)

        exchange.aheadOfLevel?.let { warning ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, SenseiTheme.colors.info),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(text = "⏭", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (exchange.alternatives.isNotEmpty()) {
            SectionHeader(
                text = when {
                    // A question, not a footnote: the professor is waiting for the answer.
                    exchange.choosing -> "Dimmi tu quale"
                    exchange.understood -> "Forse intendevi anche"
                    else -> "Il più vicino che conosco"
                },
            )
            exchange.alternatives.forEach { alternative ->
                SuggestionRow(suggestion = alternative, onClick = { onFollowUp(alternative) })
            }
        }
    }
}

/**
 * A question the student can hand to the professor with one tap.
 *
 * The level is spelled out, never signalled by colour alone: it is a label the screen reader
 * announces as part of the row.
 */
@Composable
private fun SuggestionRow(suggestion: Suggestion, onClick: () -> Unit) {
    val levelLabel = LEVEL_LABELS.getOrElse(suggestion.level) { "Livello ${suggestion.level}" }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${suggestion.text}. $levelLabel." },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = suggestion.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = levelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteRow(note: Note) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // An icon, not just a colour: the observation must read as a warning in greyscale too.
        Text(
            text = if (note.warning) "!" else "·",
            style = MaterialTheme.typography.titleMedium,
            color = if (note.warning) {
                SenseiTheme.colors.warning
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = note.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            note.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val LEVEL_LABELS = listOf("Introduzione", "Facile", "Intermedio", "Difficile")
