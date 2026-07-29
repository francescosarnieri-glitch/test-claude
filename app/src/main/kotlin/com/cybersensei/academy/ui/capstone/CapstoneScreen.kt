package com.cybersensei.academy.ui.capstone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.TerminalBlock
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import com.cybersensei.academy.engine.scenario.ChoiceQuality
import com.cybersensei.academy.engine.scenario.DecisionRecord
import com.cybersensei.academy.engine.scenario.Verdict

@Composable
fun CapstoneScreen(
    onFinished: () -> Unit,
    viewModel: CapstoneViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (uiState.phase) {
            CapstonePhase.BRIEFING -> Briefing(uiState, onBegin = viewModel::begin, onLeave = onFinished)
            CapstonePhase.DECIDING -> Deciding(uiState, onDecide = viewModel::decide)
            CapstonePhase.CONSEQUENCE -> Consequence(uiState, onProceed = viewModel::proceed)
            CapstonePhase.DEBRIEFING -> Debriefing(
                verdict = uiState.verdict,
                onRestart = viewModel::restart,
                onLeave = onFinished,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun Briefing(state: CapstoneUiState, onBegin: () -> Unit, onLeave: () -> Unit) {
    Text(
        text = state.title,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = state.subtitle,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ProfessorBubble(text = state.briefing)

    state.readyWarning?.let { warning ->
        SenseiCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "!",
                    style = MaterialTheme.typography.titleLarge,
                    color = SenseiTheme.colors.warning,
                )
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    SenseiCard {
        SectionHeader(text = "Come funziona")
        Text(
            text = "Circa ${state.minutes} minuti. Nessun tempo limite sulle singole " +
                "decisioni: la fretta la mette la storia, non un cronometro. " +
                "Ogni scelta ti mostra prima cosa succede, poi perché — e alla fine " +
                "rivediamo la notte insieme, decisione per decisione.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    SenseiPrimaryButton(
        text = "Comincia la notte",
        onClick = onBegin,
        enabled = state.available,
        modifier = Modifier.fillMaxWidth(),
    )
    SenseiTextButton(text = "Non adesso", onClick = onLeave, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Deciding(state: CapstoneUiState, onDecide: (String) -> Unit) {
    val scene = state.scene ?: return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = scene.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        scene.clock?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = SenseiTheme.colors.warning,
            )
        }
    }

    SenseiCard {
        Text(
            text = scene.situation,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        scene.terminal?.let { TerminalBlock(text = it) }
    }

    Text(
        text = scene.question,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )

    scene.choices.forEach { choice ->
        // No hint of quality here: the point of the exercise is deciding without knowing.
        Surface(
            onClick = { onDecide(choice.id) },
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = choice.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun Consequence(state: CapstoneUiState, onProceed: () -> Unit) {
    val aftermath = state.aftermath ?: return

    Text(
        text = "Hai deciso",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )

    SenseiCard {
        Text(
            text = aftermath.choiceText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    // What happened comes before whether it was right: that is the order reality uses.
    SectionHeader(text = "Cosa succede")
    SenseiCard {
        Text(
            text = aftermath.consequence,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    QualityBadge(aftermath.quality)

    SectionHeader(text = "Il parere del professore")
    ProfessorBubble(text = aftermath.explanation, animate = false)

    SenseiPrimaryButton(
        text = if (aftermath.isLast) "Vai al debriefing" else "Avanti",
        onClick = onProceed,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Debriefing(verdict: Verdict?, onRestart: () -> Unit, onLeave: () -> Unit) {
    if (verdict == null) return

    Text(
        text = "Debriefing",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )

    ProfessorBubble(text = verdict.opening)

    SenseiCard {
        Text(
            text = verdict.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = verdict.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    SectionHeader(text = "Come è andata, per assi")
    SenseiCard {
        AxisRow("Contenimento", verdict.containmentPercent)
        AxisRow("Prove conservate", verdict.evidencePercent)
        AxisRow("Fiducia mantenuta", verdict.trustPercent)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Punteggio complessivo ${verdict.scorePercent}% · " +
                "${verdict.soundDecisions} decisioni difendibili su ${verdict.decisions.size} · " +
                "${verdict.hours} ore perse",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (verdict.notes.isNotEmpty()) {
        SectionHeader(text = "Osservazioni")
        SenseiCard {
            verdict.notes.forEach { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.titleMedium,
                        color = SenseiTheme.colors.warning,
                    )
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    SectionHeader(text = "La notte, decisione per decisione")
    verdict.decisions.forEachIndexed { index, decision -> DecisionCard(index + 1, decision) }

    ProfessorBubble(text = verdict.closing, animate = false)

    SenseiPrimaryButton(
        text = "Rigioca la notte",
        onClick = onRestart,
        modifier = Modifier.fillMaxWidth(),
    )
    SenseiTextButton(text = "Torna al percorso", onClick = onLeave, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DecisionCard(number: Int, decision: DecisionRecord) {
    SenseiCard {
        Text(
            text = "$number. ${decision.sceneTitle}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = decision.choiceText,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        QualityBadge(decision.quality)
        Text(
            text = decision.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The verdict on a single decision.
 *
 * Word, symbol and colour together — the same rule as everywhere else in this app, and here
 * it matters more than usual: a student reading this in the dark must not have to guess
 * whether the green one was the good one.
 */
@Composable
private fun QualityBadge(quality: ChoiceQuality) {
    val (label, symbol, colour) = when (quality) {
        ChoiceQuality.RIGHT -> Triple("Decisione giusta", "✓", SenseiTheme.colors.correct)
        ChoiceQuality.DEFENSIBLE -> Triple("Difendibile", "~", SenseiTheme.colors.warning)
        ChoiceQuality.WRONG -> Triple("Errore", "✗", SenseiTheme.colors.wrong)
        ChoiceQuality.HARMFUL -> Triple("Errore grave", "✗✗", SenseiTheme.colors.wrong)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colour),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = symbol, style = MaterialTheme.typography.labelLarge, color = colour)
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = colour)
        }
    }
}

@Composable
private fun AxisRow(label: String, percent: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        AxisBar(percent)
    }
}

@Composable
private fun AxisBar(percent: Int) {
    val colour: Color = when {
        percent >= 80 -> SenseiTheme.colors.correct
        percent >= 50 -> SenseiTheme.colors.warning
        else -> SenseiTheme.colors.wrong
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clearAndSetSemantics {}
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(3.dp),
            ),
    ) {
        if (percent > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .height(6.dp)
                    .background(color = colour, shape = RoundedCornerShape(3.dp)),
            )
        }
    }
}
