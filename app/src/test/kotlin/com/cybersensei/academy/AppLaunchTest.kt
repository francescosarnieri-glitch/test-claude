package com.cybersensei.academy

import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The test that should have existed from day one: it starts the app and draws it.
 *
 * Drawing is not a detail. Compose builds a navigation graph during the measure pass, so an
 * app that survives `onCreate` can still die the instant the first frame is laid out — which
 * is exactly what happened to a student who had just signed the ethical pact.
 */
// One SDK per run: Robolectric cannot set up several Android versions inside the same JVM
// without tripping over its own font cache.
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class AppLaunchTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var repository: SchoolRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun `a brand new student is met by the enrolment interview`() {
        launchAndDraw()
    }

    /**
     * The case the first version of this test missed twice over: without a saved profile the
     * app only ever shows onboarding, so the school's navigation graph is never built at all.
     */
    @Test
    fun `an enrolled student lands in the school without crashing`() {
        runBlocking {
            repository.saveProfile(
                StudentProfile(
                    name = "Francesco",
                    birthDate = LocalDate.of(1990, 11, 3),
                    enrolledOn = LocalDate.of(2026, 1, 10),
                    ethicalPactSigned = true,
                ),
            )
        }
        launchAndDraw()
    }

    /**
     * Opens the activity and forces a real measure and layout pass, because that is when
     * Compose actually composes the content. Without it the test watches an activity that
     * exists but has never drawn anything, and sees none of the failures a student would.
     */
    private fun launchAndDraw() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(PHONE_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(PHONE_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, PHONE_WIDTH, PHONE_HEIGHT)
                check(!activity.isFinishing) { "L'app si è chiusa da sola" }
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private companion object {
        const val PHONE_WIDTH = 1080
        const val PHONE_HEIGHT = 2400
    }
}
