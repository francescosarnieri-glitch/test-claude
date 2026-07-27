package com.cybersensei.academy.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cybersensei.academy.core.common.FixedTimeProvider
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.di.TimeModule
import com.cybersensei.academy.engine.mastery.Confidence
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The only moment the professor speaks without being asked, and therefore the only one that
 * can annoy someone into uninstalling the app.
 *
 * Three things are held here: he stays quiet on a day the student has already studied, he
 * always re-arms tomorrow's alarm even when he says nothing, and what he says is a real line
 * from his own script rather than a generic "torna a studiare".
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@UninstallModules(TimeModule::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class ReminderWorkTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @BindValue
    @JvmField
    val timeProvider: TimeProvider = FixedTimeProvider(
        LocalDateTime.of(2026, 3, 10, 21, 0).atZone(ZoneId.of("Europe/Rome")).toInstant(),
    )

    @Inject lateinit var work: ReminderWork
    @Inject lateinit var reminders: StudyReminders
    @Inject lateinit var repository: SchoolRepository
    @Inject lateinit var curriculum: Curriculum

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hiltRule.inject()
        enrol(reminderAt = LocalTime.of(21, 0))
        // Android 13 and later will not deliver anything without this, and the student is
        // asked for it the moment they pick an hour in the settings.
        shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun enrol(reminderAt: LocalTime?) = runBlocking {
        repository.saveProfile(
            StudentProfile(
                name = "Francesco",
                birthDate = LocalDate.of(1990, 11, 3),
                enrolledOn = LocalDate.of(2026, 1, 10),
                ethicalPactSigned = true,
                reminderAt = reminderAt,
            ),
        )
    }

    private fun notifications() = shadowOf(
        context.getSystemService(NotificationManager::class.java),
    ).allNotifications

    private fun scheduledAlarm() = shadowOf(
        context.getSystemService(AlarmManager::class.java),
    ).nextScheduledAlarm

    @Test
    fun `a student who has not studied today gets one notification, in the professor's words`() {
        val outcome = runBlocking { work.run() }

        assertEquals(ReminderOutcome.SPOKEN, outcome)
        assertEquals("Un solo avviso, mai due", 1, notifications().size)

        val text = shadowOf(notifications().single()).contentText.toString()
        assertTrue("Il promemoria non può essere vuoto", text.isNotBlank())
        assertTrue(
            "E deve essere una frase, non un'etichetta: $text",
            text.length > 20,
        )
    }

    /** The rule that separates a reminder from nagging. */
    @Test
    fun `a student who already studied today is left alone`() {
        runBlocking {
            repository.recordAnswer(
                skillId = curriculum.levels.first().modules.first().skills.first(),
                correct = true,
                confidence = Confidence.SURE,
                responseTime = 30.seconds,
                expectedTime = 40.seconds,
            )
        }

        val outcome = runBlocking { work.run() }

        assertEquals(ReminderOutcome.ALREADY_STUDIED, outcome)
        assertTrue("Nessuna notifica a chi ha già studiato", notifications().isEmpty())
    }

    /** Silence today must not mean silence forever: the alarm is one-shot by design. */
    @Test
    fun `tomorrow is armed even on a day he says nothing`() {
        runBlocking {
            repository.recordAnswer(
                skillId = curriculum.levels.first().modules.first().skills.first(),
                correct = true,
                confidence = Confidence.SURE,
                responseTime = 30.seconds,
                expectedTime = 40.seconds,
            )
            work.run()
        }

        val alarm = scheduledAlarm()
        assertNotNull("La sveglia di domani deve essere già armata", alarm)
        assertEquals(
            "E deve essere fra ventiquattro ore, non fra un minuto",
            LocalDateTime.of(2026, 3, 11, 21, 0)
                .atZone(ZoneId.of("Europe/Rome")).toInstant().toEpochMilli(),
            alarm!!.triggerAtTime,
        )
    }

    @Test
    fun `a reminder switched off in the meantime cancels the alarm and says nothing`() {
        enrol(reminderAt = null)

        val outcome = runBlocking { work.run() }

        assertEquals(ReminderOutcome.NO_ONE_TO_REMIND, outcome)
        assertTrue(notifications().isEmpty())
        assertNull("Nessuna sveglia deve restare armata", scheduledAlarm())
    }

    /** A refused permission is a decision, not an error: nothing is posted and nothing crashes. */
    @Test
    fun `a student who refused notifications is not notified`() {
        shadowOf(context as android.app.Application)
            .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val outcome = runBlocking { work.run() }

        assertEquals(ReminderOutcome.SPOKEN, outcome)
        assertTrue("Permesso negato, nessuna notifica", notifications().isEmpty())
        assertNotNull("Ma la sveglia di domani resta armata", scheduledAlarm())
    }

    @Test
    fun `choosing an hour arms the alarm and choosing none removes it`() {
        reminders.schedule(LocalTime.of(8, 0))
        assertNotNull(scheduledAlarm())

        reminders.cancel()
        assertNull(scheduledAlarm())
    }
}
