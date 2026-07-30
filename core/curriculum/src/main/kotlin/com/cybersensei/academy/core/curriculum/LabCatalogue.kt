package com.cybersensei.academy.core.curriculum

/**
 * Which workshops can be *finished*.
 *
 * Five of the ten labs ask the student to judge something — twelve messages to sort, six
 * manifests to weigh, fifty-two log lines to pick from — and those have an end: every item
 * answered. The other five are benches: you type a password and watch it hold or fall, you
 * change one character of a hash and see the avalanche. A bench cannot be completed, only
 * used, and pretending otherwise would hand out a trophy for having typed something.
 *
 * The ids live here rather than next to the screens because the trophy rules are in this
 * module and must not depend on the app. A test in the app module keeps this list and the
 * workshop catalogue from drifting apart.
 */
object LabCatalogue {
    val WITH_VERDICTS = listOf(
        "casella",
        "anomalia",
        "pacchetti",
        "certificati",
        "manifesto",
    )

    val COUNT: Int get() = WITH_VERDICTS.size
}
