package com.cybersensei.academy.core.model

/**
 * The four stages of the school. [INTRO] is the short "first day of class" that
 * exists mainly so the professor can get to know the student.
 */
enum class Level(val order: Int, val italianName: String, val subtitle: String) {
    INTRO(0, "Introduzione", "Il primo giorno di scuola"),
    EASY(1, "Facile", "Le fondamenta"),
    INTERMEDIATE(2, "Intermedio", "Il mestiere"),
    HARD(3, "Difficile", "Il difensore");

    val next: Level?
        get() = entries.firstOrNull { it.order == order + 1 }

    companion object {
        fun fromOrder(order: Int): Level? = entries.firstOrNull { it.order == order }
    }
}
