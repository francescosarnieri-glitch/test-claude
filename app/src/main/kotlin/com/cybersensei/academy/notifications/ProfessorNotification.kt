package com.cybersensei.academy.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cybersensei.academy.MainActivity
import com.cybersensei.academy.R
import com.cybersensei.academy.core.ui.Professor

/**
 * The only thing this app ever puts on a lock screen.
 *
 * One channel, so that switching it off in the system settings switches off everything the
 * professor can say — no second channel appearing later to route around a decision the
 * student already made.
 */
object ProfessorNotification {

    private const val CHANNEL_ID = "promemoria"
    private const val NOTIFICATION_ID = 1

    fun show(context: Context, line: String) {
        val manager = NotificationManagerCompat.from(context)
        // Two different refusals, and both have to be honoured: the runtime permission
        // denied on Android 13+, and notifications switched off in the system settings.
        // Written out here rather than in a helper because Lint only recognises a permission
        // check that sits in the same function as the call it guards.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted || !manager.areNotificationsEnabled()) return

        createChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_promemoria)
            .setContentTitle(Professor.NAME)
            .setContentText(line)
            // The professor writes in sentences, and a lock screen truncates them at one
            // line. Expanded style is what makes the message readable without opening it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        // Checked just above, but a SecurityException here would kill the receiver's process
        // for the sake of a reminder — not a trade worth making.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** Channels only exist from Android 8 on; below it the importance is the notification's own. */
    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Promemoria di studio",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Un solo promemoria al giorno, all'ora che hai scelto tu."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
