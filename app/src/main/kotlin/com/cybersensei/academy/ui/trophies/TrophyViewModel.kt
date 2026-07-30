package com.cybersensei.academy.ui.trophies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Trophy
import com.cybersensei.academy.core.curriculum.TrophyCondition
import com.cybersensei.academy.core.curriculum.TrophyContext
import com.cybersensei.academy.core.curriculum.TrophyEngine
import com.cybersensei.academy.core.curriculum.TrophyFamily
import com.cybersensei.academy.core.database.SchoolRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One medal on the wall. */
data class TrophyRow(
    val trophy: Trophy,
    val earned: Boolean,
    val earnedOn: LocalDate?,
    /**
     * How far along the student is, when the condition is a number worth counting towards.
     *
     * Null when counting would say nothing useful — a level either has been passed or has not,
     * and "0 su 1" is a worse sentence than no sentence.
     */
    val progress: TrophyProgress?,
)

data class TrophyProgress(val current: Int, val target: Int) {
    val percent: Int get() = if (target <= 0) 0 else ((current * 100) / target).coerceIn(0, 100)
}

data class TrophyShelf(
    val family: TrophyFamily,
    val rows: List<TrophyRow>,
) {
    val earned: Int get() = rows.count { it.earned }
    val total: Int get() = rows.size
}

data class TrophyUiState(
    val loading: Boolean = true,
    val shelves: List<TrophyShelf> = emptyList(),
    val earned: Int = 0,
    val total: Int = 0,
    /** The medal the student has tapped, if any. */
    val opened: TrophyRow? = null,
) {
    val percent: Int get() = if (total <= 0) 0 else (earned * 100) / total
}

/**
 * The trophy wall.
 *
 * Everything locked is shown, with its name and how it is earned: a wall of question marks
 * would be a mystery instead of a set of goals, and the student asked for goals. What stays
 * hidden until the medal is won is only the symbol in the middle — that much is the reward.
 */
@HiltViewModel
class TrophyViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val trophies: TrophyEngine,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrophyUiState())
    val uiState: StateFlow<TrophyUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // Awarded on the way in as well as in the classroom: a student who finishes a case
            // and comes straight here must not find the medal missing.
            repository.awardTrophies()
            val held = repository.trophiesHeld()
            val dates = repository.trophyDates()
            val context = repository.trophyContext()
            val zone = timeProvider.zone()

            val shelves = trophies.byFamily().map { (family, list) ->
                TrophyShelf(
                    family = family,
                    rows = list.map { trophy ->
                        TrophyRow(
                            trophy = trophy,
                            earned = trophy.id in held,
                            earnedOn = dates[trophy.id]
                                ?.let { LocalDate.ofInstant(Instant.ofEpochMilli(it), zone) },
                            progress = progressOf(trophy, context),
                        )
                    },
                )
            }

            _uiState.value = TrophyUiState(
                loading = false,
                shelves = shelves,
                earned = shelves.sumOf { it.earned },
                total = shelves.sumOf { it.total },
            )
        }
    }

    fun open(row: TrophyRow) {
        _uiState.value = _uiState.value.copy(opened = row)
    }

    fun close() {
        _uiState.value = _uiState.value.copy(opened = null)
    }

    /**
     * Progress towards a locked medal, for the conditions where a number helps.
     *
     * Read from the same context the rules judge on, never recomputed here: two ways of counting
     * the same thing is how a wall ends up saying "9 su 10" next to a medal the student holds.
     */
    private fun progressOf(trophy: Trophy, context: TrophyContext): TrophyProgress? {
        val target = trophy.condition.value
        return when (trophy.condition.type) {
            TrophyCondition.LESSONS_COMPLETED ->
                TrophyProgress(context.lessonsCompleted, target)

            TrophyCondition.ALL_LESSONS ->
                TrophyProgress(context.lessonsCompleted, context.lessonsTotal)

            TrophyCondition.CASES_COMPLETED ->
                TrophyProgress(context.casesCompleted, target)

            TrophyCondition.CASES_IN_LEVEL ->
                TrophyProgress(
                    context.casesCompletedByLevel[target] ?: 0,
                    context.casesTotalByLevel[target] ?: 0,
                )

            TrophyCondition.ALL_CASES ->
                TrophyProgress(context.casesCompleted, context.casesTotal)

            TrophyCondition.PERFECT_CASES ->
                TrophyProgress(context.perfectCases, target)

            TrophyCondition.EXAMS_PASSED ->
                TrophyProgress(context.examsPassed.size, target)

            TrophyCondition.ALL_EXAMS ->
                TrophyProgress(context.examsPassed.size, context.examsTotal)

            TrophyCondition.LABS_COMPLETED ->
                TrophyProgress(context.labsCompleted, target)

            TrophyCondition.ALL_LABS ->
                TrophyProgress(context.labsCompleted, context.labsTotal)

            TrophyCondition.SKILLS_MASTERED ->
                TrophyProgress(context.skillsMastered, target)

            TrophyCondition.MODULES_MASTERED ->
                TrophyProgress(context.modulesMastered, target)

            TrophyCondition.STREAK_DAYS ->
                TrophyProgress(context.streakDays, target)

            TrophyCondition.RECORD_STREAK ->
                TrophyProgress(context.recordStreakDays, target)

            TrophyCondition.NOTHING_ABANDONED ->
                TrophyProgress(context.lessonsCompleted, target)

            // Everything else is a yes or a no. A bar on those would be theatre.
            else -> null
        }
    }
}
