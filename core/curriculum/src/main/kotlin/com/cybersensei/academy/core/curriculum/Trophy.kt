package com.cybersensei.academy.core.curriculum

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How rare a trophy is, and therefore how the medal is drawn.
 *
 * The tier is written out in words next to every medal as well as being a colour, because a
 * student who cannot tell bronze from gold has to be able to read which is which.
 */
@Serializable
enum class TrophyTier(val italianName: String, val order: Int) {
    @SerialName("bronzo")
    BRONZE("Bronzo", 0),

    @SerialName("argento")
    SILVER("Argento", 1),

    @SerialName("oro")
    GOLD("Oro", 2),

    @SerialName("platino")
    PLATINUM("Platino", 3),
}

/**
 * Which shelf a trophy sits on.
 *
 * The families exist so that a wall of forty-four medals reads as eight short lists instead of
 * one long one, and so that a student can see at a glance which part of the school they have
 * never touched.
 */
@Serializable
enum class TrophyFamily(val italianName: String, val subtitle: String, val order: Int) {
    @SerialName("percorso")
    PATH("Percorso", "Le lezioni portate a termine", 0),

    @SerialName("livelli")
    LEVELS("I livelli", "I quattro gradini della scuola", 1),

    @SerialName("casi")
    CASES("I casi", "Le storie a bivi e le decisioni prese", 2),

    @SerialName("esami")
    EXAMS("Gli esami", "Le prove di fine livello", 3),

    @SerialName("laboratori")
    LABS("I laboratori", "Il lavoro con le mani", 4),

    @SerialName("padronanza")
    MASTERY("Padronanza", "Quanto sai, non quanto hai fatto", 5),

    @SerialName("tirocinio")
    WORKSHOP("Tirocinio", "Le regole scritte da te, eseguite sul serio", 6),

    @SerialName("costanza")
    CONSISTENCY("Costanza", "Tornare, giorno dopo giorno", 7),

    @SerialName("onore")
    HONOUR("Onore", "I più difficili della scuola", 8),
}

/**
 * What has to be true for a trophy to be earned.
 *
 * Every type answers to something the student *did* — an answer given, a decision taken, a day
 * come back. There is deliberately no type for time spent, for lessons merely read, or for
 * screens opened: a trophy that arrives for scrolling teaches that scrolling is the goal, and
 * it also devalues the ones next to it that took work.
 */
@Serializable
data class TrophyCondition(
    val type: String,
    /** How many, how long, or which level. Ignored by the types that need no number. */
    val value: Int = 0,
) {
    companion object {
        // --- Percorso ---
        const val LESSONS_COMPLETED = "lessons_completed"
        const val ALL_LESSONS = "all_lessons"

        // --- Livelli ---
        const val LEVEL_PASSED = "level_passed"

        // --- Casi ---
        const val CASES_COMPLETED = "cases_completed"
        const val CASES_IN_LEVEL = "cases_in_level"
        const val ALL_CASES = "all_cases"
        const val PERFECT_CASES = "perfect_cases"

        // --- Esami ---
        const val EXAMS_PASSED = "exams_passed"
        const val ALL_EXAMS = "all_exams"
        const val EXAMS_FIRST_TRY = "exams_first_try"
        const val PERFECT_EXAMS = "perfect_exams"

        // --- Laboratori ---
        const val LABS_COMPLETED = "labs_completed"
        const val ALL_LABS = "all_labs"

        // --- Tirocinio ---
        const val EXERCISES_SOLVED = "exercises_solved"
        const val ALL_EXERCISES = "all_exercises"

        // --- Padronanza ---
        const val SKILLS_MASTERED = "skills_mastered"
        const val MODULES_MASTERED = "modules_mastered"
        const val MASTERY_AVERAGE = "mastery_average"
        const val FLAWLESS_QUIZZES = "flawless_quizzes"

        // --- Costanza ---
        const val STREAK_DAYS = "streak_days"
        const val RECORD_STREAK = "record_streak"
        const val RETURN_AFTER_ABSENCE = "return_after_absence"

        // --- Onore ---
        const val FINAL_CASE_COMPLETED = "final_case_completed"
        const val FINAL_CASE_PERFECT = "final_case_perfect"
        const val DIPLOMA = "diploma"
        const val NOTHING_ABANDONED = "nothing_abandoned"
        const val EVERYTHING = "everything"

        val KNOWN_TYPES = setOf(
            LESSONS_COMPLETED, ALL_LESSONS,
            LEVEL_PASSED,
            CASES_COMPLETED, CASES_IN_LEVEL, ALL_CASES, PERFECT_CASES,
            EXAMS_PASSED, ALL_EXAMS, EXAMS_FIRST_TRY, PERFECT_EXAMS,
            LABS_COMPLETED, ALL_LABS,
            EXERCISES_SOLVED, ALL_EXERCISES,
            SKILLS_MASTERED, MODULES_MASTERED, MASTERY_AVERAGE, FLAWLESS_QUIZZES,
            STREAK_DAYS, RECORD_STREAK, RETURN_AFTER_ABSENCE,
            FINAL_CASE_COMPLETED, FINAL_CASE_PERFECT, DIPLOMA, NOTHING_ABANDONED, EVERYTHING,
        )
    }
}

