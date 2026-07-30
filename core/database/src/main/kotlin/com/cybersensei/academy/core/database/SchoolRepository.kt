package com.cybersensei.academy.core.database

import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.curriculum.LabCatalogue
import com.cybersensei.academy.core.curriculum.Trophy
import com.cybersensei.academy.core.curriculum.TrophyContext
import com.cybersensei.academy.core.curriculum.TrophyEngine
import com.cybersensei.academy.core.model.DailyBudget
import com.cybersensei.academy.core.model.LearningGoal
import com.cybersensei.academy.core.model.Level
import com.cybersensei.academy.core.model.StudentProfile
import com.cybersensei.academy.core.model.TutorTone
import com.cybersensei.academy.engine.mastery.AnswerRecord
import com.cybersensei.academy.engine.mastery.AnswerVerdict
import com.cybersensei.academy.engine.mastery.Mastery
import com.cybersensei.academy.engine.mastery.LevelGate
import com.cybersensei.academy.engine.mastery.MasteryEngine
import com.cybersensei.academy.engine.mastery.MasteryUpdate
import com.cybersensei.academy.engine.scenario.ScenarioLibrary
import com.cybersensei.academy.engine.scheduler.ReviewItem
import com.cybersensei.academy.engine.scheduler.ReviewScheduler
import com.cybersensei.academy.engine.tutor.EpisodicMemory
import com.cybersensei.academy.engine.tutor.StudentSnapshot
import com.cybersensei.academy.engine.tutor.StudyEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The one door between the school's memory and the rest of the app.
 *
 * It also owns the small amount of bookkeeping that has to happen somewhere — streaks,
 * experience, the diary — so that no screen ever has to remember to do it.
 */
