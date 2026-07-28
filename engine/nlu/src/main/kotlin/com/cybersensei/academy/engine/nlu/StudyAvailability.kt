package com.cybersensei.academy.engine.nlu

/**
 * Which of the school's answers are open to this student, and which are still ahead.
 *
 * The study holds two hundred and fifty answers, and most of them are noise to somebody who
 * started yesterday: "cos'è un rootkit" and "cosa sono SIEM e SOC" are not wrong answers, they
 * are answers to questions a beginner has no reason to ask. Showing everything at once turns a
 * catalogue into a wall.
 *
 * So the catalogue grows with the student. An answer opens when the module that teaches its
 * subject has been started — the first completed lesson is enough, because the reward has to
 * arrive while the effort is still fresh.
 *
 * **What never closes.** Anything a person might need *right now* stays open from the first
 * minute: what to do after clicking a bad link, how to recognise a scam, what the school knows
 * about them. This is a school about security, and a locked answer to somebody who is being
 * robbed would be the app failing at the one moment it exists for. That decision lives in the
 * content — a branch declares itself open — and not in a number here, because it is a judgement
 * about people rather than about difficulty.
 *
 * And nothing is ever *denied*: a question that is still ahead is folded away, not taken away.
 * The student who goes looking for it finds it, with the professor saying out loud that they
 * are running ahead of the programme.
 */
class StudyAvailability(
    /** Which module teaches each competence. */
    private val moduleOfSkill: Map<String, String>,
    /** Which lessons belong to each module. */
    private val lessonsOfModule: Map<String, Set<String>>,
) {
    /**
     * The modules the student has begun.
     *
     * Begun, not finished. A module opens its questions as soon as one of its lessons is done:
     * the moment the student has just read about phishing is exactly the moment the questions
     * about phishing are worth having, and making them wait for the whole module would put the
     * reward a week after the interest.
     */
    fun startedModules(completedLessons: Set<String>): Set<String> =
        lessonsOfModule.filterValues { lessons -> lessons.any { it in completedLessons } }.keys

    /**
     * Whether this answer is open to a student who has started [startedModules].
     *
     * [inOpenBranch] is the content's own decision: a room declared open shows everything it
     * holds, whatever the student has studied. Level zero is always open too — it is the
     * introduction, and locking the introduction behind itself would be a nice piece of
     * circular reasoning.
     */
    fun isOpen(entry: FaqEntry, inOpenBranch: Boolean, startedModules: Set<String>): Boolean {
        if (inOpenBranch || entry.level == 0) return true
        val module = entry.skillId?.let { moduleOfSkill[it] } ?: return true
        return module in startedModules
    }

    /** The modules a student has to start to open [entries], in the order the school teaches them. */
    fun modulesThatOpen(entries: List<FaqEntry>): List<String> =
        entries.mapNotNull { entry -> entry.skillId?.let { moduleOfSkill[it] } }.distinct()
}
