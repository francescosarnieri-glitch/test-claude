package com.cybersensei.academy.ui.study

import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.Level
import com.cybersensei.academy.engine.nlu.ConversationMemory
import com.cybersensei.academy.engine.nlu.FaqEntry
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

/**
 * Fills the professor's answers about *this* student, from the school's own records.
 *
 * The one thing he can do that no general assistant can. A model trained on the whole
 * internet cannot say when you enrolled, how many days in a row you came, or which idea you
 * keep getting wrong — it has never met you. This class is where that advantage lives, and
 * it is the reason the answer to "quando è stata installata l'app" should never have been
 * the brochure of the four sections.
 *
 * Every value comes from something measured. Nothing here is estimated, rounded up to sound
 * better, or invented when missing: a slot with no data behind it makes the professor fall
 * back to the entry's own "not yet" sentence instead of printing a hole.
 */
class SchoolFacts @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val timeProvider: TimeProvider,
) {

    /**
     * Resolves the placeholders of [entry] and returns the finished sentence, or the entry's
     * own empty-handed version when the record has nothing to say yet.
     */
    suspend fun fill(entry: FaqEntry, memory: ConversationMemory): String {
        val slots = slots(memory)
        val needed = SLOT_PATTERN.findAll(entry.answer).map { it.groupValues[1] }.toSet()
        val missing = needed.any { slots[it].isNullOrBlank() }

        // The empty-handed version is filled too. It has data of its own to cite — "il
        // programma ne ha 108" is what makes "nessuna, per ora" useful — and returning it
        // raw put a brace in front of a student on their first day.
        val text = if (missing) entry.empty ?: entry.answer else entry.answer
        return SLOT_PATTERN.replace(text) { slots[it.groupValues[1]].orEmpty() }
    }

    private suspend fun slots(memory: ConversationMemory): Map<String, String> = buildMap {
        putAll(recordSlots())
        putAll(memorySlots(memory))
    }

    private suspend fun recordSlots(): Map<String, String> = buildMap {
        val profile = repository.profile()
        val stats = repository.stats()
        val mastery = repository.allMastery().filter { it.attempts > 0 }
        val today = timeProvider.today()

        profile?.let {
            put("nome", it.nickname)
            put("iscritto_il", it.enrolledOn.format(DATE))
            // Zero days would read as "sono 0 giorni che ci conosciamo": the entry has an
            // empty-handed version for the first day, so leave the slot unfilled instead.
            val days = ChronoUnit.DAYS.between(it.enrolledOn, today)
            if (days > 0) put("giorni_iscritto", days.toString())
            it.ageOn(today)?.let { age -> put("eta", age.toString()) }
            it.zodiacSign?.let { sign -> put("segno", sign.italianName) }
        }

        if (stats.streakDays > 0) put("streak", stats.streakDays.toString())
        put("record_streak", stats.recordStreakDays.toString())
        if (stats.studiedMinutes > 0) put("minuti", stats.studiedMinutes.toString())
        put("xp", stats.experiencePoints.toString())

        val completed = repository.completedLessonIds().size
        if (completed > 0) put("lezioni_fatte", completed.toString())
        put("lezioni_totali", curriculum.lessons.size.toString())

        if (mastery.isNotEmpty()) {
            put("padronanza", ((mastery.sumOf { it.value } / mastery.size) * 100).toInt().toString())
            mastery.minByOrNull { it.value }?.let { weakest ->
                // Same treatment the report card gives them: the identifier is written with
                // underscores, the student reads words.
                put("punto_debole", weakest.skillId.replace('_', ' '))
            }
        }

        val due = repository.dueReviews().size
        if (due > 0) put("ripassi", due.toString())

        val unlocked = repository.unlockedLevels().maxOrNull() ?: 0
        put("livello_attuale", "livello ${Level.fromOrder(unlocked)?.italianName ?: unlocked}")

        val passed = repository.examPassedLevels().sorted()
            .mapNotNull { Level.fromOrder(it)?.italianName }
        if (passed.isNotEmpty()) put("esami_superati", passed.joinToString(", "))
    }

    private fun memorySlots(memory: ConversationMemory): Map<String, String> = buildMap {
        val questions = memory.questionsAsked()
        if (questions.isNotEmpty()) {
            put("quante_domande", questions.size.toString())
            put("domande_fatte", questions.joinToString("\n") { "• $it" })
        }

        val topics = memory.topicsCovered()
        if (topics.isNotEmpty()) put("argomenti_trattati", topics.joinToString("\n") { "• $it" })

        memory.lastAnswered()?.let { put("ultima_risposta", it.answer) }
        memory.previousAnswered()?.let {
            put("penultima_risposta", it.answer)
            put("argomento_precedente", it.topic ?: it.question)
        }
    }

    private companion object {
        val SLOT_PATTERN = Regex("\\{([a-z_]+)\\}")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
    }
}
