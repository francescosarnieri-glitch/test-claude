package com.cybersensei.academy.ui.path

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import com.cybersensei.academy.ui.labs.Lab

@Composable
fun PathScreen(
    onStartLesson: (String) -> Unit,
    onStartQuiz: (String) -> Unit,
    onStartExam: (Int) -> Unit,
    onOpenLab: (String) -> Unit,
    onStartCapstone: () -> Unit,
    viewModel: PathViewModel = hiltViewModel(),
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
            text = "Il percorso",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        uiState.levels.forEach { level ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Lo stesso lucchetto delle lezioni e delle interrogazioni: una regola
                    // sola, riconoscibile a colpo d'occhio in tutta l'applicazione.
                    Text(
                        text = when {
                            level.passed -> "✓"
                            level.available -> "●"
                            else -> "🔒"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (level.available) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            SenseiTheme.colors.lockedContent
                        },
                    )
                    Text(
                        text = "${level.name} — ${level.subtitle}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (level.available) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            SenseiTheme.colors.lockedContent
                        },
                    )
                }

                level.lockedReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SenseiTheme.colors.lockedContent,
                    )
                }

                if (!level.available) return@Column
                level.modules.forEach { module -> ModuleCard(module, onStartLesson, onStartQuiz) }

                if (level.available) {
                    Lab.forLevel(level.order).forEach { lab -> LabCard(lab, onOpenLab) }
                    ExamCard(level, onStartExam)
                }
            }
        }

        // The capstone sits after everything else, where it belongs, and is offered rather
        // than locked: telling someone what it assumes is more useful than refusing entry.
        SectionHeader(text = "Prova finale")
        SenseiCard(
            modifier = Modifier.clickable { onStartCapstone() },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "🚨", style = MaterialTheme.typography.headlineSmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "L'Incidente",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Una notte intera, decisione per decisione. Non si risponde " +
                            "a domande: si decide, e ogni scelta cambia il seguito. " +
                            "Alla fine il professore rivede la notte con te.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * One module of the programme, opened one step at a time.
 *
 * A module still behind the student's own shows its name and what opens it, and nothing else:
 * listing four lessons that cannot be tapped would be saying no four times over, and hiding
 * the module entirely would take away the map. It is the same shape the levels above use, for
 * the same reason.
 */
@Composable
private fun ModuleCard(
    module: ModuleRow,
    onStartLesson: (String) -> Unit,
    onStartQuiz: (String) -> Unit,
) {
    SenseiCard {
        SectionHeader(text = module.title)
        Text(
            text = module.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!module.unlocked) {
            LockedLine(text = "Si apre quando avrai finito il modulo precedente.")
            return@SenseiCard
        }

        module.lessons.forEach { lesson -> LessonRowView(lesson, onStartLesson) }

        // L'interrogazione chiude il modulo: e' l'ultima riga perche' e' l'ultima cosa da fare.
        val label = "Interrogazione — ${module.questionCount} domande"
        if (module.quizUnlocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStartQuiz(module.id) }
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = "$label. ${module.masteryPercent}%." },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${module.masteryPercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = "Chiusa. $label." },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "🔒", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SenseiTheme.colors.lockedContent,
                    modifier = Modifier.weight(1f),
                )
            }
            LockedLine(text = "Si apre quando avrai letto le lezioni di questo modulo.")
        }
    }
}

/**
 * One lesson.
 *
 * Three states, each with its own glyph as well as its own colour, because a padlock has to
 * survive greyscale and a screen reader: read, next up, closed.
 */
@Composable
private fun LessonRowView(lesson: LessonRow, onStartLesson: (String) -> Unit) {
    val state = when {
        lesson.done -> "Fatta"
        lesson.unlocked -> "Da fare"
        else -> "Chiusa"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (lesson.unlocked) {
                    Modifier.clickable { onStartLesson(lesson.id) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 6.dp)
            .semantics {
                contentDescription = "$state. ${lesson.title}. ${lesson.minutes} minuti."
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = when {
                lesson.done -> "✓"
                lesson.unlocked -> "▸"
                else -> "🔒"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                lesson.done -> SenseiTheme.colors.correct
                lesson.unlocked -> MaterialTheme.colorScheme.primary
                else -> SenseiTheme.colors.lockedContent
            },
        )
        Text(
            text = lesson.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (lesson.unlocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                SenseiTheme.colors.lockedContent
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${lesson.minutes}′",
            style = MaterialTheme.typography.bodyMedium,
            color = if (lesson.unlocked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                SenseiTheme.colors.lockedContent
            },
        )
    }
}

/** Why something is closed, said in the same words everywhere in the app. */
@Composable
private fun LockedLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = SenseiTheme.colors.lockedContent,
    )
}

/**
 * The exam for a level.
 *
 * Offered only once every lesson is done, because an exam sat before the material has been
 * read measures nothing and teaches the student that the exam is noise. Passing it is shown
 * separately from the level being unlocked: the gate opens on accumulated mastery, the exam
 * is one sitting that covers everything at once, and conflating them would let a good
 * average stand in for having been examined.
 */
@Composable
private fun ExamCard(level: LevelRow, onStartExam: (Int) -> Unit) {
    SenseiCard(
        modifier = if (level.lessonsFinished) {
            Modifier.clickable { onStartExam(level.order) }
        } else {
            Modifier
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when {
                    level.examPassed -> "🎓"
                    level.lessonsFinished -> "📋"
                    else -> "🔒"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Esame del livello ${level.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (level.lessonsFinished) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        SenseiTheme.colors.lockedContent
                    },
                )
                Text(
                    text = when {
                        level.examPassed ->
                            "Superato. Puoi rifarlo quando vuoi: le domande cambiano."
                        !level.lessonsFinished ->
                            "Si apre quando avrai finito tutte le lezioni del livello."
                        else ->
                            "Una domanda per ogni competenza del livello, tutte in fila. " +
                                "Serve il 75% e nessun modulo sotto il 60% — la media da sola non basta."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (level.lessonsFinished) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        SenseiTheme.colors.lockedContent
                    },
                )
            }
        }
    }
}

/** A workshop, offered next to the level whose material it exercises. */
@Composable
private fun LabCard(lab: Lab, onOpenLab: (String) -> Unit) {
    SenseiCard(modifier = Modifier.clickable { onOpenLab(lab.id) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = lab.icon, style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Laboratorio — ${lab.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = lab.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
