package com.cybersensei.academy

import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The test that should have existed from day one: it starts the app.
 *
 * Everything else in this repository proves that the code compiles and that the engines
 * reason correctly. None of it proves that the thing opens on a phone — which is the only
 * part the student ever experiences first.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
// One SDK per run: Robolectric cannot set up several Android versions inside the same JVM
// without tripping over its own font cache.
@Config(application = HiltTestApplication::class, sdk = [34])
class AppLaunchTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `the app opens without crashing`() {
        hiltRule.inject()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                check(!activity.isFinishing) { "L'activity si è chiusa da sola all'avvio" }
            }
        }
    }
}
