package com.cybersensei.academy.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Night terminal" palette: deep blue-black, mint for what is safe, amber for what deserves
 * attention, red for danger. Colour is never the only carrier of meaning — every state also
 * has an icon and a label, so the app stays readable for colour-blind students.
 */
object SenseiPalette {
    // Night (default)
    val NightDeep = Color(0xFF070B10)
    val NightSurface = Color(0xFF0F1620)
    val NightSurfaceHigh = Color(0xFF17212E)
    val NightOutline = Color(0xFF2A3644)

    // Day
    val DayBackground = Color(0xFFF6F8FA)
    val DaySurface = Color(0xFFFFFFFF)
    val DaySurfaceHigh = Color(0xFFEDF1F5)
    val DayOutline = Color(0xFFCBD5E0)

    // Brand
    val Mint = Color(0xFF3DDC97)
    val MintDeep = Color(0xFF0F7A57)
    val MintDim = Color(0xFF1B3A30)
    val Amber = Color(0xFFFFB86B)
    val AmberDeep = Color(0xFF9A5B00)
    val Danger = Color(0xFFFF6B6B)
    val DangerDeep = Color(0xFFB3261E)
    val Info = Color(0xFF6BB8FF)

    // Text
    val TextNight = Color(0xFFE6EDF3)
    val TextNightMuted = Color(0xFF98A6B5)
    val TextDay = Color(0xFF101821)
    val TextDayMuted = Color(0xFF56636F)
}

/** Semantic colours the pedagogy needs and Material 3 does not provide out of the box. */
data class SenseiSemanticColors(
    val correct: Color,
    val onCorrect: Color,
    val wrong: Color,
    val onWrong: Color,
    val warning: Color,
    val info: Color,
    val terminalBackground: Color,
    val terminalText: Color,
    val professorBubble: Color,
    val onProfessorBubble: Color,
    val lockedContent: Color,
)

internal val NightSemanticColors = SenseiSemanticColors(
    correct = SenseiPalette.Mint,
    onCorrect = SenseiPalette.NightDeep,
    wrong = SenseiPalette.Danger,
    onWrong = SenseiPalette.NightDeep,
    warning = SenseiPalette.Amber,
    info = SenseiPalette.Info,
    terminalBackground = Color(0xFF04070A),
    terminalText = SenseiPalette.Mint,
    professorBubble = SenseiPalette.NightSurfaceHigh,
    onProfessorBubble = SenseiPalette.TextNight,
    lockedContent = Color(0xFF3A4653),
)

internal val DaySemanticColors = SenseiSemanticColors(
    correct = SenseiPalette.MintDeep,
    onCorrect = Color.White,
    wrong = SenseiPalette.DangerDeep,
    onWrong = Color.White,
    warning = SenseiPalette.AmberDeep,
    info = Color(0xFF0B5FA5),
    terminalBackground = Color(0xFF0F1620),
    terminalText = SenseiPalette.Mint,
    professorBubble = SenseiPalette.DaySurfaceHigh,
    onProfessorBubble = SenseiPalette.TextDay,
    lockedContent = Color(0xFF9AA7B4),
)
