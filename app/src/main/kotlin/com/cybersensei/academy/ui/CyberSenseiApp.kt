package com.cybersensei.academy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cybersensei.academy.ui.capstone.CapstoneScreen
import com.cybersensei.academy.ui.classroom.ClassroomScreen
import com.cybersensei.academy.ui.labs.LabScreen
import com.cybersensei.academy.ui.lesson.LessonScreen
import com.cybersensei.academy.ui.navigation.Routes
import com.cybersensei.academy.ui.navigation.TopLevelDestination
import com.cybersensei.academy.ui.onboarding.OnboardingScreen
import com.cybersensei.academy.ui.path.PathScreen
import com.cybersensei.academy.ui.quiz.QuizScreen
import com.cybersensei.academy.ui.report.ReportScreen
import com.cybersensei.academy.ui.settings.SettingsScreen
import com.cybersensei.academy.ui.study.StudyScreen

@Composable
fun CyberSenseiApp(
    navController: NavHostController = rememberNavController(),
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val destination by rootViewModel.startDestination.collectAsStateWithLifecycle()

    when (destination) {
        // The very first frames, before the database has answered. Deliberately blank:
        // a spinner for a few milliseconds is worse than nothing.
        StartDestination.UNKNOWN -> Box(modifier = Modifier.fillMaxSize())
        StartDestination.ENROLMENT -> OnboardingScreen(onFinished = {})
        StartDestination.SCHOOL -> School(navController)
    }
}

@Composable
private fun School(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val showBottomBar = TopLevelDestination.entries.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showBottomBar) return@Scaffold
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == destination.route
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = {
                            Icon(
                                imageVector = if (selected) {
                                    destination.selectedIcon
                                } else {
                                    destination.unselectedIcon
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.CLASSROOM.route,
            ) {
                composable(TopLevelDestination.CLASSROOM.route) {
                    ClassroomScreen(
                        onStartLesson = { navController.navigate(Routes.lesson(it)) },
                        onOpenPath = { navController.navigateToTopLevel(TopLevelDestination.PATH) },
                        onStartReview = { navController.navigate(Routes.REVIEW) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }

                composable(TopLevelDestination.PATH.route) {
                    PathScreen(
                        onStartLesson = { navController.navigate(Routes.lesson(it)) },
                        onStartQuiz = { navController.navigate(Routes.quiz(it)) },
                        onStartExam = { level -> navController.navigate(Routes.exam(level)) },
                        onOpenLab = { labId -> navController.navigate(Routes.lab(labId)) },
                        onStartCapstone = { navController.navigate(Routes.CAPSTONE) },
                    )
                }

                composable(TopLevelDestination.STUDY.route) {
                    StudyScreen()
                }

                composable(TopLevelDestination.PROGRESS.route) {
                    ReportScreen()
                }

                composable(
                    route = Routes.LESSON,
                    arguments = listOf(navArgument(Routes.ARG_LESSON_ID) { type = NavType.StringType }),
                ) {
                    LessonScreen(
                        onFinished = { navController.popBackStack() },
                        onQuizRequested = { moduleId ->
                            navController.navigate(Routes.quiz(moduleId)) {
                                popUpTo(TopLevelDestination.CLASSROOM.route)
                            }
                        },
                    )
                }

                composable(
                    route = Routes.QUIZ,
                    arguments = listOf(navArgument(Routes.ARG_MODULE_ID) { type = NavType.StringType }),
                ) {
                    QuizScreen(onFinished = { navController.popBackStack() })
                }

                composable(Routes.REVIEW) {
                    QuizScreen(onFinished = { navController.popBackStack() })
                }

                composable(
                    route = Routes.EXAM,
                    arguments = listOf(navArgument(Routes.ARG_LEVEL) { type = NavType.StringType }),
                ) {
                    QuizScreen(onFinished = { navController.popBackStack() })
                }

                composable(
                    route = Routes.LAB,
                    arguments = listOf(navArgument(Routes.ARG_LAB_ID) { type = NavType.StringType }),
                ) {
                    LabScreen(onFinished = { navController.popBackStack() })
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.CAPSTONE) {
                    CapstoneScreen(onFinished = { navController.popBackStack() })
                }
            }
        }
    }
}

/**
 * Standard top-level navigation: one entry per section on the back stack, state preserved
 * when the student jumps between sections and comes back.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
