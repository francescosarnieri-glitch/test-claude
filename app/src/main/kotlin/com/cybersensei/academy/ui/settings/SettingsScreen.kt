package com.cybersensei.academy.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.model.DailyBudget
import com.cybersensei.academy.core.model.LearningGoal
import com.cybersensei.academy.core.model.TutorTone
import com.cybersensei.academy.core.ui.component.ChoiceRow
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.SenseiTextField
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = uiState.profile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Impostazioni",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (profile == null) {
            Text(
                text = "Non c'è nessuno studente registrato.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SenseiTextButton(text = "Torno indietro", onClick = onBack)
            return@Column
        }

        // --- How the professor treats you ------------------------------------------------

        SectionHeader(text = "Come ti tratta il professore")
        var nickname by remember(profile.nickname) { mutableStateOf(profile.nickname) }
        SenseiCard {
            SenseiTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = "Come ti chiama",
                supportingText = "Lascialo vuoto e torna al tuo nome.",
            )
            SenseiTextButton(
                text = "Salva il nome",
                onClick = { viewModel.onNicknameChanged(nickname) },
            )
        }

        TutorTone.entries.forEach { tone ->
            ChoiceRow(
                text = tone.italianName,
                description = tone.description,
                selected = profile.tone == tone,
                onClick = { viewModel.onToneChosen(tone) },
            )
        }

        // --- Your time -------------------------------------------------------------------

        SectionHeader(text = "Quanto tempo hai")
        Text(
            text = "Non è una promessa da mantenere: serve a me per non farti sessioni " +
                "di ripasso più lunghe di quanto reggi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DailyBudget.entries.forEach { budget ->
            ChoiceRow(
                text = budget.italianName,
                selected = profile.dailyBudget == budget,
                onClick = { viewModel.onBudgetChosen(budget) },
            )
        }

        SectionHeader(text = "Perché sei qui")
        LearningGoal.entries.forEach { goal ->
            ChoiceRow(
                text = goal.italianName,
                selected = profile.goal == goal,
                onClick = { viewModel.onGoalChosen(goal) },
            )
        }

        // --- The one time the professor speaks first ---------------------------------------

        SectionHeader(text = "Il promemoria")
        ReminderSection(
            chosen = profile.reminderAt,
            onChosen = viewModel::onReminderChosen,
        )

        // --- The promise ------------------------------------------------------------------

        SectionHeader(text = "La promessa")
        SenseiCard {
            PromiseRow(
                "Nessun accesso a Internet",
                "L'app non chiede il permesso di rete: non è una dichiarazione, è il " +
                    "sistema operativo che glielo impedisce. Puoi verificarlo tu.",
            )
            PromiseRow(
                "Nessuna telemetria",
                "Niente analytics, niente segnalazioni di errore inviate altrove.",
            )
            PromiseRow(
                "Tutto resta qui",
                "Nome, risposte, progressi: sul tuo telefono e da nessun'altra parte. " +
                    "Se disinstalli l'app, spariscono con lei.",
            )
        }

        // --- Starting over ----------------------------------------------------------------

        SectionHeader(text = "Ricomincia da capo")
        val lost = uiState.lost
        SenseiCard {
            if (!uiState.confirmingReset) {
                Text(
                    text = "Cancella tutto e torna al primo giorno di scuola: il professore " +
                        "non ti conoscerà più.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SenseiTextButton(text = "Voglio ricominciare", onClick = viewModel::askToReset)
            } else {
                Text(
                    text = "Sei sicuro?",
                    style = MaterialTheme.typography.titleMedium,
                    color = SenseiTheme.colors.wrong,
                )
                // Naming what goes is the point: a generic warning is easy to tap through.
                if (lost != null) {
                    Text(
                        text = "Perderai " +
                            "${lost.lessonsCompleted} lezioni su ${viewModel.lessonsInSyllabus}, " +
                            "${lost.skillsMeasured} competenze misurate, " +
                            "${lost.experiencePoints} XP, " +
                            "${lost.badges} riconoscimenti, " +
                            "${lost.studyMinutes} minuti passati a scuola e un record di " +
                            "${lost.recordStreakDays} giorni di fila. " +
                            "Sei iscritto da ${lost.daysEnrolled} giorni.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "Non si può annullare, e non ne ho una copia da nessuna parte — " +
                        "è il rovescio della promessa qui sopra.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SenseiPrimaryButton(
                    text = "Cancella tutto",
                    onClick = viewModel::confirmReset,
                    modifier = Modifier.fillMaxWidth(),
                )
                SenseiTextButton(
                    text = "No, lascia stare",
                    onClick = viewModel::cancelReset,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SenseiTextButton(text = "Torno in aula", onClick = onBack, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Choosing when — and whether — the professor may knock.
 *
 * "Nessun promemoria" is first and is the default: the app never switches this on by itself.
 * On Android 13 and later, picking an hour is also the moment the system permission is
 * asked for, because asking before the student has expressed any interest is exactly the
 * pattern this course teaches them to distrust.
 */
@Composable
private fun ReminderSection(chosen: LocalTime?, onChosen: (LocalTime?) -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val askPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> enabled = granted },
    )

    SenseiCard {
        Text(
            text = "Un solo avviso al giorno, all'ora che scegli tu, e solo se quel giorno " +
                "non hai ancora studiato. Se hai già fatto la tua sessione resto zitto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (chosen != null && !enabled) {
            // Said plainly rather than left as a reminder that silently never arrives.
            Text(
                text = "⚠ Le notifiche di questa app sono disattivate nelle impostazioni di " +
                    "sistema: finché restano così, il promemoria non arriverà.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenseiTheme.colors.wrong,
            )
        }
    }

    ChoiceRow(
        text = "Nessun promemoria",
        description = "Vengo a scuola quando decido io.",
        selected = chosen == null,
        onClick = { onChosen(null) },
    )
    REMINDER_TIMES.forEach { (time, description) ->
        ChoiceRow(
            text = "Alle ${time.format(HOUR)}",
            description = description,
            selected = chosen == time,
            onClick = {
                onChosen(time)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !enabled) {
                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
}

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

/** A short list of sensible hours: a clock picker for a daily habit is friction, not choice. */
private val REMINDER_TIMES = listOf(
    LocalTime.of(8, 0) to "Prima di cominciare la giornata",
    LocalTime.of(13, 0) to "Nella pausa",
    LocalTime.of(18, 30) to "Rientrando",
    LocalTime.of(21, 0) to "Prima di chiudere la giornata",
)

@Composable
private fun PromiseRow(title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.titleMedium,
            color = SenseiTheme.colors.correct,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
