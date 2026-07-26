package com.cybersensei.academy.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.DeterministicRandom
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.nlu.AnswerResult
import com.cybersensei.academy.engine.nlu.FaqEntry
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.QuestionAnswerer
import com.cybersensei.academy.engine.tutor.StudentSnapshot
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A question the student asked and what the professor said back. */
data class Exchange(
    val id: Long,
    val question: String,
    val answer: String,
    val understood: Boolean,
    /**
     * The syllabus question the professor actually answered. Shown because retrieval is not
     * telepathy: the student must be able to see that he understood something slightly
     * different from what was asked, instead of silently getting the wrong answer.
     */
    val answeredTopic: String? = null,
    /** Set when the topic belongs to a level the student has not unlocked yet. */
    val aheadOfLevel: String? = null,
    val alternatives: List<Suggestion> = emptyList(),
)

/** A question the student can ask with one tap. */
data class Suggestion(val id: String, val text: String, val level: Int)

data class StudyUiState(
    val openingLine: String = "",
    val draft: String = "",
    /** Newest first: the last answer must be readable without scrolling anywhere. */
    val exchanges: List<Exchange> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val notes: List<Note> = emptyList(),
    val corpusSize: Int = 0,
)

/**
 * One thing the professor has noticed about this student.
 *
 * [detail] is deliberately separate from [text]: the observation is a sentence, the number
 * behind it is evidence. A student who is told "sei debole qui" deserves to see why.
 */
data class Note(val text: String, val detail: String? = null, val warning: Boolean = false)

/**
 * The professor's study: the student asks in their own words, entirely offline.
 *
 * Retrieval can be wrong, so the screen is built around admitting it — the professor names
 * the topic he understood, offers near misses, and when nothing matches well enough he says
 * so instead of inventing. That last part is the whole reason this is a rule-based engine
 * and not a generative one: a school that occasionally makes up a security answer is worse
 * than one that says "questo non te lo so dire".
 */
