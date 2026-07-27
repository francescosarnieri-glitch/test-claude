package com.cybersensei.academy.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cybersensei.academy.core.ui.Professor
import com.cybersensei.academy.core.ui.R
import com.cybersensei.academy.core.ui.theme.CyberSenseiTheme
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import kotlinx.coroutines.delay

/**
 * The professor's face.
 *
 * It used to be his initials in a circle, which was honest but anonymous — and the whole
 * product rests on the student feeling that somebody specific is talking to them. Same
 * portrait as the launcher icon on purpose: the face that sits on the home screen is the
 * face that speaks in the bubble.
 */
@Composable
fun ProfessorAvatar(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    speaking: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "avatar")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary.copy(
                    alpha = if (speaking) pulse else 0.75f,
                ),
                shape = CircleShape,
            )
            .clearAndSetSemantics { contentDescription = Professor.NAME },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.prof_hackstein),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                // Clipped to the circle it sits in, otherwise the hair spills over the ring.
                .clip(CircleShape),
        )
    }
}

/**
 * A line spoken by Prof. Hackstein White.
 *
 * The typewriter effect is not decoration: it is what makes a pre-written sentence feel like
 * it is being thought up on the spot. Tapping the bubble reveals the whole text at once, so
 * nobody is ever forced to wait for it.
 */
@Composable
fun ProfessorBubble(
    text: String,
    modifier: Modifier = Modifier,
    speakerName: String = Professor.NAME,
    animate: Boolean = true,
    charDelayMillis: Long = 18L,
) {
    val colors = SenseiTheme.colors
    val isPreview = LocalInspectionMode.current
    var revealed by remember(text) { mutableIntStateOf(if (animate && !isPreview) 0 else text.length) }

    LaunchedEffect(text, animate) {
        if (!animate || isPreview) return@LaunchedEffect
        while (revealed < text.length) {
            delay(charDelayMillis)
            revealed = (revealed + 1).coerceAtMost(text.length)
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ProfessorAvatar(speaking = revealed < text.length)
        Column(
            modifier = Modifier
                .background(
                    color = colors.professorBubble,
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = speakerName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                // The full sentence is always exposed to screen readers, never the partial one.
                text = text.take(revealed),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onProfessorBubble,
                modifier = Modifier.clearAndSetSemantics { contentDescription = text },
            )
            if (revealed < text.length) {
                Text(
                    text = "▌",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alpha(0.8f),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF070B10)
@Composable
private fun ProfessorBubblePreview() {
    CyberSenseiTheme(darkTheme = true) {
        ProfessorBubble(
            text = "Bentornato, Francesco. Ieri hai lasciato a metà la lezione sul DNS: " +
                "riprendiamo da lì, mi bastano quattro minuti del tuo tempo.",
            modifier = Modifier.padding(16.dp),
            animate = false,
        )
    }
}
