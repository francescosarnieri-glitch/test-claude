package com.cybersensei.academy.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.BadgeEngine
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.SchoolClock
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.formatTimeAtSchool
import com.cybersensei.academy.core.model.Level
import com.cybersensei.academy.engine.mastery.LevelGate
import com.cybersensei.academy.engine.mastery.Mastery
import com.cybersensei.academy.engine.tutor.StudyEvent
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * How a single skill stands.
 *
 * [state] exists so the report never relies on the bar's length or colour alone: every skill
 * carries a word for what it is, which is also what a screen reader announces.
 */
data class SkillRow(
    val id: String,
    val label: String,
    val percent: Int,
    val attempts: Int,
    val state: SkillState,
)

enum class SkillState(val label: String, val icon: String) {
    /** Never answered. Not the same as "known badly", and the report must not confuse them. */
    UNTOUCHED("Mai affrontata", "—"),
    FRAGILE("Fragile", "!"),
    TO_CONSOLIDATE("Da consolidare", "~"),
    SOLID("Solida", "✓"),
}

data class ModuleReport(
    val id: String,
    val title: String,
    val skills: List<SkillRow>,
    val averagePercent: Int,
    val passed: Boolean,
)

data class LevelReport(
    val order: Int,
    val name: String,
    val subtitle: String,
    val unlocked: Boolean,
    val passed: Boolean,
    val averagePercent: Int,
    val modulesPassed: Int,
    val modules: List<ModuleReport>,
)

/** One day of the recent history: did the student show up, and did it count for the streak. */
data class StudyDay(val date: LocalDate, val studied: Boolean)

data class Summary(
    val masteryPercent: Int,
    val skillsTouched: Int,
    val skillsTotal: Int,
    val lessonsDone: Int,
    val lessonsTotal: Int,
    val experiencePoints: Int,
    val streakDays: Int,
    val recordStreakDays: Int,
    val studyMinutes: Int,
    val badgesEarned: Int,
    val badgesTotal: Int,
    val dueReviews: Int,
)

data class ReportUiState(
    val professorLine: String = "",
    val summary: Summary? = null,
    val levels: List<LevelReport> = emptyList(),
    /** Weakest first: the report's only piece of advice, and it is an ordered list. */
    val toRevise: List<SkillRow> = emptyList(),
    val recentDays: List<StudyDay> = emptyList(),
    val hasAnyData: Boolean = false,
)

/**
 * The report card.
 *
 * Everything here is measured, never declared: a level counts as passed only when the same
 * gate that unlocks it says so, and a skill that has never been answered is reported as
 * untouched rather than as zero. Those two rules are what stop a report card from being a
 * congratulation screen.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val badgeEngine: BadgeEngine,
    private val tutor: TutorEngine,
    private val timeProvider: TimeProvider,
    private val clock: SchoolClock,
) : ViewModel() {

    /**
     * The stopwatch, ticking on screen.
     *
     * A number that is being measured has to look like it: a static «4 minuti» is
     * indistinguishable from a number somebody made up, which is precisely what the old one
     * was. Reading the clock costs nothing — it is arithmetic on an instant, not a query.
     */
    val timeAtSchool: StateFlow<String> = flow {
        while (true) {
            emit(formatTimeAtSchool(clock.totalSeconds))
            delay(TICK_MILLIS)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(TICK_MILLIS),
        formatTimeAtSchool(0),
    )

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = repository.snapshot()
            val mastery = repository.allMastery().associateBy { it.skillId }
            val unlocked = repository.unlockedLevels()
            val passedLevels = repository.passedLevels()
            val doneLessons = repository.completedLessonIds()
            val stats = repository.stats()
            val badges = repository.badgesHeld()

            val levels = curriculum.levels.map { level ->
                val modules = level.modules.map { module ->
                    val gate = LevelGate.evaluate(module.skills, module.skills.mapNotNull { mastery[it] })
                    ModuleReport(
                        id = module.id,
                        title = module.title,
                        skills = module.skills.map { skill -> skillRow(skill, mastery[skill]) },
                        averagePercent = gate.averagePercent,
                        passed = gate.passed,
                    )
                }
                LevelReport(
                    order = level.level,
                    name = Level.fromOrder(level.level)?.italianName ?: level.title,
                    subtitle = Level.fromOrder(level.level)?.subtitle.orEmpty(),
                    unlocked = level.level in unlocked,
                    passed = level.level in passedLevels,
                    averagePercent = modules.map { it.averagePercent }.averageOrZero(),
                    modulesPassed = modules.count { it.passed },
                    modules = modules,
                )
            }

            val allSkills = levels.flatMap { it.modules }.flatMap { it.skills }
            // Only what the student has actually met: telling someone to revise a skill from
            // a level they cannot open yet is noise dressed up as advice.
            val reviseCandidates = levels
                .filter { it.unlocked }
                .flatMap { it.modules }
                .flatMap { it.skills }
                .filter { it.state == SkillState.FRAGILE || it.state == SkillState.TO_CONSOLIDATE }
                .sortedBy { it.percent }

            _uiState.value = ReportUiState(
                professorLine = tutor.speak(TutorEvent.ReportOpened, snapshot).text,
                summary = Summary(
                    masteryPercent = (snapshot.masteryAverage * 100).toInt(),
                    skillsTouched = allSkills.count { it.state != SkillState.UNTOUCHED },
                    skillsTotal = allSkills.size,
                    lessonsDone = doneLessons.size,
                    lessonsTotal = curriculum.lessons.size,
                    experiencePoints = stats.experiencePoints,
                    streakDays = stats.streakDays,
                    recordStreakDays = stats.recordStreakDays,
                    studyMinutes = stats.studySeconds / 60,
                    badgesEarned = badges.size,
                    badgesTotal = badgeEngine.all().size,
                    dueReviews = snapshot.dueReviews,
                ),
                levels = levels,
                toRevise = reviseCandidates.take(REVISE_LIMIT),
                recentDays = recentDays(repository.recentStudyEvents()),
                hasAnyData = allSkills.any { it.state != SkillState.UNTOUCHED } ||
                    doneLessons.isNotEmpty(),
            )
        }
    }

    private fun skillRow(skillId: String, mastery: Mastery?): SkillRow = SkillRow(
        id = skillId,
        label = skillId.replace('_', ' '),
        percent = mastery?.percent ?: 0,
        attempts = mastery?.attempts ?: 0,
        state = when {
            mastery == null || mastery.attempts == 0 -> SkillState.UNTOUCHED
            mastery.value >= LevelGate.AVERAGE_REQUIRED -> SkillState.SOLID
            mastery.value >= LevelGate.MINIMUM_PER_SKILL -> SkillState.TO_CONSOLIDATE
            else -> SkillState.FRAGILE
        },
    )

    /**
     * The last two weeks, oldest first.
     *
     * Built from the diary rather than from the streak counter: the streak says only how
     * long the current run is, while this shows the gaps — which is the thing a student
     * actually recognises about their own habits.
     */
    private fun recentDays(events: List<StudyEvent>): List<StudyDay> {
        val zone = ZoneId.systemDefault()
        val studied = events.map { it.at.atZone(zone).toLocalDate() }.toSet()
        val today = timeProvider.today()
        return (HISTORY_DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            StudyDay(date = date, studied = date in studied)
        }
    }

    private fun List<Int>.averageOrZero(): Int = if (isEmpty()) 0 else sum() / size

    private companion object {
        /** Un giro al secondo: e' un cronometro, non un'animazione. */
        const val TICK_MILLIS = 1_000L

        const val REVISE_LIMIT = 5
        const val HISTORY_DAYS = 14
    }
}
