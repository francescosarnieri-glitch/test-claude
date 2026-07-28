package com.cybersensei.academy.engine.nlu

/**
 * Which of the school's answers are open to this student.
 *
 * The rule is one sentence: **a question about the subject opens when the student has sat the
 * interrogation that covers it** — passed or failed, it makes no difference.
 *
 * Failing is the case the rule is really for. A student who gets a test wrong has just proved
 * they did not understand something, and that is precisely the moment they need to come and
 * ask the professor about it. Opening those answers only to whoever already passed would hand
 * the explanations to the people who needed them least.
 *
 * The other half is what has no lesson behind it at all: what this school is, how the exams
 * work, where the data goes, what the professor knows about you. Those are open from the first
 * second, because they are not subject matter — they are the questions somebody asks *before*
 * deciding to study, and refusing them would be a locked door on the way in.
 *
 * There is no third case. Everything that belongs to the syllabus waits for its interrogation,
 * first aid included: this school teaches an order, and the order holds even where it costs.
 */
class StudyAvailability {

    /**
     * Whether this answer is open to a student who has already been examined on
     * [attemptedSkills].
     *
     * An entry with no competence declared belongs to the school rather than to the syllabus,
     * and never waits for anything.
     */
    fun isOpen(entry: FaqEntry, attemptedSkills: Set<String>): Boolean {
        val skill = entry.skillId ?: return true
        return skill in attemptedSkills
    }

    /** The competences whose interrogation would open [entries], in the order they are met. */
    fun skillsThatOpen(entries: List<FaqEntry>): List<String> =
        entries.mapNotNull { it.skillId }.distinct()
}
