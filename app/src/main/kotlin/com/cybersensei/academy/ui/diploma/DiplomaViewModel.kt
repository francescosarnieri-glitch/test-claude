package com.cybersensei.academy.ui.diploma

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.model.Level
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One requirement of the diploma, and whether it is met. */
data class Requirement(val label: String, val met: Boolean, val detail: String)

/** Everything printed on the certificate. Assembled once, from measured facts only. */
data class Diploma(
    val studentName: String,
    val enrolledOn: LocalDate,
    val awardedOn: LocalDate,
    val daysEnrolled: Long,
    val lessonsCompleted: Int,
    val lessonsTotal: Int,
    val studyMinutes: Int,
    val masteryPercent: Int,
    val trophies: Int,
    val trophiesTotal: Int,
    val examScores: Map<String, Int>,
)

data class DiplomaUiState(
    val requirements: List<Requirement> = emptyList(),
    val diploma: Diploma? = null,
    val loaded: Boolean = false,
) {
    val earned: Boolean get() = diploma != null
    val missing: List<Requirement> get() = requirements.filterNot { it.met }
}

/**
 * The diploma.
 *
 * Awarded on the three level exams plus the capstone, and on nothing else: not on time
 * spent, not on lessons opened, not on showing up. A certificate that can be earned by
 * persistence alone is a participation medal, and this school spent thirty modules arguing
 * that measured understanding is a different thing.
 */
@HiltViewModel
class DiplomaViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiplomaUiState())
    val uiState: StateFlow<DiplomaUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val profile = repository.profile()
            val examsPassed = repository.examPassedLevels()
            // Il caso finale per id: da quando i casi sono piu' d'uno, «un capstone
            // qualsiasi» avrebbe fatto spuntare questo requisito al dilemma da dodici minuti.
            val capstone = repository.hasCompletedCase(SchoolRepository.FINAL_CASE_ID)
            val scores = examScores()

            // The introduction is not examined for the diploma: it exists to explain what the
            // school is, and holding a certificate hostage to it would be pedantry. The three
            // graded levels are the ones the professor keeps saying out loud.
            val requirements = curriculum.levels.filter { it.level > 0 }.map { level ->
                val name = Level.fromOrder(level.level)?.italianName ?: level.title
                Requirement(
                    label = "Esame — $name",
                    met = level.level in examsPassed,
                    detail = scores[name]?.let { "superato con il $it%" }
                        ?: "non ancora superato",
                )
            } + Requirement(
                label = "La notte dell'Incidente",
                met = capstone,
                detail = if (capstone) "affrontata fino al debriefing" else "non ancora affrontata",
            )

            val stats = repository.stats()
            val mastery = repository.allMastery()

            _uiState.value = DiplomaUiState(
                loaded = true,
                requirements = requirements,
                diploma = if (requirements.all { it.met } && profile != null) {
                    Diploma(
                        studentName = profile.name,
                        enrolledOn = profile.enrolledOn,
                        awardedOn = timeProvider.today(),
                        daysEnrolled = profile.daysEnrolled(timeProvider.today()),
                        lessonsCompleted = repository.completedLessonIds().size,
                        lessonsTotal = curriculum.lessons.size,
                        studyMinutes = stats.studySeconds / 60,
                        masteryPercent = if (mastery.isEmpty()) {
                            0
                        } else {
                            ((mastery.sumOf { it.value } / mastery.size) * 100).toInt()
                        },
                        trophies = repository.trophiesHeld().size,
                        trophiesTotal = repository.trophiesInSchool,
                        examScores = scores,
                    )
                } else {
                    null
                },
            )
        }
    }

    /**
     * The best score recorded for each level's exam.
     *
     * Best rather than latest: the diploma records what the student proved they could do,
     * and a later attempt taken carelessly should not erase it.
     */
    private suspend fun examScores(): Map<String, Int> = repository.recentStudyEvents()
        .filter { it.label.startsWith(EXAM_PREFIX) }
        .mapNotNull { event ->
            val parts = event.label.removePrefix(EXAM_PREFIX).split(":")
            val level = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val score = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            (Level.fromOrder(level)?.italianName ?: "Livello $level") to score
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, scores) -> scores.max() }

    private companion object {
        const val EXAM_PREFIX = "esame:"
    }
}
