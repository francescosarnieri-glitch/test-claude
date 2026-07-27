package com.cybersensei.academy.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.nlu.AnswerResult
import com.cybersensei.academy.engine.nlu.Branch
import com.cybersensei.academy.engine.nlu.ConversationMemory
import com.cybersensei.academy.engine.nlu.EntryKind
import com.cybersensei.academy.engine.nlu.FaqEntry
import com.cybersensei.academy.engine.nlu.ItalianText
import com.cybersensei.academy.engine.nlu.Miss
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.QuestionAnswerer
import com.cybersensei.academy.engine.nlu.StudyPaths
import com.cybersensei.academy.engine.nlu.Turn
import com.cybersensei.academy.engine.tutor.StudentSnapshot
import com.cybersensei.academy.engine.tutor.TutorEngine
import com.cybersensei.academy.engine.tutor.TutorEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Which entry answered, for the professor's own memory of what he said. */
    val entryId: String? = null,
    /**
     * The professor is not answering: he is putting his guesses in front of the student.
     * The alternatives are then the point of the card, not a footnote.
     */
    val choosing: Boolean = false,
)

/** A question the student can ask with one tap. */
data class Suggestion(val id: String, val text: String, val level: Int)

/** One place the professor can take the student, as the screen needs it. */
data class Place(
    val id: String,
    val title: String,
    val subtitle: String?,
    /** How many questions are left down there, so a picked-clean branch says so. */
    val remaining: Int,
)

data class StudyUiState(
    val openingLine: String = "",
    val notes: List<Note> = emptyList(),
    val corpusSize: Int = 0,
    /** Where the student is, root first. Empty at the top. */
    val trail: List<Place> = emptyList(),
    /** What the professor says on arriving here. */
    val line: String? = null,
    /** Further places to go from here. */
    val places: List<Place> = emptyList(),
    /** Questions to ask here, the ones already asked removed. */
    val questions: List<Suggestion> = emptyList(),
    /** True when this branch had questions and the student has asked them all. */
    val exhausted: Boolean = false,
    /** The keyboard, hidden until asked for. */
    val typing: Boolean = false,
    val draft: String = "",
    /** What the words typed so far match, as they are typed. */
    val matches: List<Suggestion> = emptyList(),
    /** Newest first: the last answer must be readable without scrolling anywhere. */
    val exchanges: List<Exchange> = emptyList(),
)

/**
 * One thing the professor has noticed about this student.
 *
 * [detail] is deliberately separate from [text]: the observation is a sentence, the number
 * behind it is evidence. A student who is told "sei debole qui" deserves to see why.
 */
data class Note(val text: String, val detail: String? = null, val warning: Boolean = false)

/**
 * The professor's study: he leads, the student taps, and every answer is one somebody wrote.
 *
 * It used to be a text box. The student typed, retrieval guessed which of the school's answers
 * came closest, and the guess was wrong often enough that the right answers became worthless
 * too — a student who cannot tell which replies to trust ends up trusting none of them. In a
 * school about security that is not a rough edge, it is the whole product failing.
 *
 * So the shape changed rather than the numbers. The professor offers routes; the routes end on
 * verified answers; a wrong answer is structurally impossible rather than merely unlikely. The
 * keyboard is still here, further down, and it no longer pretends: when the words name a
 * lesson he answers, and when they do not he shows what he thinks it might be and lets the
 * student settle it.
 */
