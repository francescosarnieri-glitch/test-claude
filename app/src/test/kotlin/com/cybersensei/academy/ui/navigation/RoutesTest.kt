package com.cybersensei.academy.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that was broken: a route template, the argument declared for it in the graph and
 * the key the screen reads back are three copies of the same string, and nothing forces them
 * to agree. When `lezione/{moduleId}` was paired with an argument called `lessonId`, the
 * entire navigation graph refused to build — and it did so on the first frame of the
 * classroom, in front of a student who had just signed the ethical pact.
 *
 * Navigation itself can only be exercised on a device, but this invariant can be checked
 * here, in milliseconds, and it is the one that failed.
 */
class RoutesTest {

    private fun placeholdersIn(route: String): List<String> =
        Regex("\\{([a-zA-Z_]+)\\}").findAll(route).map { it.groupValues[1] }.toList()

    @Test
    fun `the lesson route carries exactly the argument the lesson screen reads`() {
        assertEquals(listOf(Routes.ARG_LESSON_ID), placeholdersIn(Routes.LESSON))
    }

    @Test
    fun `the quiz route carries exactly the argument the quiz screen reads`() {
        assertEquals(listOf(Routes.ARG_MODULE_ID), placeholdersIn(Routes.QUIZ))
    }

    @Test
    fun `no two routes declare the same argument under different names`() {
        assertEquals("lessonId", Routes.ARG_LESSON_ID)
        assertEquals("moduleId", Routes.ARG_MODULE_ID)
    }

    @Test
    fun `the built links fit the templates they are built from`() {
        val lesson = Routes.lesson("les_intro_cia")
        val quiz = Routes.quiz("mod_intro")

        assertEquals("lezione/les_intro_cia", lesson)
        assertEquals("interrogazione/mod_intro", quiz)

        assertTrue(
            "Il link della lezione non corrisponde al suo modello",
            lesson.matches(Routes.LESSON.replace("{${Routes.ARG_LESSON_ID}}", "[a-z_0-9]+").toRegex()),
        )
        assertTrue(
            "Il link dell'interrogazione non corrisponde al suo modello",
            quiz.matches(Routes.QUIZ.replace("{${Routes.ARG_MODULE_ID}}", "[a-z_0-9]+").toRegex()),
        )
    }

    @Test
    fun `every top level section has a distinct route`() {
        val routes = TopLevelDestination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.none { it.contains('{') })
    }
}
