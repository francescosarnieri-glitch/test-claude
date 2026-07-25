package com.cybersensei.academy.engine.tutor

import com.cybersensei.academy.core.model.Level
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.core.model.TutorTone

/**
 * Everything the professor knows about the student at this instant.
 *
 * This is the object that makes a rule-based tutor feel like a person: not the cleverness of
 * any single sentence, but the fact that the sentence is chosen knowing the student skipped
 * three days, keeps confusing hashing with encryption, and has an exam-level average.
 */
data class StudentSnapshot(
    val profile: StudentProfile?,
    val level: Level = Level.INTRO,
    val streakDays: Int = 0,
    val recordStreakDays: Int = 0,
    val daysSinceLastVisit: Int = 0,
    val openings: Int = 0,
    val masteryAverage: Double = 0.0,
    val weakestSkillLabel: String? = null,
    val recurringMisconceptionLabel: String? = null,
    val recurringMisconceptionTimes: Int = 0,
    val unfinishedLessonTitle: String? = null,
    val dueReviews: Int = 0,
    val totalStudyMinutes: Int = 0,
) {
    val isKnownStudent: Boolean get() = profile != null
    val tone: TutorTone get() = profile?.tone ?: TutorTone.FRIENDLY
    val name: String? get() = profile?.name
    val nickname: String? get() = profile?.nickname
}
