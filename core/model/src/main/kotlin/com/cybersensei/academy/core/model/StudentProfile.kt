package com.cybersensei.academy.core.model

import java.time.LocalDate
import java.time.Period

/** How the professor talks to the student. Chosen during onboarding, changeable later. */
enum class TutorTone(val italianName: String, val description: String) {
    FRIENDLY("Amichevole", "Ti dà del tu, scherza, ti incoraggia"),
    STRICT("Severo ma giusto", "Professore vecchia scuola: poche coccole, molta sostanza"),
    MENTOR("Mentore calmo", "Pacato, riflessivo, non alza mai la voce"),
    IRONIC("Ironico", "Ti insegna prendendoti in giro con affetto"),
}

/** Why the student is here. Used to pick real-world examples that hit closer to home. */
enum class LearningGoal(val italianName: String) {
    CURIOSITY("Curiosità personale"),
    CAREER("Lavorare nella cybersecurity"),
    PROTECT_FAMILY("Proteggere me e la mia famiglia"),
    STUDY("Studio o esame"),
    WORK_IT("Il mio lavoro ha a che fare con l'IT"),
}

/** Minutes per day the student committed to. The professor plans sessions around it. */
enum class DailyBudget(val minutes: Int, val italianName: String) {
    SHORT(5, "5 minuti al giorno"),
    NORMAL(15, "15 minuti al giorno"),
    SERIOUS(30, "30 minuti al giorno"),
    INTENSE(60, "Un'ora al giorno"),
}

/**
 * Everything the student told the professor about themselves. It never leaves the device.
 */
data class StudentProfile(
    val name: String,
    /** What the professor actually calls them — may be a nickname, defaults to [name]. */
    val nickname: String = name,
    val birthDate: LocalDate?,
    val goal: LearningGoal = LearningGoal.CURIOSITY,
    val tone: TutorTone = TutorTone.FRIENDLY,
    val dailyBudget: DailyBudget = DailyBudget.NORMAL,
    val enrolledOn: LocalDate,
    val ethicalPactSigned: Boolean = false,
) {
    val zodiacSign: ZodiacSign? = birthDate?.let(ZodiacSign::of)

    fun ageOn(today: LocalDate): Int? =
        birthDate?.let { Period.between(it, today).years }

    fun isBirthday(today: LocalDate): Boolean =
        birthDate != null &&
            birthDate.dayOfMonth == today.dayOfMonth &&
            birthDate.month == today.month

    /** Days since enrolment, used by the professor to comment on the journey so far. */
    fun daysEnrolled(today: LocalDate): Long =
        java.time.temporal.ChronoUnit.DAYS.between(enrolledOn, today)
}
