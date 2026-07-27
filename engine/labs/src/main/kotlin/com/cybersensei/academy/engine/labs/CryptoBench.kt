package com.cybersensei.academy.engine.labs

import java.security.MessageDigest

/**
 * The crypto bench.
 *
 * Everything here is either a toy from the history books (Caesar, XOR against a repeating
 * key) or a one-way function. There is deliberately nothing that produces a usable attack:
 * the exercise is to *see* why an hash cannot be undone and why a salt matters, not to break
 * anything. The toys are labelled as toys in their own results.
 */
object CryptoBench {

    // --- Historical toys ---------------------------------------------------------------

    /**
     * Caesar's shift, kept because seeing it fall apart is the fastest way to understand
     * that secrecy of the method was never the point.
     */
    fun caesar(text: String, shift: Int): String = text.map { char ->
        when {
            char.isLowerCase() -> shiftLetter(char, shift, 'a')
            char.isUpperCase() -> shiftLetter(char, shift, 'A')
            else -> char
        }
    }.joinToString("")

    private fun shiftLetter(char: Char, shift: Int, base: Char): Char {
        val normalised = ((char - base) + shift).mod(ALPHABET)
        return base + normalised
    }

    /** Every shift at once: twenty-six lines, one of which is Italian. That is the lesson. */
    fun breakCaesarByHand(cipherText: String): List<Pair<Int, String>> =
        (1 until ALPHABET).map { shift -> shift to caesar(cipherText, -shift) }

    /**
     * XOR against a repeating key.
     *
     * Shown as hexadecimal because the raw bytes are not printable. Reversible with the same
     * call — which is itself the point: symmetric means one key, both directions.
     */
    fun xorHex(text: String, key: String): String {
        if (key.isEmpty()) return text.toByteArray().toHex()
        val keyBytes = key.toByteArray()
        return text.toByteArray()
            .mapIndexed { index, byte -> (byte.toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte() }
            .toByteArray()
            .toHex()
    }

    // --- One-way functions ---------------------------------------------------------------

    fun sha256(text: String): String = digest("SHA-256", text.toByteArray())

    fun sha256(bytes: ByteArray): String = digest("SHA-256", bytes)

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).toHex()

    /**
     * How much of the output changed, in bits.
     *
     * The avalanche effect is the property that makes an hash useful for integrity, and it
     * is far more convincing measured than described: one character in, roughly half the
     * output bits flipped.
     */
    fun avalanche(first: String, second: String): AvalancheResult {
        val a = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        val b = MessageDigest.getInstance("SHA-256").digest(second.toByteArray())
        val differing = a.indices.sumOf { index ->
            Integer.bitCount((a[index].toInt() xor b[index].toInt()) and 0xFF)
        }
        val total = a.size * 8
        return AvalancheResult(
            first = a.toHex(),
            second = b.toHex(),
            differingBits = differing,
            totalBits = total,
        )
    }

    /**
     * The same password, hashed with two different salts.
     *
     * Answers the question the syllabus says students ask most: what a salt is for if it is
     * stored in the open next to the hash. Seeing two unrelated outputs from one password
     * settles it faster than any sentence.
     */
    fun saltedPair(password: String, firstSalt: String, secondSalt: String): SaltDemonstration =
        SaltDemonstration(
            withoutSalt = sha256(password),
            firstSalt = firstSalt,
            secondSalt = secondSalt,
            firstHash = sha256(firstSalt + password),
            secondHash = sha256(secondSalt + password),
        )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val ALPHABET = 26
}

data class AvalancheResult(
    val first: String,
    val second: String,
    val differingBits: Int,
    val totalBits: Int,
) {
    val percentChanged: Int get() = (differingBits * 100) / totalBits
}

data class SaltDemonstration(
    val withoutSalt: String,
    val firstSalt: String,
    val secondSalt: String,
    val firstHash: String,
    val secondHash: String,
) {
    /** True when the two salted results share nothing, which is the entire point. */
    val hashesDiffer: Boolean get() = firstHash != secondHash
}