@HiltViewModel
class StudyViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val knowledgeBase: KnowledgeBase,
    private val answerer: QuestionAnswerer,
    private val tutor: TutorEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudyUiState())
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private var snapshot: StudentSnapshot? = null
    private var unlockedLevels: Set<Int> = setOf(0)
    private var asked = 0L

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snap = repository.snapshot()
            snapshot = snap
            unlockedLevels = repository.unlockedLevels()

            _uiState.value = _uiState.value.copy(
                openingLine = tutor.speak(TutorEvent.StudyOpened, snap).text,
                suggestions = pickSuggestions(snap),
                notes = observationsAbout(snap),
                corpusSize = knowledgeBase.entries.size,
            )
        }
    }

    fun onDraftChange(text: String) {
        _uiState.value = _uiState.value.copy(draft = text)
    }

    fun ask(question: String = _uiState.value.draft) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        asked++

        val exchange = when (val result = answerer.ask(trimmed)) {
            is AnswerResult.Found -> Exchange(
                id = asked,
                question = trimmed,
                answer = result.entry.answer,
                understood = true,
                answeredTopic = result.entry.question,
                aheadOfLevel = levelWarningFor(result.entry),
                alternatives = result.alternatives.map { it.toSuggestion() },
            )

            is AnswerResult.NotUnderstood -> Exchange(
                id = asked,
                question = trimmed,
                // Never silence, and never invention: the dialogue engine has a pool for
                // exactly this moment, and the nearest entry is offered as a lead.
                answer = snapshot
                    ?.let { tutor.speak(TutorEvent.UnknownQuestion(trimmed), it).text }
                    ?: DEFAULT_UNKNOWN_LINE,
                understood = false,
                alternatives = listOfNotNull(result.nearest?.toSuggestion()),
            )
        }

        _uiState.value = _uiState.value.copy(
            draft = "",
            exchanges = listOf(exchange) + _uiState.value.exchanges,
        )
    }

    fun askSuggestion(suggestion: Suggestion) {
        val entry = knowledgeBase.entries.firstOrNull { it.id == suggestion.id } ?: return
        ask(entry.question)
    }

    fun clearHistory() {
        _uiState.value = _uiState.value.copy(exchanges = emptyList())
        refresh()
    }

    /**
     * Questions worth starting from, drawn from what the student has already unlocked.
     *
     * Deterministic on the student and the number of questions asked, so the list changes as
     * the conversation goes on but never flickers on a recomposition.
     */
    private fun pickSuggestions(snap: StudentSnapshot): List<Suggestion> {
        val reachable = knowledgeBase.entries.filter { it.level in unlockedLevels }
            .ifEmpty { knowledgeBase.entries }
        if (reachable.isEmpty()) return emptyList()
        val random = DeterministicRandom.forSeed(snap.profile?.name, unlockedLevels.size, asked)
        return reachable.shuffled(random).take(SUGGESTION_COUNT).map { it.toSuggestion() }
    }

    /**
     * What the professor has noticed. Facts he already holds, said out loud — the point of
     * the study is that the student can see the record kept on them.
     */
    private fun observationsAbout(snap: StudentSnapshot): List<Note> = buildList {
        addAll(masteryNotes(snap))

        if (snap.streakDays > 0) {
            add(
                Note(
                    text = "Sei venuto ${snap.streakDays} giorni di fila.",
                    detail = if (snap.recordStreakDays > snap.streakDays) {
                        "Il tuo record resta ${snap.recordStreakDays}."
                    } else {
                        "È il tuo record."
                    },
                ),
            )
        }

        if (snap.dueReviews > 0) {
            add(
                Note(
                    text = "Hai ${snap.dueReviews} ripassi in scadenza.",
                    detail = "Un argomento ripassato al momento giusto costa un minuto; " +
                        "ristudiato da capo, costa una lezione.",
                    warning = true,
                ),
            )
        }

        snap.unfinishedLessonTitle?.let {
            add(
                Note(
                    text = "Hai lasciato a metà «$it».",
                    detail = "Le lezioni interrotte non contano: la riprendiamo quando vuoi.",
                    warning = true,
                ),
            )
        }

        if (snap.totalStudyMinutes > 0) {
            add(Note(text = "Hai studiato ${snap.totalStudyMinutes} minuti in tutto."))
        }

        if (isEmpty()) {
            add(
                Note(
                    text = "Per ora non ho osservazioni su di te.",
                    detail = "Servono qualche lezione e qualche interrogazione prima che " +
                        "possa dirti qualcosa di sensato.",
                ),
            )
        }
    }

    private fun masteryNotes(snap: StudentSnapshot): List<Note> = buildList {
        if (snap.masteryAverage > 0.0) {
            add(
                Note(
                    text = "La tua padronanza media è del ${(snap.masteryAverage * 100).toInt()}%.",
                    detail = "Misurata sulle risposte, non sulle lezioni aperte.",
                ),
            )
        }
        snap.weakestSkillLabel?.let { skill ->
            add(
                Note(
                    text = "L'argomento su cui sei più fragile è «${skill.replace('_', ' ')}».",
                    detail = curriculum.modules.firstOrNull { skill in it.skills }
                        ?.let { "Modulo: ${it.title}." },
                    warning = true,
                ),
            )
        }
        val recurring = snap.recurringMisconceptionLabel
        if (recurring != null && snap.recurringMisconceptionTimes > 1) {
            add(
                Note(
                    text = "Ci sei ricascato ${snap.recurringMisconceptionTimes} volte su " +
                        "«${recurring.replace('_', ' ')}».",
                    detail = "Non è distrazione: è un'idea sbagliata che tieni per buona.",
                    warning = true,
                ),
            )
        }
    }

    /** The FAQ knows which level introduces a topic, so the professor can say "ci arriveremo". */
    private fun levelWarningFor(entry: FaqEntry): String? {
        if (entry.level in unlockedLevels) return null
        val name = curriculum.level(entry.level)?.title ?: "un livello successivo"
        return "Questo argomento appartiene al livello $name: te lo dico volentieri, " +
            "ma lo studieremo per bene più avanti."
    }

    private fun FaqEntry.toSuggestion() = Suggestion(id = id, text = question, level = level)

    private companion object {
        const val SUGGESTION_COUNT = 4
        const val DEFAULT_UNKNOWN_LINE =
            "Questa non te la so dire, e preferisco ammetterlo piuttosto che inventare."
    }
}
