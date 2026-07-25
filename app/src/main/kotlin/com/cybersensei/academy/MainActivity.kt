package com.cybersensei.academy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cybersensei.academy.core.ui.theme.CyberSenseiTheme
import com.cybersensei.academy.ui.CyberSenseiApp
import com.cybersensei.academy.ui.DiagnosticsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // If the previous run died, show why instead of opening as though nothing happened.
        val previousCrash = CrashReporter.lastCrash(this)

        setContent {
            CyberSenseiTheme {
                var dismissed by remember { mutableStateOf(false) }
                val trace = if (dismissed) {
                    null
                } else {
                    // Either the app died last time, or something it needs failed to load.
                    listOfNotNull(previousCrash, StartupProblems.report())
                        .joinToString("\n\n")
                        .takeIf { it.isNotBlank() }
                }
                if (trace != null) {
                    DiagnosticsScreen(
                        trace = trace,
                        onDismiss = {
                            CrashReporter.clear(this@MainActivity)
                            StartupProblems.clear()
                            dismissed = true
                        },
                    )
                } else {
                    CyberSenseiApp()
                }
            }
        }
    }
}