@Singleton
class SchoolRepository @Inject constructor(
    private val studentDao: StudentDao,
    private val masteryDao: MasteryDao,
    private val reviewDao: ReviewDao,
    private val studyEventDao: StudyEventDao,
    private val progressDao: ProgressDao,
    private val statsDao: StatsDao,
    private val trophyDao: TrophyDao,
    private val seenQuestionDao: SeenQuestionDao,
    private val curriculum: Curriculum,
    private val scenarios: ScenarioLibrary,
    private val trophyEngine: TrophyEngine,
    private val masteryEngine: MasteryEngine,
    private val reviewScheduler: ReviewScheduler,
    private val timeProvider: TimeProvider,
) {

    // --- The student ------------------------------------------------------------------

    fun observeProfile(): Flow<StudentProfile?> = studentDao.observe().map { it?.toDomain() }

    suspend fun profile(): StudentProfile? = studentDao.get()?.toDomain()

    suspend fun hasEnrolled(): Boolean = studentDao.get() != null

    suspend fun saveProfile(profile: StudentProfile) {
        studentDao.save(profile.toEntity())
        if (statsDao.get() == null) statsDao.save(StatsEntity())
    }

    // --- What the professor reasons over -----------------------------------------------

    /** Assembles everything the tutor engine needs to choose what to say. */
    suspend fun snapshot(level: Level = Level.INTRO): StudentSnapshot {
        val profile = profile()
        val stats = statsDao.get() ?: StatsEntity()
        val masteries = masteryDao.all().map { it.toDomain() }
        val memory = EpisodicMemory(recentStudyEvents())
        val today = timeProvider.today()
        val recurring = memory.recurringMisconception(timeProvider.now())

        return StudentSnapshot(
            profile = profile,
            level = level,
            streakDays = stats.streakDays,
            recordStreakDays = stats.recordStreakDays,
            daysSinceLastVisit = stats.lastStudyDate
                ?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), today).toInt().coerceAtLeast(0) }
                ?: 0,
            openings = stats.openings,
            masteryAverage = if (masteries.isEmpty()) 0.0 else masteries.sumOf { it.value } / masteries.size,
            weakestSkillLabel = masteries.minByOrNull { it.value }?.skillId,
            recurringMisconceptionLabel = recurring?.first,
            recurringMisconceptionTimes = recurring?.second ?: 0,
            unfinishedLessonTitle = memory.unfinishedLesson(),
            dueReviews = reviewDao.dueOn(today.toString()).size,
            totalStudyMinutes = stats.studySeconds / SECONDS_IN_MINUTE,
        )
    }

    suspend fun recentStudyEvents(limit: Int = 500): List<StudyEvent> =
        studyEventDao.recent(limit).reversed().map { it.toDomain() }

    /** Counts an app launch. Opening the app is not studying, so it never feeds the streak. */
    suspend fun registerOpening() {
        val stats = statsDao.get() ?: StatsEntity()
        statsDao.save(stats.copy(openings = stats.openings + 1))
    }

    // --- Studying ----------------------------------------------------------------------

    /**
     * Records an answer end to end: updates what the student knows, reschedules the review,
     * writes the diary entry and pays out the experience.
     */
    suspend fun recordAnswer(
        skillId: String,
        correct: Boolean,
        confidence: com.cybersensei.academy.engine.mastery.Confidence,
        responseTime: kotlin.time.Duration,
        expectedTime: kotlin.time.Duration,
        misconceptionLabel: String? = null,
    ): MasteryUpdate {
        val now = timeProvider.now()
        val today = timeProvider.today()
        val current = masteryDao.get(skillId)?.toDomain() ?: Mastery(skillId)

        val update = masteryEngine.register(
            current,
            AnswerRecord(
                skillId = skillId,
                correct = correct,
                confidence = confidence,
                responseTime = responseTime,
                expectedTime = expectedTime,
                misconceptionId = misconceptionLabel,
                answeredAt = now,
            ),
        )
        masteryDao.save(update.mastery.toEntity())

        val review = reviewDao.get(skillId)?.toDomain() ?: ReviewItem(skillId, dueOn = today)
        reviewDao.save(reviewScheduler.schedule(review, update.verdict, today).toEntity())

        if (update.verdict == AnswerVerdict.ROOTED_MISCONCEPTION || update.verdict == AnswerVerdict.HONEST_MISS) {
            misconceptionLabel?.let { record(StudyEvent.Kind.MISCONCEPTION_HIT, it) }
        }
        addExperience(update.experiencePoints)
        registerStudyDay()

        return update
    }

    suspend fun startLesson(lessonId: String, title: String) {
        record(StudyEvent.Kind.LESSON_STARTED, title)
        record(StudyEvent.Kind.SESSION, lessonId)
    }

    suspend fun abandonLesson(title: String) = record(StudyEvent.Kind.LESSON_ABANDONED, title)

    /**
     * The cards have been read to the end. Not the same as having done the lesson.
     *
     * Reading is progress and is written down as such, but what closes a lesson is sitting its
     * interrogation — see [completeLesson]. Keeping the two apart is what stops the reader who
     * skips every test from walking the whole programme without ever being asked anything.
     */
    suspend fun markLessonRead(lessonId: String) {
        if (lessonId in readLessonIds()) return
        record(StudyEvent.Kind.LESSON_READ, lessonId)
    }

    suspend fun readLessonIds(): Set<String> = recentStudyEvents()
        .filter { it.kind == StudyEvent.Kind.LESSON_READ }
        .map { it.label }
        .toSet()

    /**
     * A lesson is finished, which now means: read *and* examined on.
     *
     * The minutes are credited once and never again, so replaying a lesson to inflate the
     * study time is not a thing that can happen — before, every re-read added its minutes
     * over again to a number the student is invited to be proud of.
     */
    suspend fun completeLesson(lessonId: String, moduleId: String, title: String, minutes: Int) {
        val first = lessonId !in completedLessonIds()
        progressDao.save(
            LessonProgressEntity(lessonId, moduleId, timeProvider.now().toEpochMilli()),
        )
        if (first) record(StudyEvent.Kind.LESSON_COMPLETED, title)
        registerStudyDay()
    }

    suspend fun completedLessonIds(): Set<String> = progressDao.all().map { it.lessonId }.toSet()

    fun observeCompletedLessonIds(): Flow<Set<String>> =
        progressDao.observeAll().map { list -> list.map { it.lessonId }.toSet() }

    suspend fun masteryFor(skillIds: List<String>): List<Mastery> =
        masteryDao.forSkills(skillIds).map { it.toDomain() }

    suspend fun allMastery(): List<Mastery> = masteryDao.all().map { it.toDomain() }

    suspend fun dueReviews(limit: Int = Int.MAX_VALUE): List<ReviewItem> {
        val today = timeProvider.today()
        return reviewScheduler.dueToday(
            reviewDao.dueOn(today.toString()).map { it.toDomain() },
            today,
            limit,
        )
    }

    // --- Housekeeping the screens should not have to think about -----------------------

    private suspend fun record(kind: StudyEvent.Kind, label: String) {
        studyEventDao.insert(
            StudyEventEntity(kind = kind.name, label = label, at = timeProvider.now().toEpochMilli()),
        )
        studyEventDao.trimTo(DIARY_CAPACITY)
    }

    private suspend fun addExperience(points: Int) {
        val stats = statsDao.get() ?: StatsEntity()
        statsDao.save(stats.copy(experiencePoints = stats.experiencePoints + points))
    }

    /**
     * Writes down the running total of time spent in the school.
     *
     * An absolute value rather than an increment, because the caller is a stopwatch that keeps
     * counting between writes: adding deltas would make the number jump whenever a flush
     * landed between two reads of the same screen.
     */
    suspend fun setTimeAtSchool(seconds: Long) {
        val stats = statsDao.get() ?: StatsEntity()
        val counted = seconds.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (counted <= stats.studySeconds) return
        statsDao.save(stats.copy(studySeconds = counted))
    }

    suspend fun timeAtSchoolSeconds(): Long = (statsDao.get() ?: StatsEntity()).studySeconds.toLong()

    /**
     * Advances the streak. Studying twice in one day counts once; a single missed day
     * restarts the count — but the record is kept, because that is what makes it worth
     * chasing again.
     */
    suspend fun registerStudyDay() {
        val stats = statsDao.get() ?: StatsEntity()
        val today = timeProvider.today()
        val last = stats.lastStudyDate?.let(LocalDate::parse)

        // One diary entry the first time the student studies on a given day. The streak
        // counter only knows how long the current run is; attendance needs the days
        // themselves, including the ones where every answer happened to be right and so
        // left no other trace.
        if (last != today) record(StudyEvent.Kind.SESSION, today.toString())

        val streak = when {
            last == null -> 1
            last == today -> stats.streakDays.coerceAtLeast(1)
            last == today.minusDays(1) -> stats.streakDays + 1
            else -> 1
        }

        statsDao.save(
            stats.copy(
                streakDays = streak,
                recordStreakDays = maxOf(stats.recordStreakDays, streak),
                lastStudyDate = today.toString(),
            ),
        )
    }

    suspend fun stats(): StatsEntity = statsDao.get() ?: StatsEntity()

    fun observeStats(): Flow<StatsEntity?> = statsDao.observe()

    // --- Levels and badges --------------------------------------------------------------

    /**
     * Which levels the student has actually passed.
     *
     * "Passed" means what it means everywhere else in this app: every module's skills at or
     * above the gate. Finishing the lessons is not enough, and neither is a good average
     * hiding one subject that was never understood.
     */
    /**
     * The levels the student has passed — ever, not right now.
     *
     * The gate is a measurement and measurements decay: mastery falls on its own, and this
     * function used to recompute the whole thing from the current numbers. The consequence was
     * silent and cruel — two weeks away from the app and a level the student had earned closed
     * again behind them, with no explanation and nothing they could point at. Forgetting is
     * what reviews are for; it is not a reason to take back what somebody has already done.
     *
     * So a pass is written into the diary the first time it happens, and from then on it is a
     * fact. What decays keeps driving the reviews, which is where forgetting belongs.
     */
    suspend fun passedLevels(): Set<Int> {
        val mastery = allMastery()
        val now = curriculum.levels.filter { level ->
            level.modules.isNotEmpty() && level.modules.all { module ->
                LevelGate.evaluate(module.skills, mastery).passed
            }
        }.map { it.level }.toSet()

        val recorded = recentStudyEvents()
            .filter { it.kind == StudyEvent.Kind.LEVEL_PASSED }
            .mapNotNull { it.label.toIntOrNull() }
            .toSet()

        (now - recorded).forEach { record(StudyEvent.Kind.LEVEL_PASSED, it.toString()) }
        return now + recorded
    }

    /** A level opens when the one before it has been passed. The first is always open. */
    suspend fun unlockedLevels(): Set<Int> {
        val passed = passedLevels()
        return curriculum.levels.map { it.level }
            .filter { order -> order == 0 || (order - 1) in passed }
            .toSet()
    }

    /** How many trophies the school has in total — the denominator on every screen. */
    val trophiesInSchool: Int get() = trophyEngine.all().size

    /**
     * How many workshop exercises exist.
     *
     * Told rather than counted, so that the records module keeps knowing nothing about how an
     * exercise is shaped — it only needs the denominator, for the trophy that asks for all of
     * them. Set once at startup; until then it is zero, and a total of zero can never satisfy
     * an "all of them" condition, so an unset value fails closed.
     */
    var exercisesInSchool: Int = 0
        private set

    fun declareExercises(count: Int) {
        exercisesInSchool = count
    }

    suspend fun trophiesHeld(): Set<String> = trophyDao.all().map { it.trophyId }.toSet()

    /** When each trophy was won, so the wall can say the date and not just the name. */
    suspend fun trophyDates(): Map<String, Long> =
        trophyDao.all().associate { it.trophyId to it.earnedAt }

    fun observeTrophies(): Flow<List<String>> =
        trophyDao.observeAll().map { list -> list.map { it.trophyId } }

    /**
     * Everything the trophy rules need, gathered in one place.
     *
     * Public because the wall shows progress towards what is still locked, and it must show the
     * same numbers the rules judge on — two ways of counting the same thing is how a screen ends
     * up claiming a trophy is missing when the student already has it.
     */
    suspend fun trophyContext(): TrophyContext {
        val stats = stats()
        val mastery = allMastery()
        val cases = caseScores()
        val exams = examResults()

        val masteredSkills = mastery
            .filter { it.value >= TrophyContext.MASTERED_THRESHOLD }
            .map { it.skillId }
            .toSet()
        val masteredModules = curriculum.levels
            .flatMap { it.modules }
            .count { module -> module.skills.isNotEmpty() && masteredSkills.containsAll(module.skills) }

        return TrophyContext(
            lessonsCompleted = progressDao.all().size,
            lessonsTotal = curriculum.levels.sumOf { level -> level.modules.sumOf { it.lessons.size } },
            lessonsAbandoned = recentStudyEvents()
                .count { it.kind == StudyEvent.Kind.LESSON_ABANDONED },
            passedLevels = passedLevels(),
            casesCompletedByLevel = cases.keys
                .mapNotNull { id -> scenarios.case(id)?.level }
                .groupingBy { it }
                .eachCount(),
            casesTotalByLevel = scenarios.cases.groupingBy { it.level }.eachCount(),
            perfectCases = cases.count { (_, score) -> score >= PERFECT_PERCENT },
            finalCaseCompleted = FINAL_CASE_ID in cases,
            finalCasePerfect = (cases[FINAL_CASE_ID] ?: 0) >= PERFECT_PERCENT,
            examsPassed = exams.filter { it.passed }.map { it.level }.toSet(),
            examsTotal = curriculum.levels.count { it.modules.isNotEmpty() },
            examsFirstTry = exams.filter { it.passed && it.firstAttempt }.map { it.level }.toSet().size,
            perfectExams = exams.filter { it.passed && it.scorePercent >= PERFECT_PERCENT }
                .map { it.level }.toSet().size,
            labsCompleted = completedLabs().size,
            labsTotal = LabCatalogue.COUNT,
            exercisesSolved = solvedExercises().size,
            exercisesTotal = exercisesInSchool,
            skillsMastered = masteredSkills.size,
            modulesMastered = masteredModules,
            masteryAverage = if (mastery.isEmpty()) 0.0 else mastery.sumOf { it.value } / mastery.size,
            flawlessQuizzes = recentStudyEvents()
                .count { it.kind == StudyEvent.Kind.QUIZ_FLAWLESS },
            streakDays = stats.streakDays,
            recordStreakDays = stats.recordStreakDays,
            longestReturnDays = longestReturnDays(),
            diplomaEarned = DIPLOMA_TROPHY_ID in trophiesHeld() || diplomaConditionsMet(),
        )
    }

    /**
     * Hands out any trophy whose condition has just become true, and returns only the new
     * ones so the professor can announce them once and never again.
     */
    suspend fun awardTrophies(): List<Trophy> {
        val held = trophiesHeld()
        val fresh = trophyEngine.newlyEarned(trophyContext(), held)
        val now = timeProvider.now().toEpochMilli()
        fresh.forEach { trophyDao.save(TrophyEntity(it.id, now)) }
        return fresh
    }

    /**
     * The longest absence the student has come back from, in days.
     *
     * Read off the study diary rather than kept in a column, because it is a fact about the
     * dates already written there. Only gaps that were *closed* count: a student who has been
     * away for a month and has not returned has not earned anything for it.
     */
    private suspend fun longestReturnDays(): Int {
        val days = recentStudyEvents()
            .map { LocalDate.ofInstant(it.at, timeProvider.zone()) }
            .distinct()
            .sorted()
        return days.zipWithNext()
            .maxOfOrNull { (before, after) -> ChronoUnit.DAYS.between(before, after).toInt() }
            ?: 0
    }

    /**
     * Whether the diploma's own conditions are met.
     *
     * Duplicated deliberately from the diploma screen's rule in one direction only: the trophy
     * may never be *stricter* than the certificate. Once the certificate has been issued the
     * trophy is held for good, which is why [trophyContext] checks the held set first.
     */
    private suspend fun diplomaConditionsMet(): Boolean {
        val levels = curriculum.levels.filter { it.modules.isNotEmpty() }.map { it.level }.toSet()
        return levels.isNotEmpty() &&
            passedLevels().containsAll(levels) &&
            hasCompletedCase(FINAL_CASE_ID)
    }

    /**
     * Writes down how a level exam went.
     *
     * Kept as a diary event so that a failed attempt survives too: an exam nobody can fail on
     * the record is a formality, and the professor is supposed to remember both.
     */
    suspend fun recordExam(level: Int, passed: Boolean, scorePercent: Int) {
        record(
            if (passed) StudyEvent.Kind.EXAM_PASSED else StudyEvent.Kind.EXAM_FAILED,
            "$EXAM_PREFIX$level:$scorePercent",
        )
        registerStudyDay()
    }

    /** Levels whose exam has actually been sat and passed. */
    suspend fun examPassedLevels(): Set<Int> = examResults().filter { it.passed }.map { it.level }.toSet()

    /**
     * Every sitting of every exam, in the order they happened.
     *
     * [ExamResult.firstAttempt] is what makes "passed at the first try" a fact rather than a
     * guess: it is true only when nothing for that level came before it, which no later attempt
     * can turn back on.
     */
    suspend fun examResults(): List<ExamResult> {
        val seen = mutableSetOf<Int>()
        return recentStudyEvents()
            .filter {
                it.label.startsWith(EXAM_PREFIX) &&
                    (it.kind == StudyEvent.Kind.EXAM_PASSED || it.kind == StudyEvent.Kind.EXAM_FAILED)
            }
            .mapNotNull { event ->
                val body = event.label.removePrefix(EXAM_PREFIX)
                val level = body.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                ExamResult(
                    level = level,
                    passed = event.kind == StudyEvent.Kind.EXAM_PASSED,
                    scorePercent = body.substringAfter(':', "").toIntOrNull() ?: 0,
                    firstAttempt = seen.add(level),
                )
            }
    }

    /**
     * Records that a case was played through to the debriefing, and how it went.
     *
     * Kept in the diary rather than in a column of its own: it is an event with a date, and the
     * diary is already where events with dates live. The score is appended after a colon, which
     * is why every reader of these labels takes the id with [String.substringBefore] — the rows
     * written before scores existed simply have no colon, and still parse.
     */
    suspend fun completeCase(scenarioId: String, scorePercent: Int) {
        record(StudyEvent.Kind.EXAM_PASSED, "$CAPSTONE_PREFIX$scenarioId:$scorePercent")
        registerStudyDay()
    }

    /**
     * The cases the student has played through to the debriefing.
     *
     * Asked by id rather than "any of them", and that is the whole point of the method. The
     * school has more than one case now: a version that answered "yes, some case was
     * finished" would let the twelve-minute dilemma of the introduction tick the diploma's
     * requirement for the final night, and nobody would ever notice the certificate had
     * become free.
     */
    suspend fun completedCases(): Set<String> = caseScores().keys

    /**
     * The best score reached on each case.
     *
     * The best and not the last, because a trophy taken back for having replayed a case and
     * done worse would teach the student never to replay anything.
     */
    suspend fun caseScores(): Map<String, Int> = recentStudyEvents()
        .filter { it.kind == StudyEvent.Kind.EXAM_PASSED && it.label.startsWith(CAPSTONE_PREFIX) }
        .map { it.label.removePrefix(CAPSTONE_PREFIX) }
        .groupBy({ it.substringBefore(':') }, { it.substringAfter(':', "").toIntOrNull() ?: 0 })
        .mapValues { (_, scores) -> scores.max() }

    suspend fun hasCompletedCase(scenarioId: String): Boolean = scenarioId in completedCases()

    /**
     * Records a workshop taken all the way through — every item judged.
     *
     * Only the fact, never the result. Labs are the one place in this school where the student
     * is allowed to be wrong without it going on their record, and that freedom is the reason
     * they work: writing down the score would quietly turn a workshop into another test.
     */
    suspend fun completeLab(labId: String) {
        if (labId in completedLabs()) return
        record(StudyEvent.Kind.LAB_COMPLETED, labId)
        registerStudyDay()
    }

    suspend fun completedLabs(): Set<String> = recentStudyEvents()
        .filter { it.kind == StudyEvent.Kind.LAB_COMPLETED }
        .map { it.label }
        .toSet()

    /** An interrogation closed without a single wrong answer. */
    suspend fun recordFlawlessQuiz(label: String) {
        record(StudyEvent.Kind.QUIZ_FLAWLESS, label)
    }

    /**
     * A workshop exercise solved: a rule written by the student that caught the whole attack
     * and nothing else.
     *
     * Only a perfect run counts. Unlike a lesson, where sitting the interrogation is what
     * matters and the score is the school's business, here the score *is* the exercise — a
     * rule that half works is a rule that would be switched off by the second week.
     */
    suspend fun solveExercise(exerciseId: String) {
        if (exerciseId in solvedExercises()) return
        record(StudyEvent.Kind.EXERCISE_SOLVED, exerciseId)
        registerStudyDay()
    }

    suspend fun solvedExercises(): Set<String> = recentStudyEvents()
        .filter { it.kind == StudyEvent.Kind.EXERCISE_SOLVED }
        .map { it.label }
        .toSet()

    suspend fun earnedTrophies(): List<Trophy> =
        trophiesHeld().mapNotNull { trophyEngine.byId(it) }

    /** "Ricomincia da capo": everything the school knows about this student, forgotten. */
    /**
     * Which questions the student has already been told are open.
     *
     * Kept so the professor can announce what has just opened instead of announcing the same
     * thing at every launch. Nothing about the student's answers or interests is in here: it
     * is a list of ids the app has already shown them.
     */
    suspend fun seenQuestions(): Set<String> = seenQuestionDao.all().toSet()

    suspend fun markQuestionsSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = timeProvider.now().toEpochMilli()
        seenQuestionDao.save(ids.map { SeenQuestionEntity(questionId = it, seenAt = now) })
    }

    suspend fun eraseEverything() {
        // The student goes last on purpose. The app decides where to open by watching the
        // saved profile, so clearing it first would send someone back to enrolment while
        // their old mastery and diary were still being deleted behind the interview.
        masteryDao.clear()
        reviewDao.clear()
        studyEventDao.clear()
        progressDao.clear()
        statsDao.clear()
        trophyDao.clear()
        seenQuestionDao.clear()
        studentDao.clear()
    }

    companion object {
        /**
         * The case that stands for the whole school.
         *
         * Written here as a constant because the records module has no business reading the
         * scenarios; a test in the app ties it to the content, so renaming the final case in
         * JSON breaks a build instead of quietly making a badge free.
         */
        const val FINAL_CASE_ID = "capstone_incidente"

        /** The trophy that stands for the certificate, held for good once it has been issued. */
        const val DIPLOMA_TROPHY_ID = "diploma"

        /** What "perfetto" means for a case or an exam. */
        const val PERFECT_PERCENT = 100

        private const val SECONDS_IN_MINUTE = 60
        private const val DIARY_CAPACITY = 500
        private const val CAPSTONE_PREFIX = "capstone:"
        private const val EXAM_PREFIX = "esame:"
    }
}

