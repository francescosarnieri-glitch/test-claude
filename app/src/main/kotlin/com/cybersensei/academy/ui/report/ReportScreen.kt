package com.cybersensei.academy.ui.report

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
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

@Composable
fun ReportScreen(
    onOpenDiploma: () -> Unit,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
            text = "Pagella",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        ProfessorBubble(text = uiState.professorLine)

        if (uiState.summary != null && !uiState.hasAnyData) {
            SenseiCard {
                SectionHeader(text = "Pagella ancora da scrivere")
                Text(
                    text = "Le percentuali qui sotto sono tutte a zero perché non hai ancora " +
                        "risposto: non vuol dire che sai zero, vuol dire che non ho ancora " +
                        "misurato nulla. Sono due cose diverse e non le confondo.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        uiState.summary?.let { SummaryCard(it) }

        if (uiState.toRevise.isNotEmpty()) {
            SectionHeader(text = "Da riprendere, in quest'ordine")
            SenseiCard {
                Text(
                    text = "Le competenze più fragili fra quelle che hai già affrontato. " +
                        "Un livello si supera solo quando nessuna resta indietro.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiState.toRevise.forEach { SkillLine(it) }
            }
        }

        if (uiState.recentDays.isNotEmpty()) {
            SectionHeader(text = "Le ultime due settimane")
            SenseiCard {
                AttendanceStrip(uiState.recentDays)
                val present = uiState.recentDays.count { it.studied }
                Text(
                    text = "$present giorni su ${uiState.recentDays.size}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeader(text = "Competenza per competenza")
        uiState.levels.forEach { level -> LevelCard(level) }

        // Always reachable, earned or not: the diploma screen is also where the requirements
        // are written down, and a target you cannot see is not a target.
        SenseiTextButton(
            text = "Il diploma",
            onClick = onOpenDiploma,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryCard(summary: Summary) {
    SenseiCard {
        SectionHeader(text = "In sintesi")
        StatLine("Padronanza media", "${summary.masteryPercent}%")
        StatLine("Competenze affrontate", "${summary.skillsTouched} / ${summary.skillsTotal}")
        StatLine("Lezioni completate", "${summary.lessonsDone} / ${summary.lessonsTotal}")
        StatLine("Tempo di studio", "${summary.studyMinutes} minuti")
        StatLine(
            label = "Giorni di fila",
            value = if (summary.recordStreakDays > summary.streakDays) {
                "${summary.streakDays} (record ${summary.recordStreakDays})"
            } else {
                summary.streakDays.toString()
            },
        )
        StatLine("Esperienza", "${summary.experiencePoints} XP")
        StatLine("Riconoscimenti", "${summary.badgesEarned} / ${summary.badgesTotal}")
        if (summary.dueReviews > 0) {
            StatLine("Ripassi in scadenza", summary.dueReviews.toString(), warning = true)
        }
    }
}

@Composable
private fun LevelCard(level: LevelReport) {
    SenseiCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = level.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = level.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The state is a word before it is a colour: a report read in greyscale, or by a
            // screen reader, must say the same thing.
            val (label, colour) = when {
                level.passed -> "Superato" to SenseiTheme.colors.correct
                level.unlocked -> "In corso" to SenseiTheme.colors.info
                else -> "Bloccato" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colour,
            )
        }

        Text(
            text = "Media ${level.averagePercent}% · " +
                "${level.modulesPassed} moduli superati su ${level.modules.size}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        level.modules.forEach { module ->
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (module.passed) "${module.averagePercent}% ✓" else "${module.averagePercent}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (module.passed) {
                        SenseiTheme.colors.correct
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            module.skills.forEach { SkillLine(it) }
        }
    }
}

@Composable
private fun SkillLine(skill: SkillRow) {
    val colour = when (skill.state) {
        SkillState.SOLID -> SenseiTheme.colors.correct
        SkillState.TO_CONSOLIDATE -> SenseiTheme.colors.warning
        SkillState.FRAGILE -> SenseiTheme.colors.wrong
        SkillState.UNTOUCHED -> MaterialTheme.colorScheme.outline
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = "${skill.label}: ${skill.state.label}, ${skill.percent}%"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${skill.state.icon} ${skill.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (skill.state == SkillState.UNTOUCHED) {
                    skill.state.label
                } else {
                    "${skill.percent}%"
                },
                style = MaterialTheme.typography.labelMedium,
                color = colour,
            )
        }
        MasteryBar(percent = skill.percent, colour = colour)
    }
}

/**
 * A plain bar rather than a progress indicator: this is a measurement, not something loading,
 * and it is announced by the row's own description instead of twice over.
 */
@Composable
private fun MasteryBar(percent: Int, colour: Color) {
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

/** Fourteen squares: the gaps are the point, not the total. */
@Composable
private fun AttendanceStrip(days: List<StudyDay>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Presenze: " +
                    days.count { it.studied } + " giorni su " + days.size
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { day ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clearAndSetSemantics {}
                    .background(
                        color = if (day.studied) {
                            SenseiTheme.colors.correct
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // A tick, so presence is not carried by the colour alone.
                if (day.studied) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = SenseiTheme.colors.onCorrect,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, warning: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (warning) {
                Text(
                    text = "!",
                    style = MaterialTheme.typography.titleMedium,
                    color = SenseiTheme.colors.warning,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (warning) {
                    SenseiTheme.colors.warning
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
