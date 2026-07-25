package com.cybersensei.academy.ui.onboarding

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.model.DailyBudget
import com.cybersensei.academy.core.model.LearningGoal
import com.cybersensei.academy.core.model.TutorTone
import com.cybersensei.academy.core.ui.component.ChoiceRow
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.SenseiTextField

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
    promptViewModel: OnboardingPromptViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.finished) {
        if (uiState.finished) onFinished()
    }

    val prompt = remember(uiState.step, uiState.name) {
        promptViewModel.promptFor(uiState.step, uiState.name.trim())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        LinearProgressIndicator(
            progress = { uiState.step.progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        ProfessorBubble(text = prompt)

        when (uiState.step) {
            OnboardingStep.WELCOME -> Unit

            OnboardingStep.NAME -> SenseiTextField(
                value = uiState.name,
                onValueChange = viewModel::onNameChanged,
                label = "Il tuo nome",
                imeAction = ImeAction.Next,
            )

            OnboardingStep.NICKNAME -> SenseiTextField(
                value = uiState.nickname,
                onValueChange = viewModel::onNicknameChanged,
                label = "Come devo chiamarti",
                supportingText = "Lascia vuoto per usare «${uiState.name.trim()}»",
                imeAction = ImeAction.Next,
            )

            OnboardingStep.BIRTH_DATE -> BirthDateStep(uiState, viewModel)

            OnboardingStep.GOAL -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LearningGoal.entries.forEach { goal ->
                    ChoiceRow(
                        text = goal.italianName,
                        selected = uiState.goal == goal,
                        onClick = { viewModel.onGoalChosen(goal) },
                    )
                }
            }

            OnboardingStep.TONE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TutorTone.entries.forEach { tone ->
                    ChoiceRow(
                        text = tone.italianName,
                        description = tone.description,
                        selected = uiState.tone == tone,
                        onClick = { viewModel.onToneChosen(tone) },
                    )
                }
            }

            OnboardingStep.BUDGET -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DailyBudget.entries.forEach { budget ->
                    ChoiceRow(
                        text = budget.italianName,
                        selected = uiState.budget == budget,
                        onClick = { viewModel.onBudgetChosen(budget) },
                    )
                }
            }

            OnboardingStep.PACT -> PactStep(uiState, viewModel)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!uiState.step.isFirst) {
                SenseiTextButton(text = "Indietro", onClick = viewModel::back)
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }
            SenseiPrimaryButton(
                text = if (uiState.step == OnboardingStep.PACT) "Entra in aula" else "Avanti",
                onClick = viewModel::next,
                enabled = uiState.canAdvance,
            )
        }
    }
}

@Composable
private fun BirthDateStep(uiState: OnboardingUiState, viewModel: OnboardingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SenseiTextField(
                value = uiState.day,
                onValueChange = viewModel::onDayChanged,
                label = "Giorno",
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
                modifier = Modifier.weight(1f),
            )
            SenseiTextField(
                value = uiState.month,
                onValueChange = viewModel::onMonthChanged,
                label = "Mese",
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
                modifier = Modifier.weight(1f),
            )
            SenseiTextField(
                value = uiState.year,
                onValueChange = viewModel::onYearChanged,
                label = "Anno",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1.4f),
            )
        }

        uiState.dateError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        uiState.zodiacSign?.let { sign ->
            Text(
                text = "${sign.symbol} ${sign.italianName}. Me lo segno.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        SenseiTextButton(text = "Preferisco non dirlo", onClick = viewModel::onSkipBirthDate)
    }
}

@Composable
private fun PactStep(uiState: OnboardingUiState, viewModel: OnboardingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SenseiCard {
            Text(
                text = "Il patto dello studente",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PACT_CLAUSES.forEach { clause ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = clause,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (uiState.pactSigned) {
            Text(
                text = "✓ Firmato. Resta su questo telefono, e me lo ricorderò.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            SenseiPrimaryButton(
                text = "Firmo",
                onClick = viewModel::onPactSigned,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val PACT_CLAUSES = listOf(
    "Userò quello che imparo per difendere, mai per colpire.",
    "Non toccherò sistemi che non sono miei senza un'autorizzazione scritta.",
    "So che l'accesso abusivo è un reato anche senza danni e anche solo «per provare».",
    "Se troverò una falla, la segnalerò invece di sfruttarla.",
)