/** One sitting of one exam. */
data class ExamResult(
    val level: Int,
    val passed: Boolean,
    val scorePercent: Int,
    /** True when this was the first time this level's exam was sat at all. */
    val firstAttempt: Boolean,
)

// --- Mapping ---------------------------------------------------------------------------

private fun StudentEntity.toDomain() = StudentProfile(
    name = name,
    nickname = nickname,
    birthDate = birthDate?.let(LocalDate::parse),
    goal = enumValueOf<LearningGoal>(goal),
    tone = enumValueOf<TutorTone>(tone),
    dailyBudget = enumValueOf<DailyBudget>(dailyBudget),
    enrolledOn = LocalDate.parse(enrolledOn),
    ethicalPactSigned = ethicalPactSigned,
    reminderAt = reminderAt?.let(LocalTime::parse),
)

private fun StudentProfile.toEntity() = StudentEntity(
    name = name,
    nickname = nickname,
    birthDate = birthDate?.toString(),
    goal = goal.name,
    tone = tone.name,
    dailyBudget = dailyBudget.name,
    enrolledOn = enrolledOn.toString(),
    ethicalPactSigned = ethicalPactSigned,
    reminderAt = reminderAt?.toString(),
)

private fun MasteryEntity.toDomain() = Mastery(
    skillId = skillId,
    value = value,
    attempts = attempts,
    consecutiveCorrect = consecutiveCorrect,
    lastPracticed = lastPracticedAt?.let(Instant::ofEpochMilli),
)

private fun Mastery.toEntity() = MasteryEntity(
    skillId = skillId,
    value = value,
    attempts = attempts,
    consecutiveCorrect = consecutiveCorrect,
    lastPracticedAt = lastPracticed?.toEpochMilli(),
)

private fun ReviewEntity.toDomain() = ReviewItem(
    skillId = skillId,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    repetitions = repetitions,
    dueOn = LocalDate.parse(dueOn),
    flaggedForLuck = flaggedForLuck,
)

private fun ReviewItem.toEntity() = ReviewEntity(
    skillId = skillId,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    repetitions = repetitions,
    dueOn = dueOn.toString(),
    flaggedForLuck = flaggedForLuck,
)

private fun StudyEventEntity.toDomain() = StudyEvent(
    kind = enumValueOf<StudyEvent.Kind>(kind),
    label = label,
    at = Instant.ofEpochMilli(at),
)
