package com.cybersensei.academy

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Local crash recorder.
 *
 * An app that dies on launch tells the student nothing and tells us less. This writes the
 * stack trace to a file inside the app's own storage and shows it on the next launch, so a
 * silent close becomes a readable explanation.
 *
 * It sends nothing anywhere — there is no network permission to send it with, and there
 * never will be. The file lives and dies with the app.
 */
object CrashReporter {

    private const val FILE_NAME = "ultimo-errore.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? =
        file(context).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        file(context).writeText(
            buildString {
                appendLine("Cyber Sensei ${BuildConfig.VERSION_NAME}")
                appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Quando: ${Instant.now()}")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(trace)
            },
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