@Serializable
data class Trophy(
    val id: String,
    val name: String,
    /** The glyph in the middle of the medal. */
    val icon: String,
    val tier: TrophyTier,
    val family: TrophyFamily,
    /** What it means to hold it — shown once the medal has been won. */
    val description: String,
    /** How it is earned, in plain Italian. Shown whether or not it has been won. */
    @SerialName("how_to_earn") val howToEarn: String,
    val condition: TrophyCondition,
)

@Serializable
data class TrophyContent(val trofei: List<Trophy>)

/**
 * Everything the trophy rules are allowed to look at.
 *
 * A snapshot rather than a live handle on the database, so the rules stay pure Kotlin and every
 * one of them can be tested in a millisecond without a school around it.
 */
data class TrophyContext(
    val lessonsCompleted: Int = 0,
    val lessonsTotal: Int = 0,
    val lessonsAbandoned: Int = 0,
    val passedLevels: Set<Int> = emptySet(),
    /** Cases played through to the debriefing, counted per level. */
    val casesCompletedByLevel: Map<Int, Int> = emptyMap(),
    val casesTotalByLevel: Map<Int, Int> = emptyMap(),
    /** Cases finished with a hundred per cent, which means every decision was the sound one. */
    val perfectCases: Int = 0,
    val finalCaseCompleted: Boolean = false,
    val finalCasePerfect: Boolean = false,
    val examsPassed: Set<Int> = emptySet(),
    val examsTotal: Int = 0,
    /** Exams passed without ever having failed that same exam before. */
    val examsFirstTry: Int = 0,
    val perfectExams: Int = 0,
    val labsCompleted: Int = 0,
    val labsTotal: Int = 0,
    /** Rule-writing exercises solved: caught the whole attack and nothing else. */
    val exercisesSolved: Int = 0,
    val exercisesTotal: Int = 0,
    /** Skills at or above [MASTERED_THRESHOLD]. */
    val skillsMastered: Int = 0,
    /** Modules whose every skill is at or above [MASTERED_THRESHOLD]. */
    val modulesMastered: Int = 0,
    /** Average mastery across every skill practised so far, 0..1. */
    val masteryAverage: Double = 0.0,
    /** Interrogations finished without a single wrong answer. */
    val flawlessQuizzes: Int = 0,
    val streakDays: Int = 0,
    val recordStreakDays: Int = 0,
    /** The longest gap, in days, the student has come back from. */
    val longestReturnDays: Int = 0,
    val diplomaEarned: Boolean = false,
) {
    val casesCompleted: Int get() = casesCompletedByLevel.values.sum()
    val casesTotal: Int get() = casesTotalByLevel.values.sum()

    companion object {
        /**
         * What counts as knowing something.
         *
         * Higher than the gate that unlocks a level, on purpose: passing a level asks for a
         * solid average, a trophy for a single skill asks for that skill to be beyond doubt.
         */
        const val MASTERED_THRESHOLD = 0.90
    }
}

/**
 * Awards trophies from real progress.
 *
 * Deliberately dull: a trophy is never given for opening the app, for time spent, or for pages
 * scrolled — only for something the student demonstrably did. A reward that arrives for nothing
 * devalues the ones that mean something, and turns the wall into a list of things the student
 * knows they did not earn.
 */
class TrophyEngine(private val trophies: List<Trophy>) {

    fun all(): List<Trophy> = trophies

    fun byId(id: String): Trophy? = trophies.firstOrNull { it.id == id }

    /** Every family in display order, each with its trophies in ascending tier. */
    fun byFamily(): List<Pair<TrophyFamily, List<Trophy>>> =
        trophies.groupBy { it.family }
            .toList()
            .sortedBy { (family, _) -> family.order }
            .map { (family, list) -> family to list.sortedBy { it.tier.order } }

    fun earned(context: TrophyContext): List<Trophy> = trophies.filter { isEarned(it, context) }

    /** The trophies earned now that were not held before — what the professor announces. */
    fun newlyEarned(context: TrophyContext, alreadyHeld: Set<String>): List<Trophy> =
        earned(context).filterNot { it.id in alreadyHeld }

