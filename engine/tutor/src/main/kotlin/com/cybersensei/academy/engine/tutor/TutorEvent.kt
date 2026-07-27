package com.cybersensei.academy.engine.tutor

import com.cybersensei.academy.engine.mastery.AnswerVerdict

/**
 * Something happened that the professor should have an opinion about.
 *
 * [key] is what the rule set matches on; [slots] are the facts this particular occurrence
 * can contribute to a sentence. Keeping both on the event means a new kind of moment can be
 * added without touching the engine.
 */
sealed interface TutorEvent {
    val key: String
    val slots: Map<String, String> get() = emptyMap()

    /** The student opened the app. The most frequent moment, and the one that sets the tone. */
    data object AppOpened : TutorEvent {
        override val key = "app_opened"
    }

    /** An answer has been judged. The single most important moment in the whole school. */
    data class AnswerJudged(
        val verdict: AnswerVerdict,
        val skillLabel: String,
        val misconceptionLabel: String? = null,
    ) : TutorEvent {
        override val key = "answer_judged"
        override val slots = buildMap {
            put("abilita", skillLabel)
            misconceptionLabel?.let { put("misconcezione", it) }
        }
    }

    data class LessonCompleted(val lessonTitle: String) : TutorEvent {
        override val key = "lesson_completed"
        override val slots = mapOf("lezione" to lessonTitle)
    }

    data class LessonAbandoned(val lessonTitle: String) : TutorEvent {
        override val key = "lesson_abandoned"
        override val slots = mapOf("lezione" to lessonTitle)
    }

    data class ExamPassed(val levelName: String, val scorePercent: Int) : TutorEvent {
        override val key = "exam_passed"
        override val slots = mapOf("livello" to levelName, "punteggio" to scorePercent.toString())
    }

    data class ExamFailed(val levelName: String, val weakestSkill: String) : TutorEvent {
        override val key = "exam_failed"
        override val slots = mapOf("livello" to levelName, "abilita" to weakestSkill)
    }

    /** The student came back after a while. Tone must scale with how long they were gone. */
    data class ReturnedAfterAbsence(val days: Int) : TutorEvent {
        override val key = "returned_after_absence"
        override val slots = mapOf("giorni_assenza" to days.toString())
    }

    data object StreakBroken : TutorEvent {
        override val key = "streak_broken"
    }

    data class ReviewsDue(val count: Int) : TutorEvent {
        override val key = "reviews_due"
        override val slots = mapOf("ripassi" to count.toString())
    }

    data class LevelUnlocked(val levelName: String) : TutorEvent {
        override val key = "level_unlocked"
        override val slots = mapOf("livello" to levelName)
    }

    /** The student asked something the professor has no answer for. Never left silent. */
    data class UnknownQuestion(val question: String) : TutorEvent {
        override val key = "unknown_question"
        override val slots = mapOf("domanda" to question)
    }

    /** The student walked into the study to ask something of their own. */
    data object StudyOpened : TutorEvent {
        override val key = "study_opened"
    }

    /** The student opened the report card. The moment to be accurate, not encouraging. */
    data object ReportOpened : TutorEvent {
        override val key = "report_opened"
    }

    /**
     * One question of the enrolment interview.
     *
     * The name is carried on the event because during onboarding there is no saved profile
     * yet — the professor is learning it as he speaks.
     */
    data class OnboardingPrompt(val step: String, val draftName: String = "") : TutorEvent {
        override val key = "onboarding_$step"
        override val slots =
            if (draftName.isBlank()) emptyMap() else mapOf("nome" to draftName)
    }

    companion object {
        /** Matches the steps of the enrolment interview, in order. */
        val ONBOARDING_STEPS = listOf(
            "welcome", "name", "nickname", "birth_date", "goal", "tone", "budget", "pact",
        )

        /**
         * Every event key the engine must be able to speak about. The rule set is checked
         * against this list, so shipping a build where the professor could go silent is a
         * test failure rather than an awkward blank bubble on someone's phone.
         */
        val ALL_KEYS = listOf(
            "app_opened",
            "answer_judged",
            "lesson_completed",
            "lesson_abandoned",
            "exam_passed",
            "exam_failed",
            "returned_after_absence",
            "streak_broken",
            "reviews_due",
            "level_unlocked",
            "unknown_question",
            "study_opened",
            "report_opened",
        ) + ONBOARDING_STEPS.map { "onboarding_$it" }
    }
}
