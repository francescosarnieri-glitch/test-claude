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

        // Quello che si e' appena aperto sta sopra al conto totale: e' la notizia, il conto
        // e' il contesto.
        uiState.justOpened?.let { notizia ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, SenseiTheme.colors.correct),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(text = "✓", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = notizia,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (uiState.corpusSize > 0) {
            Text(
                text = "Hai aperto ${uiState.openQuestions} domande su ${uiState.corpusSize}.",
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
 *
 * The top of the study is split in two and says so: what can be asked now, and what opens by
 * studying. Before the split the two were interleaved through the tree — which is filed by
 * subject, while what is open cuts across it — so finding the handful of answers already
 * earned meant opening ten rooms and reading a locked count in six of them.
 */
@Composable
private fun Navigation(uiState: StudyUiState, viewModel: StudyViewModel) {
    val place = uiState.trail.lastOrNull()

    if (place == null) {
        SectionHeader(text = "Quello che puoi chiedermi")
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

    Ahead(uiState = uiState)

    if (uiState.locked.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        SectionHeader(text = "Quello che si apre studiando")
        Text(
            text = "Altre ${uiState.lockedTotal} domande sono ancora chiuse. Ognuna si apre " +
                "quando fai l'interrogazione del modulo che la spiega, superata o no.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        uiState.locked.forEach { group -> LockedRow(group = group) }
    }
}

/**
 * What the student has not opened yet: a number, and the way to open it.
 *
 * The questions themselves are neither listed nor touchable. A question you are not ready for
 * is not an offer — putting it on screen greyed out would only be a way of saying no twice,
 * and putting it there alive would make the order the school teaches a suggestion.
 */
@Composable
private fun Ahead(uiState: StudyUiState) {
    if (uiState.ahead == 0) return

    val quante = if (uiState.ahead == 1) {
        "Di questa stanza c'è ancora 1 domanda chiusa"
    } else {
        "Di questa stanza ci sono ancora ${uiState.ahead} domande chiuse"
    }
    val moduli = uiState.opensWith.take(3).joinToString(", ")
    val coda = if (uiState.opensWith.size > 3) " e altri" else ""
    val come = if (moduli.isBlank()) {
        ": si aprono quando avrai fatto le interrogazioni che le riguardano."
    } else {
        ": si aprono facendo l'interrogazione di $moduli$coda."
    }

    Text(
        text = quante + come,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One level's worth of still-closed questions.
 *
 * Deliberately not a [Surface] with an `onClick`: this row is information, not an offer, and
 * a row that looks tappable and does nothing teaches the student to distrust every other row
 * on the screen. The padlock says the same thing as the muted colour, so it survives in
 * greyscale and out loud.
 */
@Composable
private fun LockedRow(group: LockedGroup) {
    val quante = if (group.count == 1) "1 domanda" else "${group.count} domande"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Chiuse. ${group.title}: $quante. ${group.detail}" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "🔒",
            style = MaterialTheme.typography.titleMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${group.title} · $quante",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = group.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * A place to go, with how much is left to ask down there.
 *
 * The count is of open questions only, and only rooms with at least one ever get here. What
 * is locked has its own list at the top of the study, said once instead of ten times.
 */
@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    val remaining = when {
        place.remaining == 0 -> "mi hai già chiesto tutto"
        place.remaining == 1 -> "1 domanda"
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

/** Gli stessi nomi dei livelli, perche' una domanda dice a quale parte del programma appartiene. */
private val LEVEL_LABELS = listOf("Introduzione", "Le fondamenta", "Il mestiere", "Il difensore")
