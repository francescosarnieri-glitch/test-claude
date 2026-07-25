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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.theme.SenseiTheme

@Composable
fun PathScreen(
    onStartLesson: (String) -> Unit,
    onStartQuiz: (String) -> Unit,
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
                    Text(
                        text = when {
                            level.passed -> "✓"
                            level.available -> "●"
                            else -> "○"
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
                level.modules.forEach { module ->
                    SenseiCard {
                        SectionHeader(text = module.title)
                        Text(
                            text = module.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        module.lessons.forEach { lesson ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onStartLesson(lesson.id) }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = if (lesson.done) "✓" else "·",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (lesson.done) {
                                        SenseiTheme.colors.correct
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(
                                    text = lesson.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${lesson.minutes}′",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onStartQuiz(module.id) }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "?",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Interrogazione — ${module.questionCount} domande",
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
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
