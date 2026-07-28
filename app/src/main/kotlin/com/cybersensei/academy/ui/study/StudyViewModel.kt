package com.cybersensei.academy.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.nlu.Branch
import com.cybersensei.academy.engine.nlu.ConversationMemory
import com.cybersensei.academy.engine.nlu.EntryKind
import com.cybersensei.academy.engine.nlu.FaqEntry
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.StudyAvailability
import com.cybersensei.academy.engine.nlu.StudyPaths
import com.cybersensei.academy.engine.nlu.Turn
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
    /** Which entry answered, for the professor's own memory of what he said. */
    val entryId: String? = null,
)

/** A question the student can ask with one tap. */
data class Suggestion(val id: String, val text: String, val level: Int)

/**
 * One place the professor can take the student, as the screen needs it.
 *
 * A place only ever reaches the screen when [open] is above zero. A room that offers nothing
 * but a count of what is still locked is a door that opens onto a wall: the student walks in,
 * reads «17 ancora da sbloccare» and walks back out, and does that ten times before finding
 * the four rooms that had something. What is locked is said once, at the top, in one list.
 */
data class Place(
    val id: String,
    val title: String,
    val subtitle: String?,
    /** How many questions down there the student can ask at all. Zero means: do not show me. */
    val open: Int,
    /** How many of those are still unasked in this visit, so a picked-clean room says so. */
    val remaining: Int,
)

/**
 * A slice of what is still closed, named by the part of the programme that opens it.
 *
 * Gathered in one place instead of being sprinkled through every room: the tree is filed by
 * subject, but what is open cuts across it, so a per-room count made the student reconstruct
 * the total by walking the whole tree.
 */
data class LockedGroup(val title: String, val detail: String, val count: Int)

