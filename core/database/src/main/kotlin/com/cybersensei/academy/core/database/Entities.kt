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
