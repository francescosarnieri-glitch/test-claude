package com.cybersensei.academy.core.curriculum

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One screen of a lesson: short enough to read standing on a bus. */
@Serializable
data class LessonCard(
    val title: String? = null,
    val body: String,
    /** Optional monospaced block: a log line, a command, a hash. */
    val terminal: String? = null,
    /** Optional one-line takeaway the student should leave with. */
    val takeaway: String? = null,
)

@Serializable
data class Lesson(
    val id: String,
    val title: String,
    val minutes: Int,
    val cards: List<LessonCard>,
)

/**
 * One possible answer.
 *
 * A wrong option is never filler: it stands for a specific way of getting the reasoning
 * wrong, and it carries the rebuttal for exactly that mistake. That is what lets the
 * professor say *why* the student's own answer was wrong, instead of only restating the
 * right one.
 */
@Serializable
data class QuestionOption(
    val id: String,
    val text: String,
    val correct: Boolean = false,
    /** Short label of the misconception this option reveals, used by the diary. */
    val misconception: String? = null,
    /** Why this particular answer is wrong. Required on every wrong option. */
    val rebuttal: String? = null,
)

@Serializable
data class Question(
    val id: String,
    /** The micro-skill this question measures. Mastery is tracked per skill, not per question. */
    val skill: String,
    val prompt: String,
    val options: List<QuestionOption>,
    /** Why the right answer is right — shown even when the student got it right. */
    @SerialName("explanation") val explanation: String,
    /** Where this shows up outside a classroom. */
    @SerialName("real_world") val realWorld: String? = null,
    /** A follow-up the professor leaves the student thinking about. */
    @SerialName("control_question") val controlQuestion: String? = null,
    /** Seconds a student who reasons it through is expected to take. */
    @SerialName("expected_seconds") val expectedSeconds: Int = 40,
    /** Other questions testing the same idea from a different angle. */
    @SerialName("variants") val variantIds: List<String> = emptyList(),
) {
    val correctOption: QuestionOption get() = options.first { it.correct }
}

@Serializable
data class Module(
    val id: String,
    val title: String,
    val subtitle: String,
    val skills: List<String>,
    val lessons: List<Lesson>,
    val questions: List<Question>,
) {
    val totalMinutes: Int get() = lessons.sumOf { it.minutes }
}

@Serializable
data class LevelContent(
    val level: Int,
    val title: String,
    val modules: List<Module>,
)

@Serializable
data class Curriculum(val levels: List<LevelContent>) {

    val modules: List<Module> get() = levels.flatMap { it.modules }
    val lessons: List<Lesson> get() = modules.flatMap { it.lessons }
    val questions: List<Question> get() = modules.flatMap { it.questions }

    fun level(order: Int): LevelContent? = levels.firstOrNull { it.level == order }
    fun module(id: String): Module? = modules.firstOrNull { it.id == id }
    fun lesson(id: String): Lesson? = lessons.firstOrNull { it.id == id }
    fun question(id: String): Question? = questions.firstOrNull { it.id == id }
    /**
     * Matched by id, never by value: the screens hand back questions whose options have been
     * reordered for presentation, so a data-class comparison would find nothing and throw.
     */
    fun moduleOfQuestion(questionId: String): Module? =
        modules.firstOrNull { module -> module.questions.any { it.id == questionId } }

    fun moduleOfLesson(lessonId: String): Module? =
        modules.firstOrNull { module -> module.lessons.any { it.id == lessonId } }

    fun questionsForSkill(skill: String): List<Question> = questions.filter { it.skill == skill }

    /**
     * Everything that must be true before a build is allowed to ship. The rules encode the
     * promises made to the student: every question can be answered, every wrong answer is
     * explained, and no question measures a skill its module never claims to teach.
     */
    fun validate(): List<String> = buildList {
        val ids = mutableSetOf<String>()
        (modules.map { it.id } + lessons.map { it.id } + questions.map { it.id }).forEach {
            if (!ids.add(it)) add("Identificativo duplicato: '$it'")
        }

        modules.forEach { module ->
            if (module.lessons.isEmpty()) add("Il modulo '${module.id}' non ha lezioni")
            if (module.questions.isEmpty()) add("Il modulo '${module.id}' non ha domande")
            module.questions.forEach { question ->
                if (question.skill !in module.skills) {
                    add("La domanda '${question.id}' misura '${question.skill}', che il modulo '${module.id}' non dichiara")
                }
            }
            module.skills.forEach { skill ->
                if (module.questions.none { it.skill == skill }) {
                    add("L'abilità '$skill' del modulo '${module.id}' non è verificata da nessuna domanda")
                }
            }
        }

        lessons.forEach { lesson ->
            if (lesson.cards.isEmpty()) add("La lezione '${lesson.id}' non ha schede")
            lesson.cards.filter { it.body.isBlank() }
                .forEach { add("Scheda vuota nella lezione '${lesson.id}'") }
        }

        questions.forEach { question ->
            val correct = question.options.count { it.correct }
            if (correct != 1) add("La domanda '${question.id}' ha $correct risposte corrette invece di una")
            if (question.options.size < 2) add("La domanda '${question.id}' ha meno di due opzioni")
            if (question.explanation.isBlank()) {
                add("La domanda '${question.id}' non spiega perché la risposta giusta è giusta")
            }
            question.options.filterNot { it.correct }.forEach { option ->
                if (option.rebuttal.isNullOrBlank()) {
                    add("L'opzione '${option.id}' di '${question.id}' non spiega perché è sbagliata")
                }
            }
            question.variantIds.filter { variant -> questions.none { it.id == variant } }
                .forEach { add("La domanda '${question.id}' rimanda alla variante inesistente '$it'") }
        }
    }

    companion object {
        const val RESOURCE_PATH = "/curriculum/programma.json"

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): Curriculum = json.decodeFromString(raw)

        fun fromResources(path: String = RESOURCE_PATH): Curriculum {
            val stream = Curriculum::class.java.getResourceAsStream(path)
                ?: error("Programma didattico non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