data class StudyUiState(
    val openingLine: String = "",
    val notes: List<Note> = emptyList(),
    val corpusSize: Int = 0,
    /**
     * How much of the study is open right now.
     *
     * Stated out loud because a growing catalogue that nobody notices growing is just a
     * catalogue: the number is the difference between "l'app ha poche domande" and "ne ho
     * aperte 127 e le altre arrivano studiando".
     */
    val openQuestions: Int = 0,
    /**
     * What has opened since the student was last here, said out loud.
     *
     * An opening nobody notices is not a reward, e' solo un elenco che si allunga: questa
     * riga e' la differenza fra le due cose.
     */
    val justOpened: String? = null,
    /** Where the student is, root first. Empty at the top. */
    val trail: List<Place> = emptyList(),
    /** What the professor says on arriving here. */
    val line: String? = null,
    /** Further places to go from here. Only ones that hold something the student can ask. */
    val places: List<Place> = emptyList(),
    /** Questions to ask here, the ones already asked removed. */
    val questions: List<Suggestion> = emptyList(),
    /** True when this branch had questions and the student has asked them all. */
    val exhausted: Boolean = false,
    /**
     * How many questions in this room are still closed.
     *
     * A number and nothing else: what is closed is not listed and cannot be touched, because
     * a question you are not ready for is not an offer — showing it would only be a way of
     * saying no twice. Zero at the top, where [locked] says the same thing better.
     */
    val ahead: Int = 0,
    /** Which interrogations would open them, so the number turns into a next step. */
    val opensWith: List<String> = emptyList(),
    /**
     * Everything still closed, at the top of the study, in one list ordered like the syllabus.
     *
     * The counterpart of [places]: those two lists together are the whole catalogue, split
     * once into «what you can ask me» and «what opens as you study», so the student never has
     * to open a room to find out which of the two it belongs to.
     */
    val locked: List<LockedGroup> = emptyList(),
    /** How many questions the whole of [locked] adds up to. */
    val lockedTotal: Int = 0,
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
 * So the shape changed rather than the numbers, and then the text box went altogether. It was
 * kept for one turn as a search over the school's own questions, and one turn was enough to
 * see it earn nothing: everything it could find was already a tap away, and what it could not
 * find it answered badly. Nothing here interprets anything now — the student picks, and the
 * answer that arrives is the one that was written for that question.
 */
@HiltViewModel
class StudyViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val curriculum: Curriculum,
    private val knowledgeBase: KnowledgeBase,
    private val paths: StudyPaths,
    private val availability: StudyAvailability,
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

    /**
     * The competences the student has already been examined on, which is what decides how
     * much of the study is open. Read once per refresh; it changes only when an interrogation
     * is answered, and the study is re-read every time it comes back to the front.
     */
    private var attemptedSkills: Set<String> = emptySet()

    /**
     * Announced once per opening, not once per redraw.
     *
     * The study refreshes every time it comes back to the front, and re-announcing the same
     * six questions each time would turn a reward into a nagging.
     */
    private var announced = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snap = repository.snapshot()
            snapshot = snap
            unlockedLevels = repository.unlockedLevels()
            attemptedSkills = repository.allMastery().filter { it.attempts > 0 }
                .map { it.skillId }.toSet()

            _uiState.value = _uiState.value.copy(
                openingLine = tutor.speak(TutorEvent.StudyOpened, snap).text,
                notes = observationsAbout(snap),
                corpusSize = knowledgeBase.entries.size,
                openQuestions = openQuestionCount(),
                justOpened = announceNewlyOpened(),
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

    /**
     * What has opened since the last visit, and the professor's line about it.
     *
     * Everything open is written down as seen, including on the very first visit — so a new
     * student is not greeted by "ti ho aperto centoquindici domande", which would be true and
     * useless. From then on only the difference is announced.
     */
    private suspend fun announceNewlyOpened(): String? {
        val open = openQuestions().map { it.id }.toSet()
        val alreadySeen = repository.seenQuestions()
        val nuove = open - alreadySeen
        repository.markQuestionsSeen(nuove)

        // Il primo giro registra e tace: non c'e' un "prima" con cui confrontare.
        if (alreadySeen.isEmpty() || nuove.isEmpty() || announced) return null
        announced = true

        val moduli = availability.skillsThatOpen(knowledgeBase.entries.filter { it.id in nuove })
            .mapNotNull { skill -> curriculum.modules.firstOrNull { skill in it.skills }?.title }
            .distinct()
        val dove = when {
            moduli.isEmpty() -> ""
            moduli.size == 1 -> " Sono quelle di ${moduli.first()}."
            else -> " Sono quelle di ${moduli.dropLast(1).joinToString(", ")} e ${moduli.last()}."
        }
        return if (nuove.size == 1) {
            "Da quando non ci vediamo si è aperta una domanda nuova.$dove"
        } else {
            "Da quando non ci vediamo si sono aperte ${nuove.size} domande nuove.$dove"
        }
    }

    /** Every question open to the student right now, counted once even if filed twice. */
    private fun openQuestions(): List<FaqEntry> =
        entriesUnder(null).filter { availability.isOpen(it, attemptedSkills) }

    /** Every question open to the student right now, counted once even if filed twice. */
    private fun openQuestionCount(): Int = openQuestions().size

    /**
     * What the screen shows from where the student is standing.
     *
     * Two rules do all the tidying. A room that holds nothing open is not drawn — the student
     * used to walk into it, read a count of things he could not have, and walk back out. And
     * a room whose few open questions are scattered across sub-rooms is flattened onto one
     * screen: making somebody tap three times to reach two answers is searching, not studying.
     */
    private fun showHere() {
        val branch = here?.let { paths.branch(it) }
        val children = branch?.branches ?: paths.branches

        val ownItems = branch?.items.orEmpty().mapNotNull { item ->
            knowledgeBase.entries.firstOrNull { it.id == item.faq }
                ?.let { entry -> entry to (item.text ?: entry.question) }
        }
        val rooms = children.map { it.toPlace() }.filter { it.open > 0 }
        val flattened = if (ownItems.isEmpty()) {
            children.flatMap { openItemsUnder(it) }
                .distinctBy { it.first.id }
                .takeIf { it.isNotEmpty() && it.size <= FLATTEN_LIMIT }
                .orEmpty()
        } else {
            emptyList()
        }

        val open = ownItems.filter { availability.isOpen(it.first, attemptedSkills) } + flattened
        val questions = open.map { (entry, testo) -> Suggestion(entry.id, testo, entry.level) }
        val closed = entriesUnder(branch).filterNot { availability.isOpen(it, attemptedSkills) }
        val atTheTop = branch == null

        _uiState.value = _uiState.value.copy(
            trail = paths.trail(here.orEmpty()).map { it.toPlace() },
            line = branch?.line,
            places = if (flattened.isNotEmpty()) emptyList() else rooms,
            questions = questions.filterNot { it.id in answered },
            exhausted = questions.isNotEmpty() && questions.all { it.id in answered },
            ahead = if (atTheTop) 0 else closed.size,
            opensWith = if (atTheTop) emptyList() else modulesThatOpen(closed),
            locked = if (atTheTop) lockedGroups(closed) else emptyList(),
            lockedTotal = if (atTheTop) closed.size else 0,
        )
    }

    /**
     * What is still closed, cut by level rather than by module.
     *
     * Twenty-six module rows would be an inventory; four are a map. The modules are still
     * named, in the line under each level, because a number without a next step is only a
     * reminder of what you do not have.
     */
    private fun lockedGroups(closed: List<FaqEntry>): List<LockedGroup> {
        val byLevel = closed.groupBy { it.level }
        return curriculum.levels.mapNotNull { level ->
            val entries = byLevel[level.level].orEmpty()
            if (entries.isEmpty()) return@mapNotNull null
            val modules = modulesThatOpen(entries)
            val named = modules.take(3).joinToString(", ")
            val rest = modules.size - 3
            LockedGroup(
                title = level.title,
                detail = when {
                    modules.isEmpty() -> "Si aprono man mano che studi."
                    rest > 0 -> "Si aprono con le interrogazioni di $named e altri $rest moduli."
                    else -> "Si aprono con le interrogazioni di $named."
                },
                count = entries.size,
            )
        }
    }

    /** The modules whose interrogation would open [entries], named as the student sees them. */
    private fun modulesThatOpen(entries: List<FaqEntry>): List<String> =
        availability.skillsThatOpen(entries)
            .mapNotNull { skill -> curriculum.modules.firstOrNull { skill in it.skills }?.title }
            .distinct()

    private fun Branch.toPlace(): Place {
        val open = entriesUnder(this).filter { availability.isOpen(it, attemptedSkills) }
        return Place(
            id = id,
            title = title,
            subtitle = subtitle,
            open = open.size,
            remaining = open.count { it.id !in answered },
        )
    }

    /** Every question filed under [branch], or under the whole study when it is null. */
    private fun entriesUnder(branch: Branch?): List<FaqEntry> =
        (branch?.let { listOf(it) } ?: paths.branches)
            .flatMap { room -> entriesIn(room) }
            .distinctBy { it.id }

    private fun entriesIn(branch: Branch): List<FaqEntry> =
        branch.items.mapNotNull { item -> knowledgeBase.entries.firstOrNull { it.id == item.faq } } +
            branch.branches.flatMap { entriesIn(it) }

    /** The open questions under [branch], each with the words it is offered in. */
    private fun openItemsUnder(branch: Branch): List<Pair<FaqEntry, String>> =
        branch.items.mapNotNull { item ->
            knowledgeBase.entries.firstOrNull { it.id == item.faq }
                ?.takeIf { availability.isOpen(it, attemptedSkills) }
                ?.let { entry -> entry to (item.text ?: entry.question) }
        } + branch.branches.flatMap { openItemsUnder(it) }

    // --- chiedere ---------------------------------------------------------------------

    /** A question the student picked: no retrieval involved, so no chance of a wrong answer. */
    fun askSuggestion(suggestion: Suggestion) {
        val entry = knowledgeBase.entries.firstOrNull { it.id == suggestion.id } ?: return
        answered += entry.id
        viewModelScope.launch {
            asked++
            val exchange = answer(entry)
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

    /**
     * The answer to a question the student picked.
     *
     * There is no matching left to do: the tap named the entry, so the only work is filling
     * in the templates that speak about this particular student.
     */
    private suspend fun answer(entry: FaqEntry): Exchange = Exchange(
        id = asked,
        question = entry.question,
        // A fact about the student is a template: it has to be filled from the records
        // before anyone reads it.
        answer = if (entry.kind == EntryKind.FACT || entry.kind == EntryKind.MEMORY) {
            facts.fill(entry, memory)
        } else {
            entry.answer
        },
        understood = true,
        answeredTopic = entry.question,
        aheadOfLevel = levelWarningFor(entry),
        entryId = entry.id,
    )

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

    private companion object {
        /**
         * Above this many open questions a room keeps its sub-rooms; below it, they collapse.
         *
         * Eight is what fits on a phone without scrolling past the professor's line: enough
         * that no branch worth splitting gets flattened, few enough that nobody is asked to
         * navigate towards a handful of answers.
         */
        const val FLATTEN_LIMIT = 8
    }
}
