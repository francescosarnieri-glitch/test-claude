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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cybersensei.academy.core.ui.component.ComingSoonScreen
import com.cybersensei.academy.ui.classroom.ClassroomScreen
import com.cybersensei.academy.ui.navigation.TopLevelDestination

@Composable
fun CyberSenseiApp(
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
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
                    ClassroomScreen()
                }
                composable(TopLevelDestination.PATH.route) {
                    ComingSoonScreen(
                        title = "Il Percorso",
                        description = "La mappa dei quattro livelli, con i moduli che si " +
                            "sbloccano solo quando li hai davvero capiti.",
                        phase = "Fase 2",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(TopLevelDestination.STUDY.route) {
                    ComingSoonScreen(
                        title = "Lo Studio del Prof.",
                        description = "Qui potrai fare domande tue al professore e leggere " +
                            "le osservazioni che ha annotato su di te.",
                        phase = "Fase 1",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(TopLevelDestination.PROGRESS.route) {
                    ComingSoonScreen(
                        title = "La Pagella",
                        description = "Padronanza per abilità, punti deboli, tempo di studio " +
                            "e andamento settimanale.",
                        phase = "Fase 3",
                        modifier = Modifier.fillMaxSize(),
                    )
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
