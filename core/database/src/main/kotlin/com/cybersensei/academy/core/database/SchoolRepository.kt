package com.cybersensei.academy.core.database

import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Badge
import com.cybersensei.academy.core.curriculum.BadgeContext
import com.cybersensei.academy.core.curriculum.BadgeEngine
import com.cybersensei.academy.core.curriculum.Curriculum
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
    private val badgeDao: BadgeDao,
    private val seenQuestionDao: SeenQuestionDao,
    private val curriculum: Curriculum,
    private val badgeEngine: BadgeEngine,
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

    suspend fun badgesHeld(): Set<String> = badgeDao.all().map { it.badgeId }.toSet()

    fun observeBadges(): Flow<List<String>> =
        badgeDao.observeAll().map { list -> list.map { it.badgeId } }

    /**
     * Hands out any badge whose condition has just become true, and returns only the new
     * ones so the professor can announce them once and never again.
     */
    suspend fun awardBadges(): List<Badge> {
        val stats = stats()
        val mastery = allMastery()
        val context = BadgeContext(
            lessonsCompleted = progressDao.all().size,
            streakDays = stats.streakDays,
            masteryAverage = if (mastery.isEmpty()) 0.0 else mastery.sumOf { it.value } / mastery.size,
            passedLevels = passedLevels(),
            capstoneCompleted = hasCompletedCase(FINAL_CASE_ID),
        )
        val held = badgesHeld()
        val fresh = badgeEngine.newlyEarned(context, held)
        val now = timeProvider.now().toEpochMilli()
        fresh.forEach { badgeDao.save(BadgeEntity(it.id, now)) }
        return fresh
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
    suspend fun examPassedLevels(): Set<Int> = recentStudyEvents()
        .filter { it.kind == StudyEvent.Kind.EXAM_PASSED && it.label.startsWith(EXAM_PREFIX) }
        .mapNotNull { it.label.removePrefix(EXAM_PREFIX).substringBefore(':').toIntOrNull() }
        .toSet()

    /**
     * Records that the final exercise was played through to the debriefing.
     *
     * Kept in the diary rather than in a column of its own: it is an event with a date, and
     * the diary is already where events with dates live.
     */
    suspend fun completeCapstone(scenarioId: String) {
        record(StudyEvent.Kind.EXAM_PASSED, "$CAPSTONE_PREFIX$scenarioId")
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
    suspend fun completedCases(): Set<String> = recentStudyEvents()
        .filter { it.kind == StudyEvent.Kind.EXAM_PASSED && it.label.startsWith(CAPSTONE_PREFIX) }
        .map { it.label.removePrefix(CAPSTONE_PREFIX) }
        .toSet()

    suspend fun hasCompletedCase(scenarioId: String): Boolean = scenarioId in completedCases()

    suspend fun earnedBadges(): List<Badge> =
        badgesHeld().mapNotNull { badgeEngine.byId(it) }

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
        badgeDao.clear()
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

        private const val SECONDS_IN_MINUTE = 60
        private const val DIARY_CAPACITY = 500
        private const val CAPSTONE_PREFIX = "capstone:"
        private const val EXAM_PREFIX = "esame:"
    }
}

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
