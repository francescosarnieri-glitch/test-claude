package com.cybersensei.academy.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Route
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level sections, mirroring the "Interfaccia e schermate" table of PROGETTO.md. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    CLASSROOM(
        route = "aula",
        label = "Aula",
        selectedIcon = Icons.Filled.School,
        unselectedIcon = Icons.Outlined.School,
    ),
    PATH(
        route = "percorso",
        label = "Percorso",
        selectedIcon = Icons.Filled.Route,
        unselectedIcon = Icons.Outlined.Route,
    ),
    STUDY(
        route = "studio",
        label = "Studio",
        selectedIcon = Icons.Filled.Chat,
        unselectedIcon = Icons.Outlined.Chat,
    ),
    PROGRESS(
        route = "pagella",
        label = "Pagella",
        selectedIcon = Icons.Filled.Insights,
        unselectedIcon = Icons.Outlined.Insights,
    ),
}

/** Routes that are pushed on top of a top-level section rather than being one. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LESSON = "lezione/{moduleId}"
    const val QUIZ = "interrogazione/{moduleId}"

    fun lesson(moduleId: String) = "lezione/$moduleId"
    fun quiz(moduleId: String) = "interrogazione/$moduleId"
}
