package com.cybersensei.academy.ui.trophies

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.curriculum.TrophyTier
import com.cybersensei.academy.core.ui.component.MedalMetal
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.component.SenseiTextButton
import com.cybersensei.academy.core.ui.component.TrophyMedal
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which metal a tier is made of. The only place the two vocabularies meet. */
internal val TrophyTier.metal: MedalMetal
    get() = when (this) {
        TrophyTier.BRONZE -> MedalMetal.BRONZE
        TrophyTier.SILVER -> MedalMetal.SILVER
        TrophyTier.GOLD -> MedalMetal.GOLD
        TrophyTier.PLATINUM -> MedalMetal.PLATINUM
    }

private val dayMonthYear = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)

@Composable
fun TrophyScreen(
    onBack: () -> Unit,
    viewModel: TrophyViewModel = hiltViewModel(),
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
            text = "Trofei",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        SenseiCard {
            Text(
                text = "${uiState.earned} su ${uiState.total}",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (uiState.earned == 0) {
                    "Nessuno ancora. Sono tutti qui sotto, con scritto come si prendono: " +
                        "nessuno arriva per aver aperto una schermata o per il tempo passato."
                } else {
                    "Il ${uiState.percent}% della bacheca. Toccane uno per sapere cos'è e, " +
                        "se ce l'hai, quando l'hai preso."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgressBar(percent = uiState.percent)
        }

        uiState.shelves.forEach { shelf ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(text = "${shelf.family.italianName} · ${shelf.earned}/${shelf.total}")
                Text(
                    text = shelf.family.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shelf.rows.chunked(COLUMNS).forEach { rowOfThree ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowOfThree.forEach { row ->
                            MedalCell(
                                row = row,
                                onClick = { viewModel.open(row) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Empty cells keep the last row's medals the same width as the others,
                        // instead of letting two of them stretch across the screen.
                        repeat(COLUMNS - rowOfThree.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        SenseiTextButton(text = "Torna indietro", onClick = onBack)
    }

    // A dialog rather than a bottom sheet: the sheet is still an experimental Material API, and
    // nothing else in this app opts into one. A card that comes up over the wall also matches
    // what a trophy popping open is supposed to feel like.
    uiState.opened?.let { row ->
        Dialog(onDismissRequest = viewModel::close) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 24.dp),
                ) {
                    TrophyDetail(row)
                    SenseiTextButton(
                        text = "Chiudi",
                        onClick = viewModel::close,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MedalCell(
    row: TrophyRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TrophyMedal(
            glyph = row.trophy.icon,
            metal = row.trophy.tier.metal,
            earned = row.earned,
            size = 68.dp,
            label = if (row.earned) {
                "${row.trophy.name}, trofeo di ${row.trophy.tier.italianName}, conquistato"
            } else {
                "${row.trophy.name}, trofeo di ${row.trophy.tier.italianName}, ancora da conquistare"
            },
        )
        Text(
            text = row.trophy.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = if (row.earned) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // Never colour alone: a locked medal also says so in words, and a won one says its metal.
        Text(
            text = if (row.earned) row.trophy.tier.italianName else "da prendere",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrophyDetail(row: TrophyRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TrophyMedal(
            glyph = row.trophy.icon,
            metal = row.trophy.tier.metal,
            earned = row.earned,
            size = 108.dp,
        )
        Text(
            text = row.trophy.name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${row.trophy.family.italianName} · ${row.trophy.tier.italianName}" +
                if (row.earned) "" else " · ancora da conquistare",
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
        )

        SenseiCard {
            Text(
                text = "Come si prende",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.trophy.howToEarn,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            row.progress?.takeIf { !row.earned && it.target > 0 }?.let { progress ->
                Text(
                    text = "Sei a ${progress.current} su ${progress.target}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgressBar(percent = progress.percent)
            }
        }

        // The meaning of a trophy is part of the reward, so it arrives with the medal.
        if (row.earned) {
            SenseiCard {
                Text(
                    text = "Cosa significa",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.trophy.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                row.earnedOn?.let { day ->
                    Text(
                        text = "Conquistato il ${day.format(dayMonthYear)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SenseiTheme.colors.correct,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(percent: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(4.dp),
            ) {}
        }
    }
}

/**
 * Three medals to a row.
 *
 * Four is too many for the names to stay on one line on a narrow phone, and two makes the wall
 * twice as long to scroll — which for the family with nine trophies is the difference between
 * seeing the shelf and hunting for it.
 */
private const val COLUMNS = 3
