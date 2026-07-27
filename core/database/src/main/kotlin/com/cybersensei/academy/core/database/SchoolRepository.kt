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
            totalStudyMinutes = stats.totalStudyMinutes,
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

    suspend fun completeLesson(lessonId: String, moduleId: String, title: String, minutes: Int) {
        progressDao.save(
            LessonProgressEntity(lessonId, moduleId, timeProvider.now().toEpochMilli()),
        )
        record(StudyEvent.Kind.LESSON_COMPLETED, title)
        addStudyMinutes(minutes)
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

    private suspend fun addStudyMinutes(minutes: Int) {
        val stats = statsDao.get() ?: StatsEntity()
        statsDao.save(stats.copy(totalStudyMinutes = stats.totalStudyMinutes + minutes))
    }

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
    suspend fun passedLevels(): Set<Int> {
        val mastery = allMastery()
        return curriculum.levels.filter { level ->
            level.modules.isNotEmpty() && level.modules.all { module ->
                LevelGate.evaluate(module.skills, mastery).passed
            }
        }.map { it.level }.toSet()
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
        )
        val held = badgesHeld()
        val fresh = badgeEngine.newlyEarned(context, held)
        val now = timeProvider.now().toEpochMilli()
        fresh.forEach { badgeDao.save(BadgeEntity(it.id, now)) }
        return fresh
    }

    suspend fun earnedBadges(): List<Badge> =
        badgesHeld().mapNotNull { badgeEngine.byId(it) }

    /** "Ricomincia da capo": everything the school knows about this student, forgotten. */
    suspend fun eraseEverything() {
        studentDao.clear()
        masteryDao.clear()
        reviewDao.clear()
        studyEventDao.clear()
        progressDao.clear()
        statsDao.clear()
        badgeDao.clear()
    }

    private companion object {
        const val DIARY_CAPACITY = 500
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
