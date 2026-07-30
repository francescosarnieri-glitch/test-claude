package com.cybersensei.academy.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersensei.academy.core.ui.theme.SenseiPalette
import com.cybersensei.academy.core.ui.theme.SenseiTheme

/**
 * The four metals a medal can be made of.
 *
 * Kept here rather than in the curriculum module because it is a drawing instruction, not a
 * rule: the engine decides which tier a trophy is, this decides what that looks like.
 */
enum class MedalMetal(val face: Color, val rim: Color, val onFace: Color) {
    BRONZE(SenseiPalette.Bronze, SenseiPalette.BronzeDeep, Color(0xFF2A1708)),
    SILVER(SenseiPalette.Silver, SenseiPalette.SilverDeep, Color(0xFF1B2229)),
    GOLD(SenseiPalette.Gold, SenseiPalette.GoldDeep, Color(0xFF3A2A02)),
    PLATINUM(SenseiPalette.Platinum, SenseiPalette.PlatinumDeep, Color(0xFF0A2926)),
}

/**
 * A medal, drawn rather than drawn *by somebody*.
 *
 * There is no artist on this project, and a wall of forty-four hand-made images would also be
 * forty-four files in the APK that have to be redrawn every time a trophy changes. A ribbon, a
 * ring of metal and a glyph in the middle cost nothing, scale to any screen, and look the same
 * on a phone from 2016.
 *
 * A locked medal keeps the exact silhouette of an unlocked one — same ribbon, same ring, same
 * size — because that is what makes the wall legible at a glance: the shape says "trophy", the
 * metal says "yours". Only the colour and the glyph change.
 */
@Composable
fun TrophyMedal(
    glyph: String,
    metal: MedalMetal,
    earned: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    /** Read out by screen readers in place of the drawing. */
    label: String? = null,
) {
    val lockedFace = SenseiTheme.colors.medalLocked
    val lockedRim = SenseiTheme.colors.onMedalLocked
    val face = if (earned) metal.face else lockedFace
    val rim = if (earned) metal.rim else lockedRim

    Box(
        modifier = modifier
            .size(size)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val side = this.size.minDimension
            val ribbonTop = side * RIBBON_TOP
            val ribbonWidth = side * RIBBON_WIDTH
            val discRadius = side * DISC_RADIUS
            val centre = Offset(side / 2f, side * DISC_CENTRE_Y)

            // The two ribbon tails, drawn first so the disc sits on top of them.
            listOf(-1f, 1f).forEach { direction ->
                val path = Path().apply {
                    moveTo(side / 2f + direction * ribbonWidth * 0.1f, ribbonTop)
                    lineTo(side / 2f + direction * ribbonWidth, ribbonTop)
                    lineTo(side / 2f + direction * ribbonWidth * 0.55f, centre.y)
                    lineTo(side / 2f + direction * ribbonWidth * 0.05f, centre.y)
                    close()
                }
                drawPath(path, color = rim.copy(alpha = if (earned) 0.85f else 0.55f))
            }

            // The disc: a light-to-dark sweep so it reads as metal instead of as a flat circle.
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(face, rim),
                    start = Offset(centre.x - discRadius, centre.y - discRadius),
                    end = Offset(centre.x + discRadius, centre.y + discRadius),
                ),
                radius = discRadius,
                center = centre,
            )
            drawCircle(
                color = rim,
                radius = discRadius,
                center = centre,
                style = Stroke(width = side * RIM_WIDTH),
            )
            // The inner ring is what separates a medal from a coin.
            drawCircle(
                color = rim.copy(alpha = 0.45f),
                radius = discRadius * INNER_RING,
                center = centre,
                style = Stroke(width = side * INNER_RING_WIDTH),
            )
            // A single highlight in the top left, the cheapest way to suggest a curved surface.
            if (earned) {
                drawArc(
                    color = Color.White.copy(alpha = 0.25f),
                    startAngle = 170f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(centre.x - discRadius * 0.72f, centre.y - discRadius * 0.72f),
                    size = Size(discRadius * 1.44f, discRadius * 1.44f),
                    style = Stroke(width = side * 0.035f),
                )
            }
        }

        // The glyph rides in the middle of the disc, which sits below the ribbon — hence the
        // nudge down. A locked medal shows a question mark instead, never the real symbol:
        // the symbol is part of the reward.
        Text(
            text = if (earned) glyph else "?",
            fontSize = (size.value * GLYPH_SCALE).sp,
            color = if (earned) metal.onFace else lockedRim,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.offset(y = size * (DISC_CENTRE_Y - 0.5f)),
        )
    }
}

private const val RIBBON_TOP = 0.02f
private const val RIBBON_WIDTH = 0.28f
private const val DISC_RADIUS = 0.33f
private const val DISC_CENTRE_Y = 0.62f
private const val RIM_WIDTH = 0.055f
private const val INNER_RING = 0.74f
private const val INNER_RING_WIDTH = 0.02f
private const val GLYPH_SCALE = 0.30f
