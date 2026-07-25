package com.cybersensei.academy.engine.tutor

import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.common.androidSafeRegex

/**
 * Fills the `{placeholders}` in a line with facts about this student, at this moment.
 *
 * The tidy-up at the end is not cosmetic: a slot that resolves to nothing would otherwise
 * leave a hole — "Bentornato , ." — and a single sentence like that is enough to break the
 * illusion that someone is talking to you.
 */
class SlotResolver(private val timeProvider: TimeProvider) {

    fun resolve(template: String, student: StudentSnapshot, event: TutorEvent): String {
        val slots = slotsFor(student) + event.slots
        val filled = SLOT_PATTERN.replace(template) { match ->
            slots[match.groupValues[1]].orEmpty()
        }
        return tidy(filled)
    }

    fun slotsFor(student: StudentSnapshot): Map<String, String> = buildMap {
        val profile = student.profile
        put("saluto", timeProvider.dayPart().italianGreeting)
        put("nome", profile?.name.orEmpty())
        put("appellativo", profile?.nickname ?: profile?.name.orEmpty())
        put("streak", student.streakDays.toString())
        put("record_streak", student.recordStreakDays.toString())
        put("ripassi", student.dueReviews.toString())
        put("giorni_assenza", student.daysSinceLastVisit.toString())
        put("livello", student.level.italianName)
        put("minuti_totali", student.totalStudyMinutes.toString())
        put("padronanza", (student.masteryAverage * 100).toInt().toString())
        student.weakestSkillLabel?.let { put("punto_debole", it) }
        student.unfinishedLessonTitle?.let { put("lezione_interrotta", it) }
        student.recurringMisconceptionLabel?.let { put("errore_ricorrente", it) }
        put("volte", student.recurringMisconceptionTimes.toString())
        profile?.zodiacSign?.let {
            put("segno", it.italianName)
            put("simbolo_segno", it.symbol)
        }
        profile?.birthDate?.let { birth ->
            put("eta", java.time.Period.between(birth, timeProvider.today()).years.toString())
        }
    }

    /** Which placeholders a template needs — used by the content validator. */
    fun slotsUsedIn(template: String): Set<String> =
        SLOT_PATTERN.findAll(template).map { it.groupValues[1] }.toSet()

    private fun tidy(text: String): String = text
        .replace(SPACE_BEFORE_PUNCTUATION, "$1")
        // "Bentornato, ." — what is left when a name the app does not have was expected.
        .replace(ORPHANED_COMMA, "$1")
        .replace(REPEATED_PUNCTUATION, "$1")
        .replace(MULTIPLE_SPACES, " ")
        .let(::capitaliseSentences)
        .trim()

    /**
     * A slot dropped after a full stop arrives in whatever case the data had — "si riparte
     * dalle fondamenta. l'hash non si decifra". One lowercase letter is enough to make the
     * whole sentence look generated, so sentence starts are fixed up here.
     */
    private fun capitaliseSentences(text: String): String {
        val builder = StringBuilder(text)
        var startOfSentence = true
        for (index in builder.indices) {
            val char = builder[index]
            when {
                startOfSentence && char.isLetter() -> {
                    builder[index] = char.uppercaseChar()
                    startOfSentence = false
                }
                char in SENTENCE_ENDINGS -> startOfSentence = true
                !char.isWhitespace() && char !in QUOTES -> startOfSentence = false
            }
        }
        return builder.toString()
    }

    companion object {
        private val SLOT_PATTERN = androidSafeRegex("\\{([a-z_]+)\\}")
        private val SPACE_BEFORE_PUNCTUATION = androidSafeRegex("\\s+([,.;:!?])")
        private val ORPHANED_COMMA = androidSafeRegex("[,;:]\\s*([.!?])")
        private val REPEATED_PUNCTUATION = androidSafeRegex("([,.;:])\\1+")
        private val MULTIPLE_SPACES = androidSafeRegex("[ \\t]{2,}")
        private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '…')
        private val QUOTES = charArrayOf('«', '"', '\'', '“')

        /** Slots the resolver can always fill, whatever the student's state. */
        val ALWAYS_AVAILABLE = setOf(
            "saluto", "nome", "appellativo", "streak", "record_streak", "ripassi",
            "giorni_assenza", "livello", "minuti_totali", "padronanza", "volte",
        )

        /** Slots that only exist when the student's state or the event provides them. */
        val CONDITIONAL = setOf(
            "segno", "simbolo_segno", "eta", "punto_debole", "lezione_interrotta",
            "errore_ricorrente", "abilita", "misconcezione", "lezione", "punteggio", "domanda",
        )

        /**
         * The full vocabulary a line may use. Anything outside it is a typo in the content,
         * and the content test turns that into a failing build instead of a hole in a
         * sentence on someone's phone.
         */
        val KNOWN_SLOTS = ALWAYS_AVAILABLE + CONDITIONAL
    }
}
