package com.cybersensei.academy.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.School
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
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
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

    // The placeholder names are read back by the ViewModels through SavedStateHandle, so
    // they must match ARG_LESSON_ID / ARG_MODULE_ID exactly — a lesson route declaring
    // {moduleId} is what once brought the whole navigation graph down on entering.
    const val ARG_LESSON_ID = "lessonId"
    const val ARG_MODULE_ID = "moduleId"

    const val LESSON = "lezione/{$ARG_LESSON_ID}"
    const val QUIZ = "interrogazione/{$ARG_MODULE_ID}"

    /** The final exercise. No arguments: there is one incident, and it is the same for everyone. */
    const val CAPSTONE = "incidente"

    fun lesson(lessonId: String) = "lezione/$lessonId"
    fun quiz(moduleId: String) = "interrogazione/$moduleId"
}
