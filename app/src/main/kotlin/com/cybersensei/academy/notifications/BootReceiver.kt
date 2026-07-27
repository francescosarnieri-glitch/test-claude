package com.cybersensei.academy.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cybersensei.academy.core.database.SchoolRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Puts the alarm back after a reboot or an update, because the system forgets every alarm
 * across both.
 *
 * Separate from [ReminderReceiver] on purpose. This one has to be exported to hear the
 * system's broadcast, and a receiver that anyone can wake should be able to do as little as
 * possible: all it can do is re-read the time the student chose and set the same alarm
 * again. It cannot show a notification, and the two actions it answers to are protected
 * broadcasts that only the system is allowed to send.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: SchoolRepository

    @Inject lateinit var reminders: StudyReminders

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                reminders.apply(repository.profile())
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