@HiltViewModel
class StudyViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val knowledgeBase: KnowledgeBase,
    private val paths: StudyPaths,
    private val answerer: QuestionAnswerer,
    private val tutor: TutorEngine,
    private val facts: SchoolFacts,
) : ViewModel() {

    /**
     * What the professor remembers of this conversation.
     *
     * Lives in the ViewModel and nowhere else: it survives a rotation, and it is gone the
     * moment the student leaves the study. A conversation kept on disk would be one more
     * thing about them that outlives their attention, and the school promised the opposite.
     */
    private val memory = ConversationMemory()

    private val _uiState = MutableStateFlow(StudyUiState())
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private var snapshot: StudentSnapshot? = null
    private var unlockedLevels: Set<Int> = setOf(0)
    private var asked = 0L

    /** Where the student is standing. Null is the top of the study. */
    private var here: String? = null

    /**
     * What has already been answered in this visit.
     *
     * A question the student has just had answered stays on screen as an answer; leaving it
     * in the list of things to ask as well makes the list look like it never moves. It comes
     * back when the conversation is cleared, because the catalogue is not consumed — only
     * this visit through it is.
     */
    private val answered = mutableSetOf<String>()

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
                notes = observationsAbout(snap),
                corpusSize = knowledgeBase.entries.size,
            )
            showHere()
        }
    }

    // --- muoversi nello studio ------------------------------------------------------------

    fun goTo(placeId: String) {
        here = placeId
        showHere()
    }

    /** One step back up, to the top when there is nowhere above. */
    fun goBack() {
        val trail = here?.let { paths.trail(it) }.orEmpty()
        here = trail.dropLast(1).lastOrNull()?.id
        showHere()
    }

    fun goHome() {
        here = null
        showHere()
    }

    private fun showHere() {
        val branch = here?.let { paths.branch(it) }
        val places = (branch?.branches ?: paths.branches).map { it.toPlace() }
        val questions = branch?.items.orEmpty()
            .mapNotNull { item ->
                knowledgeBase.entries.firstOrNull { it.id == item.faq }
                    ?.let { entry -> Suggestion(entry.id, item.text ?: entry.question, entry.level) }
            }

        _uiState.value = _uiState.value.copy(
            trail = paths.trail(here.orEmpty()).map { it.toPlace() },
            line = branch?.line,
            places = places,
            questions = questions.filterNot { it.id in answered },
            exhausted = questions.isNotEmpty() && questions.all { it.id in answered },
        )
    }

    private fun Branch.toPlace() = Place(
        id = id,
        title = title,
        subtitle = subtitle,
        remaining = questionsUnder(this).count { it !in answered },
    )

    private fun questionsUnder(branch: Branch): List<String> =
        branch.items.map { it.faq } + branch.branches.flatMap { questionsUnder(it) }

    // --- la tastiera, che adesso cerca ------------------------------------------------------

    fun toggleTyping() {
        val typing = !_uiState.value.typing
        _uiState.value = _uiState.value.copy(typing = typing, draft = "", matches = emptyList())
    }

    /**
     * Every keystroke narrows the catalogue.
     *
     * This is the honest half of typing: it cannot be wrong, because it shows the questions
     * the school actually has and the student picks. Pressing send still asks the professor,
     * for whoever would rather describe their problem than look for it.
     */
    fun onDraftChange(text: String) {
        _uiState.value = _uiState.value.copy(draft = text, matches = matching(text))
    }

    private fun matching(text: String): List<Suggestion> {
        val words = ItalianText.normalise(text).split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        return knowledgeBase.entries
            .filter { entry ->
                val haystack = ItalianText.normalise(
                    (listOf(entry.question) + entry.aliases).joinToString(" "),
                )
                words.all { haystack.contains(it) }
            }
            .take(SEARCH_RESULTS)
            .map { it.toSuggestion() }
    }

    // --- chiedere ---------------------------------------------------------------------------

    fun ask(question: String = _uiState.value.draft) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        // Cleared straight away, so the field is empty while the answer is being assembled
        // and a second tap cannot send the same question twice.
        _uiState.value = _uiState.value.copy(draft = "", matches = emptyList())

        viewModelScope.launch {
            // Off the main thread: reading the school's records and comparing a question
            // against six hundred meanings is milliseconds of work, but they are milliseconds
            // the screen would spend not drawing.
            //
            // Note the plural. A message can hold more than one question — "come ti chiami e
            // quanti anni hai" is two — and answering only the last one is indistinguishable,
            // from the outside, from not having listened.
            val answers = withContext(Dispatchers.Default) { answerer.askAll(trimmed) }

            val fresh = mutableListOf<Exchange>()
            answers.forEach { answered ->
                asked++
                val exchange = answer(answered.question, answered.result)
                // Remembered one at a time and in order, so that the second question of a
                // message can be about the answer the first one just got.
                memory.remember(
                    Turn(
                        question = answered.question,
                        answer = exchange.answer,
                        entryId = exchange.entryId,
                        topic = exchange.answeredTopic,
                    ),
                )
                exchange.entryId?.let { this@StudyViewModel.answered += it }
                fresh += exchange
            }

            // The list is newest first, so the answers go in reversed: the student reads them
            // top to bottom in the order they asked.
            _uiState.value = _uiState.value.copy(
                exchanges = fresh.reversed() + _uiState.value.exchanges,
            )
            showHere()
        }
    }

    /** A question the student picked: no retrieval involved, so no chance of a wrong answer. */
    fun askSuggestion(suggestion: Suggestion) {
        val entry = knowledgeBase.entries.firstOrNull { it.id == suggestion.id } ?: return
        answered += entry.id
        viewModelScope.launch {
            asked++
            val exchange = answer(entry.question, AnswerResult.Found(entry, 1.0, emptyList()))
            memory.remember(
                Turn(
                    question = entry.question,
                    answer = exchange.answer,
                    entryId = entry.id,
                    topic = entry.question,
                ),
            )
            _uiState.value = _uiState.value.copy(
                exchanges = listOf(exchange) + _uiState.value.exchanges,
                draft = "",
                matches = emptyList(),
            )
            showHere()
        }
    }

    fun clearHistory() {
        memory.clear()
        answered.clear()
        _uiState.value = _uiState.value.copy(exchanges = emptyList())
        refresh()
    }

    private suspend fun answer(question: String, result: AnswerResult): Exchange = when (result) {
        is AnswerResult.Found -> Exchange(
            id = asked,
            question = question,
            // A fact about the student, or about this conversation, is a template: it has
            // to be filled from the records before anyone reads it.
            answer = if (result.entry.kind == EntryKind.FACT || result.entry.kind == EntryKind.MEMORY) {
                facts.fill(result.entry, memory)
            } else {
                result.entry.answer
            },
            understood = true,
            answeredTopic = result.entry.question,
            aheadOfLevel = levelWarningFor(result.entry),
            alternatives = result.alternatives.map { it.toSuggestion() },
            entryId = result.entry.id,
        )

        is AnswerResult.Ambiguous -> Exchange(
            id = asked,
            question = question,
            answer = "Qui posso intendere due cose diverse, e sceglierne una a caso " +
                "sarebbe un modo elegante di risponderti male. Quale delle due?",
            understood = true,
            choosing = true,
            alternatives = result.options.map { it.toSuggestion() },
        )

        // The professor thinks he understood and is not sure enough to say it outright. He
        // shows his guesses instead: one tap, and no chance of a confident wrong answer.
        is AnswerResult.Unsure -> Exchange(
            id = asked,
            question = question,
            answer = "Non sono sicuro di aver capito, e tirare a indovinare qui è il modo " +
                "peggiore di aiutarti. Intendevi una di queste?",
            understood = true,
            choosing = true,
            alternatives = result.options.map { it.toSuggestion() },
        )

        is AnswerResult.NotUnderstood -> Exchange(
            id = asked,
            question = question,
            // Never silence, and never invention — but also never the wrong refusal. The
            // professor has three ways of saying he cannot answer, and the engine says which
            // of the three this is.
            answer = snapshot
                ?.let { tutor.speak(refusalFor(result.reason, question), it).text }
                ?: DEFAULT_UNKNOWN_LINE,
            understood = false,
            // A near miss is worth offering only when the question was about the subject:
            // suggesting a security lesson to somebody asking about carbonara is comedy.
            alternatives = if (result.reason == Miss.NOT_COVERED) {
                listOfNotNull(result.nearest?.toSuggestion())
            } else {
                emptyList()
            },
        )
    }

    private fun refusalFor(reason: Miss, question: String): TutorEvent = when (reason) {
        Miss.NOT_COVERED -> TutorEvent.UnknownQuestion(question)
        Miss.OFF_TOPIC -> TutorEvent.OffTopicQuestion(question)
        Miss.UNPARSEABLE -> TutorEvent.UnclearQuestion(question)
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
        const val SEARCH_RESULTS = 8
        const val DEFAULT_UNKNOWN_LINE =
            "Questa non te la so dire, e preferisco ammetterlo piuttosto che inventare."
    }
}
