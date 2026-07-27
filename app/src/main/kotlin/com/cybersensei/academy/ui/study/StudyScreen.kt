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

/**
 * The study, where the professor leads.
 *
 * The order on screen is the argument the redesign makes: what he has noticed about *you*
 * first, then where he can take you, and only at the bottom the keyboard — which is now a way
 * of searching the school's own questions rather than a box that guesses.
 */
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

        Navigation(uiState = uiState, viewModel = viewModel)

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

        Keyboard(uiState = uiState, viewModel = viewModel)

        SectionHeader(text = "Cosa ho notato su di te")
        SenseiCard {
            uiState.notes.forEach { note -> NoteRow(note) }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Where the professor can take the student.
 *
 * Every route here ends on an answer somebody wrote and checked, which is the whole point: no
 * amount of tapping can produce a wrong answer, so nothing on this screen needs to be read
 * with suspicion.
 */
@Composable
private fun Navigation(uiState: StudyUiState, viewModel: StudyViewModel) {
    val place = uiState.trail.lastOrNull()

    if (place == null) {
        SectionHeader(text = "Di cosa parliamo?")
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionHeader(text = place.title)
            SenseiTextButton(text = "‹ Indietro", onClick = viewModel::goBack)
        }
        place.subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        uiState.line?.let { ProfessorBubble(text = it, animate = false) }
    }

    uiState.places.forEach { destination ->
        PlaceRow(place = destination, onClick = { viewModel.goTo(destination.id) })
    }

    uiState.questions.forEach { question ->
        SuggestionRow(suggestion = question, onClick = { viewModel.askSuggestion(question) })
    }

    // Asked questions leave the list. Saying so out loud turns an emptying screen from a bug
    // into progress — and the answers are all still there, further down.
    if (uiState.exhausted) {
        Text(
            text = "Di questa stanza mi hai chiesto tutto. Le risposte restano qui sotto, " +
                "e con «Pulisci» rimetto le domande al loro posto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The keyboard, kept and demoted.
 *
 * It stays because the moment a person most needs this app is the moment they want to
 * describe what happened rather than look for it in a list. It is folded away because for
 * everything else the list is better: it cannot be misunderstood.
 */
@Composable
private fun Keyboard(uiState: StudyUiState, viewModel: StudyViewModel) {
    if (!uiState.typing) {
        SenseiTextButton(
            text = "Preferisco scrivere io",
            onClick = viewModel::toggleTyping,
        )
        return
    }

    SenseiCard {
        SenseiTextField(
            value = uiState.draft,
            onValueChange = viewModel::onDraftChange,
            label = "Scrivi con parole tue",
            imeAction = ImeAction.Send,
            supportingText = "Mentre scrivi ti mostro le domande che ho. " +
                "So rispondere su ${uiState.corpusSize} argomenti.",
        )

        // The honest half of typing: these are the school's own questions, filtered. Tapping
        // one cannot be a misunderstanding, because nothing was interpreted.
        uiState.matches.forEach { match ->
            SuggestionRow(suggestion = match, onClick = { viewModel.askSuggestion(match) })
        }

        SenseiPrimaryButton(
            text = "Chiedi al professore",
            onClick = { viewModel.ask() },
            enabled = uiState.draft.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        SenseiTextButton(text = "Chiudi la tastiera", onClick = viewModel::toggleTyping)
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
                text = "Ti rispondo su: ${exchange.answeredTopic}",
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

/** A place to go, with how much is left to ask down there. */
@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    val remaining = when (place.remaining) {
        0 -> "niente di nuovo qui"
        1 -> "1 domanda"
        else -> "${place.remaining} domande"
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${place.title}. ${place.subtitle.orEmpty()} $remaining." },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = place.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            place.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = remaining,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
