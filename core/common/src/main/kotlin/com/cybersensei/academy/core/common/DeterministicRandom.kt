package com.cybersensei.academy.core.common

import kotlin.random.Random

/**
 * The professor must feel spontaneous but stay reproducible: given the same student, the
 * same event and the same counter, he says the same thing. That makes his behaviour
 * testable, and it stops the same line from appearing twice in a row by accident.
 *
 * Not a security primitive — never use this for anything cryptographic. (The app will
 * happily teach you why in module 2.1.)
 */
object DeterministicRandom {

    fun seedOf(vararg parts: Any?): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (part in parts) {
            val text = part?.toString() ?: "∅"
            for (char in text) {
                hash = hash xor char.code.toLong()
                hash *= 0x100000001b3L
            }
        }
        return hash
    }

    fun forSeed(vararg parts: Any?): Random = Random(seedOf(*parts))
}

/**
 * Picks an element pseudo-randomly while avoiding anything in [recentlyUsed]. Falls back to
 * the full list when every option has been used recently, so it can never return null.
 */
fun <T> List<T>.pickAvoidingRecent(
    random: Random,
    recentlyUsed: Collection<T>,
): T {
    require(isNotEmpty()) { "Cannot pick from an empty list" }
    val fresh = filterNot { it in recentlyUsed }
    val pool = fresh.ifEmpty { this }
    return pool[random.nextInt(pool.size)]
}
