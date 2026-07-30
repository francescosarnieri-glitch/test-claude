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
    const val ARG_LEVEL = "level"

    const val LESSON = "lezione/{$ARG_LESSON_ID}"
    const val QUIZ = "interrogazione/{$ARG_MODULE_ID}"

    /**
     * The interrogation that follows one lesson, which asks about that lesson alone.
     *
     * A separate route from the module one on purpose: they are different papers. The module
     * interrogation closes a chapter, this one checks what was just read — and before it
     * existed the student got the same nine questions after each of a module's four lessons.
     */
    const val LESSON_QUIZ = "interrogazione/lezione/{$ARG_LESSON_ID}"

    const val ARG_CASE_ID = "casoId"

    /** One route for all the cases: which one is an argument, not a screen each. */
    const val CAPSTONE = "incidente/{$ARG_CASE_ID}"

    /**
     * A review session. Deliberately the quiz route without a module: a review *is* an
     * interrogation, only the questions are chosen by the scheduler instead of by subject.
     */
    const val REVIEW = "ripasso"

    /** Profile, the professor's tone, and the way out of the school. */
    const val SETTINGS = "impostazioni"

    /** The certificate, and — until it is earned — the list of what is still missing. */
    const val DIPLOMA = "diploma"

    /**
     * The trophy wall.
     *
     * A screen of its own rather than a strip inside the classroom: forty-four medals under the
     * professor's greeting would push everything the student came for below the fold, and the
     * ones still locked — which are the point of a wall — would never fit at all.
     */
    const val TROPHIES = "trofei"

    const val ARG_LAB_ID = "labId"

    /** One route for all the workshops: which one is an argument, not a screen each. */
    const val LAB = "laboratorio/{$ARG_LAB_ID}"

    fun lab(labId: String) = "laboratorio/$labId"

    /** The exam for one level. Same screen as an interrogation, different paper. */
    const val EXAM = "esame/{$ARG_LEVEL}"

    fun exam(level: Int) = "esame/$level"

    fun capstone(caseId: String) = "incidente/$caseId"

    fun lesson(lessonId: String) = "lezione/$lessonId"
    fun quiz(moduleId: String) = "interrogazione/$moduleId"
    fun lessonQuiz(lessonId: String) = "interrogazione/lezione/$lessonId"
}
