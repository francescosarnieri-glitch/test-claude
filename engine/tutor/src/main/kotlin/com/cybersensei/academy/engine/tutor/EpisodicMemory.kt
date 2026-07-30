package com.cybersensei.academy.engine.tutor

import java.time.Duration
import java.time.Instant

/** Something worth remembering about this student's history. */
data class StudyEvent(
    val kind: Kind,
    val label: String,
    val at: Instant,
) {
    enum class Kind {
        LESSON_STARTED,

        /**
         * Le schede sono state lette fino in fondo, l'interrogazione no.
         *
         * Serve perche' «letta» e «fatta» sono due cose diverse: una lezione e' fatta quando
         * la sua interrogazione e' stata sostenuta, superata o no. Senza questo evento lo
         * studente che legge e se ne va ritroverebbe la lezione identica a come l'ha lasciata,
         * senza alcun segno del lavoro che ha fatto.
         */
        LESSON_READ,
        LESSON_COMPLETED,
        LESSON_ABANDONED,
        MISCONCEPTION_HIT,
        EXAM_PASSED,
        EXAM_FAILED,

        /**
         * A level whose gate was met. Written down rather than recomputed, because mastery
         * decays and a level the student had earned must not close behind them.
         */
        LEVEL_PASSED,

        /**
         * Un laboratorio portato a termine: tutte le sue voci giudicate.
         *
         * Solo il fatto, mai il risultato. Nei laboratori lo studente ha il diritto di sbagliare
         * senza che finisca nel registro, ed e' quella liberta' che li rende utili: scrivere il
         * punteggio trasformerebbe un banco di prova in un'altra interrogazione.
         */
        LAB_COMPLETED,

        /** Un'interrogazione chiusa senza nemmeno una risposta sbagliata. */
        QUIZ_FLAWLESS,
        SESSION,
    }
}

/**
 * The professor's diary.
 *
 * This is the cheapest component in the whole engine and the one that does the most work for
 * the illusion. A tutor that says "è la terza volta in dieci giorni che confondi hashing e
 * cifratura" feels alive in a way no amount of clever phrasing can match — and all it takes
 * is remembering.
 */
class EpisodicMemory(
    events: List<StudyEvent> = emptyList(),
    private val capacity: Int = 500,
) {
    private val entries = ArrayDeque(events.takeLast(capacity))

    val size: Int get() = entries.size
    fun all(): List<StudyEvent> = entries.toList()

    fun record(event: StudyEvent) {
        entries.addLast(event)
        while (entries.size > capacity) entries.removeFirst()
    }

    fun lastSession(): Instant? = entries.lastOrNull { it.kind == StudyEvent.Kind.SESSION }?.at

    /**
     * A lesson the student walked out of and never came back to. The professor uses it to
     * pick up exactly where they left off, instead of asking "what shall we do today?".
     */
    fun unfinishedLesson(): String? {
        val abandoned = entries.filter { it.kind == StudyEvent.Kind.LESSON_ABANDONED }
        val completed = entries.filter { it.kind == StudyEvent.Kind.LESSON_COMPLETED }
            .map { it.label }
            .toSet()
        return abandoned.lastOrNull { it.label !in completed }?.label
    }

    /**
     * The mistake this student keeps making, within [window]. Returns the label and how many
     * times it happened, so the sentence can be specific about both.
     */
    fun recurringMisconception(
        now: Instant,
        window: Duration = Duration.ofDays(30),
        minimumTimes: Int = 2,
    ): Pair<String, Int>? = entries
        .asSequence()
        .filter { it.kind == StudyEvent.Kind.MISCONCEPTION_HIT }
        .filter { Duration.between(it.at, now) <= window }
        .groupingBy { it.label }
        .eachCount()
        .filterValues { it >= minimumTimes }
        .maxByOrNull { it.value }
        ?.toPair()

    /** Days between the last session and now — the basis of every "welcome back" line. */
    fun daysSinceLastSession(now: Instant): Int {
        val last = lastSession() ?: return 0
        return Duration.between(last, now).toDays().toInt().coerceAtLeast(0)
    }

    fun countOf(kind: StudyEvent.Kind): Int = entries.count { it.kind == kind }
}
