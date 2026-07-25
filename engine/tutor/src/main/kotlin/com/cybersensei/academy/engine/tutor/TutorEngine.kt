package com.cybersensei.academy.engine.tutor

import com.cybersensei.academy.core.common.DeterministicRandom
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.common.pickAvoidingRecent
import com.cybersensei.academy.engine.mastery.AnswerVerdict

/** One thing said by Prof. Hackstein White, with the trace of why he said it. */
data class ProfessorLine(
    val text: String,
    val ruleId: String,
    val poolId: String,
)

/**
 * Remembers the last few lines used in each pool, so the professor does not repeat himself
 * in a way the student would notice.
 */
class RecentLineMemory(private val perPool: Int = 3) {
    private val used = mutableMapOf<String, ArrayDeque<String>>()

    fun recentIn(poolId: String): Collection<String> = used[poolId].orEmpty()

    fun remember(poolId: String, line: String) {
        val queue = used.getOrPut(poolId) { ArrayDeque() }
        queue.addLast(line)
        while (queue.size > perPool) queue.removeFirst()
    }

    fun clear() = used.clear()
}

/**
 * The professor's mind.
 *
 * Given a moment and everything known about the student, it picks the most specific rule
 * that fits, draws a line from that rule's pool avoiding the ones just used, and fills in
 * the student's own facts. No model, no network, no API key — and, by construction, never
 * silence: [speak] always returns something.
 */
class TutorEngine(
    private val library: DialogueLibrary,
    private val timeProvider: TimeProvider,
    private val memory: RecentLineMemory = RecentLineMemory(),
) {
    private val slots = SlotResolver(timeProvider)
    private var utterances = 0L

    fun speak(event: TutorEvent, student: StudentSnapshot): ProfessorLine {
        utterances++
        val rule = selectRule(event, student)
        val pool = rule?.let { library.poolOf(it.pool) }

        if (rule == null || pool == null || pool.lines.isEmpty()) {
            // The script is validated in CI precisely so this cannot happen; if it somehow
            // does, the student still gets a sentence rather than an empty bubble.
            return ProfessorLine(FALLBACK_LINE, ruleId = "fallback", poolId = "fallback")
        }

        val random = DeterministicRandom.forSeed(
            student.profile?.name,
            event.key,
            rule.id,
            utterances,
        )
        val template = pool.lines.pickAvoidingRecent(random, memory.recentIn(pool.id))
        memory.remember(pool.id, template)

        return ProfessorLine(
            text = slots.resolve(template, student, event),
            ruleId = rule.id,
            poolId = pool.id,
        )
    }

    /** The highest-priority rule whose conditions all hold. */
    fun selectRule(event: TutorEvent, student: StudentSnapshot): DialogueRule? =
        library.rulesFor(event.key).firstOrNull { matches(it.conditions, event, student) }

    private fun matches(
        conditions: RuleConditions,
        event: TutorEvent,
        student: StudentSnapshot,
    ): Boolean {
        conditions.dayParts?.let { if (timeProvider.dayPart() !in it) return false }
        conditions.tones?.let { if (student.tone !in it) return false }
        conditions.minStreak?.let { if (student.streakDays < it) return false }
        conditions.maxStreak?.let { if (student.streakDays > it) return false }
        conditions.minAbsenceDays?.let { if (student.daysSinceLastVisit < it) return false }
        conditions.minMastery?.let { if (student.masteryAverage < it) return false }
        conditions.maxMastery?.let { if (student.masteryAverage > it) return false }
        conditions.minReviewsDue?.let { if (student.dueReviews < it) return false }
        conditions.requiresKnownStudent?.let { if (student.isKnownStudent != it) return false }

        if (conditions.requiresBirthday) {
            val profile = student.profile ?: return false
            if (!profile.isBirthday(timeProvider.today())) return false
        }
        if (conditions.requiresUnfinishedLesson && student.unfinishedLessonTitle == null) return false
        if (conditions.requiresRecurringMisconception && student.recurringMisconceptionLabel == null) {
            return false
        }

        conditions.verdicts?.let { allowed ->
            val verdict = (event as? TutorEvent.AnswerJudged)?.verdict ?: return false
            if (verdict !in allowed) return false
        }

        return true
    }

    /**
     * Convenience for the most important moment of all: an answer has been judged, and the
     * student is owed an explanation whatever the outcome was.
     */
    fun reactToAnswer(
        verdict: AnswerVerdict,
        skillLabel: String,
        student: StudentSnapshot,
        misconceptionLabel: String? = null,
    ): ProfessorLine = speak(
        TutorEvent.AnswerJudged(verdict, skillLabel, misconceptionLabel),
        student,
    )

    private companion object {
        const val FALLBACK_LINE =
            "Un attimo che raccolgo le idee. Riprendiamo da dove eravamo rimasti."
    }
}
