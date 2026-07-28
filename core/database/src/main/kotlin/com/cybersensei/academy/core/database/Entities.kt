package com.cybersensei.academy.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The school records. The student never sees any of this — no sync screen, no account, no
 * "your data" section: it simply works, and it never leaves the device.
 */

/** There is exactly one student per installation, so this table holds exactly one row. */
@Entity(tableName = "student")
data class StudentEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val name: String,
    val nickname: String,
    /** ISO date, null when the student preferred not to say. */
    val birthDate: String?,
    val goal: String,
    val tone: String,
    val dailyBudget: String,
    val enrolledOn: String,
    val ethicalPactSigned: Boolean,
    /** ISO local time, null when the student wants no reminder — which is the default. */
    val reminderAt: String? = null,
) {
    companion object {
        const val SINGLE_ROW = 1
    }
}

@Entity(tableName = "mastery")
data class MasteryEntity(
    @PrimaryKey val skillId: String,
    val value: Double,
    val attempts: Int,
    val consecutiveCorrect: Int,
    val lastPracticedAt: Long?,
)

@Entity(tableName = "review")
data class ReviewEntity(
    @PrimaryKey val skillId: String,
    val intervalDays: Int,
    val easeFactor: Double,
    val repetitions: Int,
    val dueOn: String,
    val flaggedForLuck: Boolean,
)

/** The professor's diary: the raw material of every sentence that cites the past. */
@Entity(tableName = "study_event")
data class StudyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val label: String,
    val at: Long,
)

@Entity(tableName = "lesson_progress")
data class LessonProgressEntity(
    @PrimaryKey val lessonId: String,
    val moduleId: String,
    val completedAt: Long,
)

/** Streak, experience and the counters the professor comments on. One row, like [StudentEntity]. */
@Entity(tableName = "stats")
data class StatsEntity(
    @PrimaryKey val id: Int = StudentEntity.SINGLE_ROW,
    val experiencePoints: Int = 0,
    val streakDays: Int = 0,
    val recordStreakDays: Int = 0,
    /** ISO date of the last day the student actually studied. */
    val lastStudyDate: String? = null,
    val openings: Int = 0,
    val totalStudyMinutes: Int = 0,
)

/** A badge the student has earned. Rows only ever appear here, never disappear. */
@Entity(tableName = "badge")
data class BadgeEntity(
    @PrimaryKey val badgeId: String,
    val earnedAt: Long,
)

/**
 * A question the student has already been told about.
 *
 * The study opens as the student learns, and an opening nobody notices is not a reward: the
 * professor has to be able to say "ti ho aperto sei domande nuove". That sentence needs a
 * memory of what had already been announced, and it has to be a memory that survives closing
 * the app — otherwise every launch would announce the same things.
 *
 * Rows only appear here, never disappear, exactly like badges.
 */
@Entity(tableName = "seen_question")
data class SeenQuestionEntity(
    @PrimaryKey val questionId: String,
    val seenAt: Long,
)
