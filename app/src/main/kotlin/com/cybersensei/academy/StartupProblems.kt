package com.cybersensei.academy

/**
 * Failures that used to be fatal at startup.
 *
 * Loading the professor's script, the syllabus or the FAQ happens while the dependency graph
 * is built, which is before a single pixel is drawn: anything thrown there closes the app
 * with no explanation at all. Recording the failure and carrying on with an empty stand-in
 * turns a silent death into something the student — and whoever maintains this — can read.
 */
object StartupProblems {

    private val problems = mutableListOf<String>()

    fun record(what: String, error: Throwable) {
        problems += "$what: ${error::class.java.simpleName} — ${error.message.orEmpty()}"
    }

    fun report(): String? = problems.takeIf { it.isNotEmpty() }?.joinToString("\n")

    fun clear() = problems.clear()
}