    private fun isEarned(trophy: Trophy, context: TrophyContext): Boolean {
        val need = trophy.condition.value
        return when (trophy.condition.type) {
            TrophyCondition.LESSONS_COMPLETED -> context.lessonsCompleted >= need
            TrophyCondition.ALL_LESSONS ->
                context.lessonsTotal > 0 && context.lessonsCompleted >= context.lessonsTotal

            TrophyCondition.LEVEL_PASSED -> need in context.passedLevels

            TrophyCondition.CASES_COMPLETED -> context.casesCompleted >= need
            TrophyCondition.CASES_IN_LEVEL -> {
                val total = context.casesTotalByLevel[need] ?: 0
                total > 0 && (context.casesCompletedByLevel[need] ?: 0) >= total
            }
            TrophyCondition.ALL_CASES ->
                context.casesTotal > 0 && context.casesCompleted >= context.casesTotal
            TrophyCondition.PERFECT_CASES -> context.perfectCases >= need

            TrophyCondition.EXAMS_PASSED -> context.examsPassed.size >= need
            TrophyCondition.ALL_EXAMS ->
                context.examsTotal > 0 && context.examsPassed.size >= context.examsTotal
            TrophyCondition.EXAMS_FIRST_TRY -> context.examsFirstTry >= need
            TrophyCondition.PERFECT_EXAMS -> context.perfectExams >= need

            TrophyCondition.LABS_COMPLETED -> context.labsCompleted >= need
            TrophyCondition.ALL_LABS ->
                context.labsTotal > 0 && context.labsCompleted >= context.labsTotal

            TrophyCondition.EXERCISES_SOLVED -> context.exercisesSolved >= need
            TrophyCondition.ALL_EXERCISES ->
                context.exercisesTotal > 0 && context.exercisesSolved >= context.exercisesTotal

            TrophyCondition.SKILLS_MASTERED -> context.skillsMastered >= need
            TrophyCondition.MODULES_MASTERED -> context.modulesMastered >= need
            TrophyCondition.MASTERY_AVERAGE -> context.masteryAverage * 100 >= need
            TrophyCondition.FLAWLESS_QUIZZES -> context.flawlessQuizzes >= need

            TrophyCondition.STREAK_DAYS -> context.streakDays >= need
            TrophyCondition.RECORD_STREAK -> context.recordStreakDays >= need
            TrophyCondition.RETURN_AFTER_ABSENCE -> context.longestReturnDays >= need

            TrophyCondition.FINAL_CASE_COMPLETED -> context.finalCaseCompleted
            TrophyCondition.FINAL_CASE_PERFECT -> context.finalCasePerfect
            TrophyCondition.DIPLOMA -> context.diplomaEarned
            // "Nothing left half done" needs a body of work behind it, otherwise a student who
            // has finished one lesson and abandoned none would already hold it.
            TrophyCondition.NOTHING_ABANDONED ->
                context.lessonsCompleted >= need && context.lessonsAbandoned == 0
            TrophyCondition.EVERYTHING ->
                context.lessonsTotal > 0 && context.lessonsCompleted >= context.lessonsTotal &&
                    context.casesTotal > 0 && context.casesCompleted >= context.casesTotal &&
                    context.examsTotal > 0 && context.examsPassed.size >= context.examsTotal &&
                    context.labsTotal > 0 && context.labsCompleted >= context.labsTotal &&
                    context.exercisesTotal > 0 && context.exercisesSolved >= context.exercisesTotal

            // An unknown rule must never hand out a trophy by accident.
            else -> false
        }
    }

    fun validate(): List<String> = buildList {
        val ids = trophies.map { it.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Trofeo duplicato: '$it'") }
        trophies.filter { it.condition.type !in TrophyCondition.KNOWN_TYPES }
            .forEach { add("Il trofeo '${it.id}' usa una condizione sconosciuta: '${it.condition.type}'") }
        trophies.filter { it.description.length < 30 }
            .forEach { add("Il trofeo '${it.id}' non spiega cosa significa averlo preso") }
        trophies.filter { it.howToEarn.length < 15 }
            .forEach { add("Il trofeo '${it.id}' non dice come si guadagna") }
        trophies.filter { it.icon.isBlank() }
            .forEach { add("Il trofeo '${it.id}' non ha un simbolo") }
        trophies.groupBy { it.name }.filterValues { it.size > 1 }.keys
            .forEach { add("Due trofei si chiamano '$it'") }
        // Two identical glyphs on a wall of medals read as a bug, not as a family resemblance.
        trophies.groupBy { it.icon }.filterValues { it.size > 1 }.keys
            .forEach { add("Il simbolo '$it' è usato da più di un trofeo") }
        TrophyFamily.entries.filter { family -> trophies.none { it.family == family } }
            .forEach { add("La famiglia '${it.italianName}' non ha nessun trofeo") }
        // A wall where nothing is locked at the start would give the whole thing away, and a
        // wall where the first medal needs a month would look broken on day one.
        if (trophies.none { it.tier == TrophyTier.BRONZE }) add("Nessun trofeo di bronzo: il primo giorno resterebbe vuoto")
        if (trophies.none { it.tier == TrophyTier.PLATINUM }) add("Nessun trofeo di platino: manca il traguardo")
    }

    companion object {
        const val RESOURCE_PATH = "/trofei/trofei.json"

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): TrophyEngine =
            TrophyEngine(json.decodeFromString<TrophyContent>(raw).trofei)

        fun fromResources(path: String = RESOURCE_PATH): TrophyEngine {
            val stream = TrophyEngine::class.java.getResourceAsStream(path)
                ?: error("Elenco dei trofei non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
