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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.theme.SenseiTheme

/**
 * The study, where the professor leads.
 *
 * Nothing on this screen interprets anything. The student picks a room, picks a question, and
 * reads the answer written for exactly that question — so nothing here has to be read with
 * suspicion, which is the only way a school about security is worth anything.
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

        if (uiState.corpusSize > 0) {
            Text(
                text = "Hai aperto ${uiState.openQuestions} domande su ${uiState.corpusSize}. " +
                    "Le altre si aprono mentre studi.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                ExchangeCard(exchange = exchange)
            }
        }

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

    Ahead(uiState = uiState, viewModel = viewModel)
}

/**
 * What the student has not opened yet.
 *
 * Folded, and never hidden: a short list with nothing to explain it looks like an app that is
 * missing something, and a locked answer that somebody needs *now* would be this school
 * failing at the one thing it exists for. So the count is stated, the lessons that open it are
 * named, and one tap shows everything — with the professor saying out loud that they are
 * running ahead of the programme.
 */
@Composable
private fun Ahead(uiState: StudyUiState, viewModel: StudyViewModel) {
    if (uiState.ahead.isEmpty()) return

    val quante = if (uiState.ahead.size == 1) {
        "C'è ancora 1 domanda che si apre studiando"
    } else {
        "Ci sono ancora ${uiState.ahead.size} domande che si aprono studiando"
    }
    val moduli = uiState.opensWith.take(3).joinToString(", ")
    val coda = if (uiState.opensWith.size > 3) " e altri" else ""

    Text(
        text = "$quante: $moduli$coda.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SenseiTextButton(
        text = if (uiState.showingAhead) "Nascondi" else "Fammele vedere lo stesso",
        onClick = viewModel::toggleAhead,
    )
    if (uiState.showingAhead) {
        uiState.ahead.forEach { question ->
            SuggestionRow(suggestion = question, onClick = { viewModel.askSuggestion(question) })
        }
    }
}

@Composable
private fun ExchangeCard(exchange: Exchange) {
    SenseiCard {
        Text(
            text = exchange.question,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

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
    }
}

/** A place to go, with how much is left to ask down there. */
@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    val remaining = when {
        place.remaining == 0 && place.ahead > 0 -> "si apre studiando (${place.ahead})"
        place.remaining == 0 -> "niente di nuovo qui"
        place.remaining == 1 -> "1 domanda"
        else -> "${place.remaining} domande"
    } + if (place.remaining > 0 && place.ahead > 0) " · ${place.ahead} da sbloccare" else ""

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
