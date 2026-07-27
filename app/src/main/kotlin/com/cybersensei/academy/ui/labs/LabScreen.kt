package com.cybersensei.academy.ui.labs

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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.ChoiceRow
import com.cybersensei.academy.core.ui.component.ProfessorBubble
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiPrimaryButton
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.SenseiTextField
import com.cybersensei.academy.core.ui.component.TerminalBlock
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import com.cybersensei.academy.engine.labs.AttackScenario
import com.cybersensei.academy.engine.labs.MessageVerdict
import com.cybersensei.academy.engine.labs.PasswordVerdict
import com.cybersensei.academy.engine.labs.StrengthBand
import com.cybersensei.academy.engine.labs.TokenReading

@Composable
fun LabScreen(
    onFinished: () -> Unit,
    viewModel: LabViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val lab = uiState.lab
        if (lab == null) {
            Text(
                text = "Questo laboratorio non esiste.",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            SenseiTextButton(text = "Torno indietro", onClick = onFinished)
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = lab.icon, style = MaterialTheme.typography.displaySmall)
            Text(
                text = lab.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        uiState.loadError?.let {
            SenseiCard {
                Text(text = it, color = SenseiTheme.colors.wrong)
            }
        }

        when (lab) {
            Lab.PASSWORD_FORGE -> PasswordForge(uiState, viewModel)
            Lab.SUSPICIOUS_INBOX -> SuspiciousInbox(uiState, viewModel)
            Lab.CRYPTO_BENCH -> CryptoBenchLab(uiState, viewModel)
            Lab.TOKEN_ANATOMY -> TokenAnatomyLab(uiState, viewModel)
            Lab.ANOMALY_HUNT -> AnomalyHunt(uiState, viewModel)
        }

        SenseiTextButton(text = "Chiudi il laboratorio", onClick = onFinished, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// --- Password forge ---------------------------------------------------------------------

@Composable
private fun PasswordForge(state: LabUiState, viewModel: LabViewModel) {
    ProfessorBubble(
        text = "Scrivi qualcosa. Non è un campo di accesso, non esce da qui e non lo salvo " +
            "da nessuna parte — è un banco di prova. Guarda soprattutto l'ultima colonna.",
    )

    SenseiCard {
        SenseiTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChanged,
            label = "Prova una password",
        )
    }

    val verdict = state.passwordVerdict
    if (verdict == null || state.password.isEmpty()) return

    SenseiCard {
        val colour = bandColour(verdict.band)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Icon and word, never colour alone.
            Text(
                text = "${verdict.band.icon} ${verdict.band.italianName}",
                style = MaterialTheme.typography.titleLarge,
                color = colour,
            )
            Text(
                text = "${verdict.entropyBits.toInt()} bit",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "${verdict.length} caratteri su un alfabeto di ${verdict.alphabetSize} simboli.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionHeader(text = "Quanto regge, e contro chi")
    SenseiCard {
        AttackScenario.entries.forEach { scenario ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = scenario.italianName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = humanDuration(verdict.crackTimes.getValue(scenario)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = scenario.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    if (verdict.weaknesses.isNotEmpty()) {
        SectionHeader(text = "Perché quel numero è ottimista")
        SenseiCard {
            Text(
                text = "L'entropia presuppone che chi cerca proceda alla cieca. Ogni voce " +
                    "qui sotto è un motivo per cui non dovrebbe farlo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            verdict.weaknesses.forEach { weakness ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.titleMedium,
                        color = SenseiTheme.colors.warning,
                    )
                    Column {
                        Text(
                            text = weakness.italianName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = weakness.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// --- Suspicious inbox -------------------------------------------------------------------

@Composable
private fun SuspiciousInbox(state: LabUiState, viewModel: LabViewModel) {
    val inbox = state.inbox

    if (inbox.messages.isEmpty()) return

    if (inbox.finished) {
        SenseiCard {
            SectionHeader(text = "Come è andata")
            Text(
                text = "${inbox.right} su ${inbox.messages.size}.",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Quello che conta non è il punteggio: è che adesso sai dove guardare " +
                    "per primo — il dominio, letto da destra fino al primo slash.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SenseiPrimaryButton(
            text = "Rifacciamola",
            onClick = viewModel::restartInbox,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val message = inbox.current ?: return
    Text(
        text = "Messaggio ${inbox.index + 1} di ${inbox.messages.size}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SenseiCard {
        Text(
            text = message.from,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // The address in monospace: it is the thing to look at, and proportional type hides
        // exactly the kind of detail that matters here.
        TerminalBlock(text = message.fromAddress)
        Text(
            text = message.subject,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val revealed = inbox.revealed
    if (revealed == null) {
        MessageVerdict.entries.forEach { verdict ->
            ChoiceRow(
                text = verdict.italianName,
                selected = false,
                onClick = { viewModel.onInboxAnswer(verdict) },
            )
        }
        return
    }

    val right = revealed.isRight(message)
    SenseiCard {
        Text(
            text = if (right) "✓ Esatto: ${message.verdict.italianName}" else
                "✗ Era ${message.verdict.italianName}, hai detto ${revealed.chosen.italianName}",
            style = MaterialTheme.typography.titleMedium,
            color = if (right) SenseiTheme.colors.correct else SenseiTheme.colors.wrong,
        )
        Text(
            text = message.explanation,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (message.clues.isNotEmpty()) {
        SectionHeader(text = "Gli indizi che c'erano")
        SenseiCard {
            message.clues.forEach { clue ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TerminalBlock(text = clue.visible)
                    Text(
                        text = clue.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (clue.decisive) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (clue.decisive) {
                        Text(
                            text = "Questo da solo bastava.",
                            style = MaterialTheme.typography.labelMedium,
                            color = SenseiTheme.colors.warning,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }

    SenseiPrimaryButton(
        text = "Avanti",
        onClick = viewModel::onInboxNext,
        modifier = Modifier.fillMaxWidth(),
    )
}

// --- Crypto bench -----------------------------------------------------------------------

@Composable
private fun CryptoBenchLab(state: LabUiState, viewModel: LabViewModel) {
    val crypto = state.crypto

    ProfessorBubble(
        text = "Qui si tocca con mano. Cesare e XOR sono giocattoli storici e li trattiamo " +
            "come tali; l'impronta invece è quella vera, ed è dove voglio portarti.",
    )

    SectionHeader(text = "Cesare — il giocattolo")
    SenseiCard {
        SenseiTextField(
            value = crypto.plainText,
            onValueChange = viewModel::onCryptoInput,
            label = "Testo in chiaro",
        )
        Text(
            text = "Spostamento: ${crypto.caesarShift}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = crypto.caesarShift.toFloat(),
            onValueChange = { viewModel.onCaesarShift(it.toInt()) },
            valueRange = 1f..25f,
            steps = 23,
        )
        TerminalBlock(text = viewModel.caesarOutput())
        Text(
            text = "Adesso guarda perché non protegge niente: le possibilità sono venticinque, " +
                "e una di queste è italiano.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TerminalBlock(
            text = viewModel.caesarAttempts().joinToString("\n") { (shift, attempt) ->
                "%2d  %s".format(shift, attempt.take(40))
            },
        )
    }

    SectionHeader(text = "XOR — simmetrico davvero")
    SenseiCard {
        SenseiTextField(
            value = crypto.key,
            onValueChange = viewModel::onCryptoKey,
            label = "Chiave",
        )
        TerminalBlock(text = viewModel.xorOutput())
        Text(
            text = "Una sola chiave, entrambe le direzioni: riapplicandola allo stesso " +
                "risultato torna il testo di partenza. È il significato di «simmetrica».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionHeader(text = "L'impronta — e l'effetto valanga")
    SenseiCard {
        SenseiTextField(
            value = crypto.hashInput,
            onValueChange = viewModel::onHashInput,
            label = "Testo da cui calcolare l'impronta",
        )
        crypto.avalanche?.let { avalanche ->
            TerminalBlock(text = "«${crypto.hashInput}»\n${avalanche.first}")
            TerminalBlock(text = "«${crypto.hashInput}!»\n${avalanche.second}")
            Text(
                text = "Un carattere di differenza, e sono cambiati " +
                    "${avalanche.differingBits} bit su ${avalanche.totalBits} " +
                    "(${avalanche.percentChanged}%).",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Non c'è nessuna somiglianza fra le due, e non c'è nessun modo di " +
                    "tornare indietro: l'informazione non è nascosta, è stata distrutta.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SectionHeader(text = "A cosa serve il salt, se è pubblico")
    crypto.salted?.let { salted ->
        SenseiCard {
            TerminalBlock(
                text = "senza salt      ${salted.withoutSalt.take(32)}…\n" +
                    "salt ${salted.firstSalt}     ${salted.firstHash.take(32)}…\n" +
                    "salt ${salted.secondSalt}     ${salted.secondHash.take(32)}…",
            )
            Text(
                text = "Stessa identica password, tre risultati che non si somigliano. " +
                    "Il salt non è un segreto: serve a impedire che una tabella precalcolata " +
                    "valga per tutti, e a nascondere che due persone hanno la stessa password.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// --- Token anatomy ----------------------------------------------------------------------

@Composable
private fun TokenAnatomyLab(state: LabUiState, viewModel: LabViewModel) {
    ProfessorBubble(
        text = "Incolla un token, o prendi il mio. Poi guarda cosa riesci a leggere senza " +
            "avere nessuna chiave. Se qualcuno ti ha detto che un token è cifrato, " +
            "fra dieci secondi saprai che non è così.",
    )

    SenseiCard {
        SenseiTextField(
            value = state.tokenInput,
            onValueChange = viewModel::onTokenChanged,
            label = "Token",
        )
        SenseiPrimaryButton(
            text = "Aprilo",
            onClick = viewModel::readToken,
            enabled = state.tokenInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        SenseiTextButton(text = "Usa un token di esempio", onClick = viewModel::useExampleToken)
    }

    when (val reading = state.tokenReading) {
        null -> Unit
        is TokenReading.NotTheRightShape -> SenseiCard {
            Text(
                text = reading.reason,
                style = MaterialTheme.typography.bodyLarge,
                color = SenseiTheme.colors.warning,
            )
        }
        is TokenReading.Opened -> {
            SectionHeader(text = "Intestazione")
            TerminalBlock(text = reading.header)
            SectionHeader(text = "Contenuto")
            TerminalBlock(text = reading.payload)
            SectionHeader(text = "Cosa ci dice")
            SenseiCard {
                reading.observations.forEach { observation ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (observation.alarming) "!" else "·",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (observation.alarming) {
                                SenseiTheme.colors.warning
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column {
                            Text(
                                text = observation.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = observation.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            SenseiCard {
                Text(
                    text = "La firma non l'ho verificata, e non potrei: serve la chiave del " +
                        "server. Guardare un token non dice mai se è autentico — dice solo " +
                        "cosa contiene.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Anomaly hunt -----------------------------------------------------------------------

@Composable
private fun AnomalyHunt(state: LabUiState, viewModel: LabViewModel) {
    val hunt = state.hunt.hunt ?: return

    if (!state.hunt.judged) {
        ProfessorBubble(text = hunt.briefing)
        Text(
            text = "Righe accusate: ${state.hunt.selected.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val result = viewModel.huntResult()

    SenseiCard {
        hunt.lines.forEach { line ->
            val accused = line.id in state.hunt.selected
            val judged = state.hunt.judged
            val colour = when {
                !judged && accused -> SenseiTheme.colors.warning
                judged && line.guilty && accused -> SenseiTheme.colors.correct
                judged && line.guilty -> SenseiTheme.colors.wrong
                judged && accused -> SenseiTheme.colors.wrong
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val marker = when {
                !judged -> if (accused) "▸" else " "
                line.guilty && accused -> "✓"
                line.guilty -> "✗"
                accused -> "~"
                else -> " "
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (judged) Modifier else Modifier.background(
                            color = if (accused) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            shape = RoundedCornerShape(4.dp),
                        ),
                    )
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = marker, style = MaterialTheme.typography.labelMedium, color = colour)
                Box(modifier = Modifier.weight(1f)) {
                    if (judged) {
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = colour,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        SenseiTextButton(
                            text = line.text,
                            onClick = { viewModel.onLineToggled(line.id) },
                        )
                    }
                }
            }
            if (judged && line.note != null && (line.guilty || line.id in state.hunt.selected)) {
                Text(
                    text = line.note!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
            }
        }
    }

    if (!state.hunt.judged) {
        SenseiPrimaryButton(
            text = "Ho finito, guardiamo",
            onClick = viewModel::judgeHunt,
            enabled = state.hunt.selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    result?.let {
        SenseiCard {
            SectionHeader(text = "Esito")
            Text(
                text = "Trovate ${it.found.size} su ${hunt.guiltyLines.size}. " +
                    "Accuse sbagliate: ${it.falseAccusations.size}.",
                style = MaterialTheme.typography.titleMedium,
                color = if (it.perfect) SenseiTheme.colors.correct else SenseiTheme.colors.warning,
            )
            if (it.falseAccusations.isNotEmpty()) {
                Text(
                    text = "Accusare tutto non è trovare: in un sistema vero ogni riga " +
                        "segnalata è un'ora di lavoro di qualcuno.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    ProfessorBubble(text = hunt.debriefing, animate = false)
    SenseiPrimaryButton(
        text = "Riprova",
        onClick = viewModel::restartHunt,
        modifier = Modifier.fillMaxWidth(),
    )
}

// --- Shared -------------------------------------------------------------------------------

@Composable
private fun bandColour(band: StrengthBand) = when (band) {
    StrengthBand.LAUGHABLE, StrengthBand.WEAK -> SenseiTheme.colors.wrong
    StrengthBand.FAIR -> SenseiTheme.colors.warning
    StrengthBand.STRONG, StrengthBand.EXCESSIVE -> SenseiTheme.colors.correct
}

/**
 * Durations a person can feel.
 *
 * "3.6e14 secondi" is a number; "più del tempo che ci resta" is an answer. The point of the
 * lab is the comparison between scenarios, and that only lands if both ends are readable.
 */
private fun humanDuration(seconds: Double): String = when {
    seconds < 1 -> "istantanea"
    seconds < 60 -> "${seconds.toInt()} secondi"
    seconds < 3_600 -> "${(seconds / 60).toInt()} minuti"
    seconds < 86_400 -> "${(seconds / 3_600).toInt()} ore"
    seconds < 2_592_000 -> "${(seconds / 86_400).toInt()} giorni"
    seconds < 31_536_000 -> "${(seconds / 2_592_000).toInt()} mesi"
    seconds < 31_536_000_000.0 -> "${(seconds / 31_536_000).toInt()} anni"
    seconds < 3.15e17 -> "milioni di anni"
    else -> "più dell'età dell'universo"
}
