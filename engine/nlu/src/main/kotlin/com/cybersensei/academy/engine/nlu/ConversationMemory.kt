package com.cybersensei.academy.engine.nlu

/** One turn: what the student asked, and what came back. */
data class Turn(
    val question: String,
    val answer: String,
    /** The entry that answered, when one did. Null when the professor admitted he did not know. */
    val entryId: String?,
    val topic: String?,
)

/**
 * The last few minutes of conversation, and nothing more.
 *
 * The professor could not answer "quali domande ti ho fatto finora" for a simple reason:
 * every question lived and died on its own, with nowhere to be remembered. This is that
 * nowhere, filled in.
 *
 * Deliberately short and deliberately volatile. Short because a follow-up refers to what was
 * just said, not to something from twenty minutes ago; volatile because the school's promise
 * is that nothing about the student leaves the device, and the cheapest way to keep a
 * conversation private is not to write it down at all. Closing the study forgets it.
 */
class ConversationMemory(private val capacity: Int = 20) {

    private val turns = ArrayDeque<Turn>()

    val size: Int get() = turns.size

    val isEmpty: Boolean get() = turns.isEmpty()

    /** Oldest first, which is the order a person would recount them in. */
    fun all(): List<Turn> = turns.toList()

    fun remember(turn: Turn) {
        turns.addLast(turn)
        while (turns.size > capacity) turns.removeFirst()
    }

    fun clear() = turns.clear()

    /** The most recent turn the professor actually answered, ignoring the ones he refused. */
    fun lastAnswered(): Turn? = turns.lastOrNull { it.entryId != null }

    /** The one before that: what "prima" refers to when the student says it. */
    fun previousAnswered(): Turn? =
        turns.filter { it.entryId != null }.dropLast(1).lastOrNull()

    /** The questions asked so far, in order, without the ones repeated word for word. */
    fun questionsAsked(): List<String> = turns.map { it.question }.distinct()

    /** Topics actually covered, for "di cosa abbiamo parlato". */
    fun topicsCovered(): List<String> = turns.mapNotNull { it.topic }.distinct()

    /**
     * Whether this exact answer was the last thing said.
     *
     * Used to vary the wording instead of repeating a paragraph the student has just read —
     * being answered twice with the identical text is the clearest possible sign of talking
     * to a machine that is not listening.
     */
    fun justSaid(entryId: String): Boolean = turns.lastOrNull()?.entryId == entryId
}
