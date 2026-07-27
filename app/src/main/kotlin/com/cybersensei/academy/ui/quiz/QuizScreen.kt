package com.cybersensei.academy.ui.quiz

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.ChoiceRow
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import com.cybersensei.academy.engine.mastery.AnswerVerdict
import com.cybersensei.academy.engine.mastery.Confidence

@Composable
fun QuizScreen(
    onFinished: () -> Unit,
    viewModel: QuizViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (uiState.phase == QuizPhase.FINISHED) {
            SummaryBlock(uiState.summary, onFinished)
            return@Column
        }

        val question = uiState.question ?: run {
            Text(
                text = if (uiState.isReview) {
                    "Non hai ripassi in scadenza."
                } else {
                    "Non ci sono domande per questo modulo."
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (uiState.isReview) {
                Text(
                    text = "Vuol dire che sei in pari, non che hai finito: la memoria cala " +
                        "da sola e te li rimetto io in coda quando è il momento.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SenseiTextButton(text = "Torno in aula", onClick = onFinished)
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(text = uiState.moduleTitle)
            LinearProgressIndicator(
                progress = { uiState.progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = "Domanda ${uiState.index + 1} di ${uiState.questions.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = question.prompt,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        val feedback = uiState.feedback
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            question.options.forEachIndexed { position, option ->
                val letter = ('A' + position).toString()
                val accent = when {
                    feedback == null -> null
                    option.correct -> SenseiTheme.colors.correct
                    option.id == feedback.chosen.id -> SenseiTheme.colors.wrong
                    else -> null
                }
                ChoiceRow(
                    text = option.text,
                    leadingLabel = letter,
                    selected = option.id == uiState.selectedOptionId ||
                        (feedback != null && option.correct),
                    enabled = uiState.phase == QuizPhase.CHOOSING,
                    onClick = { viewModel.onOptionSelected(option.id) },
                    accent = accent,
                    description = feedbackLabel(feedback, option.id, option.correct),
                )
            }
        }

        when (uiState.phase) {
            QuizPhase.CHOOSING -> SenseiPrimaryButton(
                text = "Confermo",
                onClick = viewModel::onAnswerConfirmed,
                enabled = uiState.selectedOptionId != null,
                modifier = Modifier.fillMaxWidth(),
            )

            QuizPhase.DECLARING_CONFIDENCE -> ConfidenceBlock(viewModel::onConfidenceChosen)

            QuizPhase.FEEDBACK -> feedback?.let {
                FeedbackBlock(feedback = it, isLast = uiState.isLast, onContinue = viewModel::onContinue)
            }

            QuizPhase.FINISHED -> Unit
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun feedbackLabel(feedback: Feedback?, optionId: String, correct: Boolean): String? = when {
    feedback == null -> null
    correct -> "✓ Risposta corretta"
    optionId == feedback.chosen.id -> "✗ La tua risposta"
    else -> null
}

/**
 * The question the whole method rests on, asked *before* the outcome is revealed. Answering
 * honestly must never cost anything, so the wording carries no judgement.
 */
@Composable
private fun ConfidenceBlock(onChosen: (Confidence) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SenseiCard {
            Text(
                text = "Prima di dirti com'è andata: quanto sei sicuro?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Rispondi sincero. Ammettere di non sapere non ti toglie nulla — mi dice " +
                    "solo dove devo lavorare con te.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Confidence.entries.forEach { confidence ->
            ChoiceRow(
                text = "${confidence.icon}  ${confidence.italianLabel}",
                selected = false,
                onClick = { onChosen(confidence) },
            )
        }
    }
}

@Composable
private fun FeedbackBlock(feedback: Feedback, isLast: Boolean, onContinue: () -> Unit) {
    val colors = SenseiTheme.colors
    val (headline, accent) = when (feedback.verdict) {
        AnswerVerdict.SOLID -> "Corretta, e la sapevi" to colors.correct
        AnswerVerdict.CORRECT_BUT_FRAGILE -> "Corretta, ma non eri convinto" to colors.warning
        AnswerVerdict.SUSPECTED_LUCK -> "Corretta per fortuna" to colors.warning
        AnswerVerdict.ROOTED_MISCONCEPTION -> "Sbagliata, ed eri sicuro" to colors.wrong
        AnswerVerdict.HONEST_MISS -> "Sbagliata, ma lo sospettavi" to colors.wrong
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = verdictIcon(feedback.verdict),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
        }

        ProfessorBubble(text = feedback.professorLine)

        // Shown whatever the outcome: understanding why the right answer is right is the
        // point of the exercise, not a consolation prize for getting it wrong.
        SenseiCard {
            SectionHeader(text = "Perché è giusta")
            Text(
                text = feedback.explanation,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (!feedback.chosen.correct && !feedback.chosen.rebuttal.isNullOrBlank()) {
            SenseiCard {
                SectionHeader(text = "Perché la tua era sbagliata")
                Text(
                    text = feedback.chosen.rebuttal!!,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        feedback.realWorld?.let { realWorld ->
            SenseiCard {
                SectionHeader(text = "Nel mondo reale")
                Text(
                    text = realWorld,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        feedback.controlQuestion?.let { control ->
            SenseiCard {
                SectionHeader(text = "Domanda di controllo")
                Text(
                    text = control,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Non devi rispondermi adesso: pensaci.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "+${feedback.experiencePoints} XP",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "padronanza ${feedback.masteryPercent}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SenseiPrimaryButton(
            text = if (isLast) "Vediamo com'è andata" else "Avanti",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun verdictIcon(verdict: AnswerVerdict): String = when (verdict) {
    AnswerVerdict.SOLID -> "✓"
    AnswerVerdict.CORRECT_BUT_FRAGILE -> "~"
    AnswerVerdict.SUSPECTED_LUCK -> "🎲"
    AnswerVerdict.ROOTED_MISCONCEPTION -> "✗"
    AnswerVerdict.HONEST_MISS -> "✗"
}

@Composable
private fun SummaryBlock(summary: QuizSummary?, onFinished: () -> Unit) {
    if (summary == null) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Come è andata",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        SenseiCard {
            SummaryRow("Risposte solide", summary.solid.toString(), SenseiTheme.colors.correct)
            SummaryRow("Corrette ma incerte", summary.lucky.toString(), SenseiTheme.colors.warning)
            SummaryRow("Sbagliate", summary.wrong.toString(), SenseiTheme.colors.wrong)
            SummaryRow("Esperienza", "+${summary.experiencePoints} XP", MaterialTheme.colorScheme.primary)
            SummaryRow(
                "Padronanza media",
                "${summary.averageMasteryPercent}%",
                MaterialTheme.colorScheme.onSurface,
            )
        }

        // A review is not an exam: there is no module to pass, so saying "non ancora" would
        // be judging the student against a bar this session never set.
        if (summary.isReview) {
            SenseiCard {
                Text(
                    text = if (summary.stillDue > 0) "Ne restano ${summary.stillDue}" else "Sei in pari",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (summary.stillDue > 0) {
                        SenseiTheme.colors.warning
                    } else {
                        SenseiTheme.colors.correct
                    },
                )
                Text(
                    text = if (summary.stillDue > 0) {
                        "Ho fermato la sessione al tempo che mi hai detto di avere. " +
                            "Gli altri restano in coda: non scappano."
                    } else {
                        "Nessun ripasso in scadenza. Quelli che hai sbagliato adesso " +
                            "tornano prima degli altri — è il loro mestiere."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            SenseiPrimaryButton(
                text = "Torno in aula",
                onClick = onFinished,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        SenseiCard {
            if (summary.gatePassed) {
                Text(
                    text = "✓ Modulo superato",
                    style = MaterialTheme.typography.titleMedium,
                    color = SenseiTheme.colors.correct,
                )
                Text(
                    text = "Padronanza sopra l'80% e nessuna abilità sotto il 60%. " +
                        "Non te l'ho regalato.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text = "Non ancora",
                    style = MaterialTheme.typography.titleMedium,
                    color = SenseiTheme.colors.warning,
                )
                Text(
                    text = if (summary.weakSkills.isEmpty()) {
                        "Ci siamo quasi: serve una media più alta. Ripassiamo e riproviamo."
                    } else {
                        "Da rivedere: ${summary.weakSkills.joinToString(", ")}. " +
                            "Non tutto: solo quello."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        SenseiPrimaryButton(
            text = "Torno in aula",
            onClick = onFinished,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String, accent: Color) {
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
            color = accent,
        )
    }
}
