package com.cybersensei.academy.engine.nlu

import java.io.DataInputStream
import kotlin.math.sqrt

/**
 * A table that turns words into directions in space, so that two questions can be compared
 * by what they mean rather than by which words they happen to share.
 *
 * It is a lookup table and nothing more: 58.692 Italian words, each with 128 numbers, and no
 * network, no neural network at runtime, no text generation anywhere. That matters twice
 * over. It runs in a couple of milliseconds on any phone — and it is *incapable* of
 * inventing an answer, because the only thing it can do is say which of the school's own
 * written answers is closest to what was asked.
 *
 * The numbers were produced once, on a desktop, by a real multilingual model, and then
 * frozen into a byte each. Reproducing them is a script in `tools/semantica`; changing them
 * is not something that happens by accident.
 */
class WordVectors private constructor(
    private val rowOf: Map<String, Int>,
    private val quantised: ByteArray,
    val dimensions: Int,
) {
    val size: Int get() = rowOf.size

    operator fun contains(word: String): Boolean = word in rowOf

    /**
     * The direction of a whole sentence: the words it contains, added up, each pulling in
     * proportion to how much it says.
     *
     * Stop words are dropped — "come", "di", "che" point nowhere in particular and would
     * drag every sentence towards the same average. [weightOf] is how rare a word is in the
     * school's own material: "phishing" moves the result far more than "sito".
     */
    fun embed(text: String, weightOf: (String) -> Double): FloatArray? {
        val sum = FloatArray(dimensions)
        var used = 0

        ItalianText.normalise(text).split(' ').forEach { word ->
            if (word.length <= 1 || word in ItalianText.STOPWORDS) return@forEach
            val row = rowOf[word] ?: return@forEach
            val weight = weightOf(word).toFloat()
            val start = row * dimensions
            for (i in 0 until dimensions) {
                sum[i] += quantised[start + i] * weight
            }
            used++
        }

        if (used == 0) return null
        return sum.normalisedOrNull()
    }

    companion object {
        const val VECTORS_PATH = "/semantica/vettori.bin"
        const val VOCABULARY_PATH = "/semantica/vocabolario.txt"

        /** Written by the generator; guards against loading a file that is not this table. */
        private const val MAGIC = "CSV1"

        fun fromResources(
            vectorsPath: String = VECTORS_PATH,
            vocabularyPath: String = VOCABULARY_PATH,
        ): WordVectors {
            val words = WordVectors::class.java.getResourceAsStream(vocabularyPath)
                ?.bufferedReader()?.use { it.readLines() }
                ?: error("Vocabolario semantico non trovato: $vocabularyPath")

            val stream = WordVectors::class.java.getResourceAsStream(vectorsPath)
                ?: error("Vettori semantici non trovati: $vectorsPath")

            DataInputStream(stream.buffered()).use { input ->
                val magic = ByteArray(4).also(input::readFully).decodeToString()
                check(magic == MAGIC) { "Formato dei vettori sconosciuto: '$magic'" }

                val rows = input.readIntLittleEndian()
                val dimensions = input.readIntLittleEndian()
                check(rows == words.size) {
                    "Vocabolario e vettori non corrispondono: ${words.size} parole, $rows righe"
                }

                val data = ByteArray(rows * dimensions).also(input::readFully)
                val rowOf = HashMap<String, Int>(rows * 2)
                words.forEachIndexed { row, word -> rowOf[word] = row }
                return WordVectors(rowOf, data, dimensions)
            }
        }

        /** The generator writes little-endian; [DataInputStream] reads the other way round. */
        private fun DataInputStream.readIntLittleEndian(): Int {
            val bytes = ByteArray(4).also(::readFully)
            return (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
        }
    }
}

/** Unit length, or nothing: a direction with no length says nothing about meaning. */
internal fun FloatArray.normalisedOrNull(): FloatArray? {
    var squares = 0.0
    forEach { squares += it * it }
    if (squares < 1e-12) return null
    val norm = sqrt(squares).toFloat()
    for (i in indices) this[i] = this[i] / norm
    return this
}

/** Both sides are unit length, so the dot product is already the cosine. */
internal fun FloatArray.similarityTo(other: FloatArray): Double {
    var dot = 0.0
    for (i in indices) dot += this[i] * other[i]
    return dot
}
