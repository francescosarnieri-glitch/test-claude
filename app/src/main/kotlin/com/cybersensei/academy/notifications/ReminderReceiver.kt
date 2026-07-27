package com.cybersensei.academy.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The alarm went off.
 *
 * Deliberately thin: it keeps the process alive while the database is read and hands every
 * decision to [ReminderWork], which is where the two rules the student actually feels live —
 * never speak to someone who has already studied today, and never invent the words.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var work: ReminderWork

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StudyReminders.ACTION_REMIND) return

        // onReceive must not block, and reading the diary takes a few milliseconds.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                work.run()
            } finally {
                pending.finish()
            }
        }
    }
}
