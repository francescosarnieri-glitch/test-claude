package com.cybersensei.academy.engine.nlu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One question the student can reach by tapping, and the words it is offered in.
 *
 * [text] exists because the way a person describes what happened to them is not the way the
 * answer is titled. "I miei file si sono bloccati e mi chiedono dei soldi" and "Che cos'è un
 * ransomware" are the same answer, and only one of the two is findable by somebody who is
 * frightened. When it is absent the entry's own question is used.
 */
@Serializable
data class PathItem(val faq: String, val text: String? = null)

/**
 * A place in the study the professor can take the student to.
 *
 * Either a list of questions or a list of further branches — never both, because a screen
 * that offers a choice and an answer at the same time makes the student read twice to find
 * out what it wants from them.
 */
@Serializable
data class Branch(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** What the professor says on arriving here. Absent for a branch that needs no preamble. */
    val line: String? = null,
    val items: List<PathItem> = emptyList(),
    val branches: List<Branch> = emptyList(),
    /**
     * Whether this room shows everything it holds regardless of how far the student has got.
     *
     * Declared in the content and not computed, because it is a judgement about people: what
     * somebody might need *right now* — an emergency, a scam to recognise, a belief to correct
     * — must never wait for a lesson. Inherited by everything below it.
     */
    @SerialName("always_open") val alwaysOpen: Boolean = false,
) {
    val isLeaf: Boolean get() = branches.isEmpty()
}

@Serializable
private data class PathContent(val branches: List<Branch>)

/**
 * How the student gets to an answer without typing anything.
 *
 * The study used to be a text box: the student wrote, and retrieval guessed which of the
 * school's answers came closest. It was wrong often enough to be worse than useless — a
 * confident answer to a question nobody asked is more damaging in a security school than no
 * answer at all, because the student has no way of telling the two apart.
 *
 * So the professor leads instead. Every route here ends on an answer somebody wrote and
 * verified, reached by tapping, which makes a wrong answer structurally impossible rather
 * than merely unlikely. The first level is *situations*, not a table of contents: a person
 * who has just clicked a bad link does not know the word "phishing", and asking them to
 * know it before they can be helped is the failure this replaces.
 */
class StudyPaths(val branches: List<Branch>) {

    /** Every branch anywhere in the tree, flattened, parents before children. */
    val all: List<Branch> = buildList {
        fun visit(branch: Branch) {
            add(branch)
            branch.branches.forEach(::visit)
        }
        branches.forEach(::visit)
    }

    fun branch(id: String): Branch? = all.firstOrNull { it.id == id }

    /** The path from the root down to [id], so the screen can show where the student is. */
    fun trail(id: String): List<Branch> {
        fun search(branch: Branch): List<Branch>? {
            if (branch.id == id) return listOf(branch)
            branch.branches.forEach { child -> search(child)?.let { return listOf(branch) + it } }
            return null
        }
        branches.forEach { root -> search(root)?.let { return it } }
        return emptyList()
    }

    val reachableFaqIds: Set<String> get() = all.flatMap { it.items }.map { it.faq }.toSet()

    /**
     * What is wrong with the tree, in the words of whoever has to fix it.
     *
     * An answer nobody can reach is an answer that does not exist, and it is the failure that
     * happens by itself: somebody adds a question to the corpus and forgets to file it.
     */
    fun validate(knowledgeBase: KnowledgeBase): List<String> = buildList {
        val known = knowledgeBase.entries.map { it.id }.toSet()
        (reachableFaqIds - known).sorted()
            .forEach { add("Il percorso porta a una voce che non esiste: '$it'") }
        (known - reachableFaqIds).sorted()
            .forEach { add("La voce '$it' non è raggiungibile da nessun percorso") }

        all.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Ramo duplicato: '$it'") }
        all.filter { it.items.isNotEmpty() && it.branches.isNotEmpty() }
            .forEach { add("Il ramo '${it.id}' offre insieme domande e sottorami: scegline uno") }
        all.filter { it.items.isEmpty() && it.branches.isEmpty() }
            .forEach { add("Il ramo '${it.id}' è vuoto") }
    }

    companion object {
        const val RESOURCE_PATH = "/studio/percorsi.json"

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): StudyPaths = StudyPaths(json.decodeFromString<PathContent>(raw).branches)

        fun fromResources(path: String = RESOURCE_PATH): StudyPaths {
            val stream = StudyPaths::class.java.getResourceAsStream(path)
                ?: error("Percorsi dello studio non trovati: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
