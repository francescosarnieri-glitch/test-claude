package com.cybersensei.academy.ui.path

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.core.ui.component.SectionHeader
import com.cybersensei.academy.core.ui.component.SenseiCard
import com.cybersensei.academy.core.ui.theme.SenseiTheme
import com.cybersensei.academy.ui.labs.Lab

@Composable
fun PathScreen(
    onStartLesson: (String) -> Unit,
    onStartQuiz: (String) -> Unit,
    onStartExam: (Int) -> Unit,
    onOpenLab: (String) -> Unit,
    onStartCapstone: (String) -> Unit,
    viewModel: PathViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    // Cosa e' aperto in questo momento. Sopravvive alla rotazione e al giro sulle altre
    // schede: chi apre «Lezioni», va a fare una lezione e torna, deve ritrovarlo aperto.
    val aperti = rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }

    // Il livello su cui si e' adesso parte aperto: una schermata tutta chiusa al primo
    // avvio non e' ordinata, e' muta.
    val corrente = uiState.levels.firstOrNull { it.available && !it.passed }?.order
    LaunchedEffect(corrente) {
        if (corrente != null && aperti.value.isEmpty()) aperti.value = setOf(chiave(corrente))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Il percorso",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        uiState.levels.forEach { level ->
            LevelBlock(
                level = level,
                aperti = aperti,
                onStartLesson = onStartLesson,
                onStartQuiz = onStartQuiz,
                onStartExam = onStartExam,
                onOpenLab = onOpenLab,
                onStartCapstone = onStartCapstone,
            )
        }

        // The capstone sits after everything else, where it belongs, and is offered rather
        // than locked: telling someone what it assumes is more useful than refusing entry.
        SectionHeader(text = "Prova finale")
        SenseiCard(
            modifier = Modifier.clickable { onStartCapstone(FINAL_CASE_ID) },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "🚨", style = MaterialTheme.typography.headlineSmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "L'Incidente",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Una notte intera, decisione per decisione. Non si risponde " +
                            "a domande: si decide, e ogni scelta cambia il seguito. " +
                            "Alla fine il professore rivede la notte con te.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}


/**
 * One level, and the three doors inside it.
 *
 * The path used to be one column that never ended: eight module cards, three case cards, two
 * labs and an exam, four levels deep, all unrolled at once. Everything was reachable and
 * nothing was findable, which is the particular kind of disorder that looks like completeness.
 *
 * So each level opens into three named sections and nothing else. The rules underneath have
 * not moved by a millimetre — same padlocks, same order, same reasons — because this is a
 * change to how the programme is *shown*, and mixing the two would make it impossible to tell
 * a display bug from a rule bug the next time something looks wrong.
 */
@Composable
private fun LevelBlock(
    level: LevelRow,
    aperti: MutableState<Set<String>>,
    onStartLesson: (String) -> Unit,
    onStartQuiz: (String) -> Unit,
    onStartExam: (Int) -> Unit,
    onOpenLab: (String) -> Unit,
    onStartCapstone: (String) -> Unit,
) {
    if (!level.available) {
        LockedLevelCard(level)
        return
    }

    val casi = level.cases.filterNot { it.id == FINAL_CASE_ID }
    val laboratori = Lab.forLevel(level.order)
    val lezioniFatte = level.modules.sumOf { modulo -> modulo.lessons.count { it.done } }
    val lezioniTotali = level.modules.sumOf { it.lessons.size }

    fun apri(chiave: String) {
        aperti.value = if (chiave in aperti.value) aperti.value - chiave else aperti.value + chiave
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val chiaveLivello = chiave(level.order)
        val livelloAperto = chiaveLivello in aperti.value

        FoldRow(
            icon = if (level.passed) "✓" else "●",
            iconColour = MaterialTheme.colorScheme.primary,
            title = level.label,
            subtitle = level.subtitle,
            detail = "$lezioniFatte/$lezioniTotali lezioni",
            open = livelloAperto,
            emphasis = true,
            onClick = { apri(chiaveLivello) },
        )

        if (!livelloAperto) return@Column

        val chiaveLezioni = chiave(level.order, "lezioni")
        FoldRow(
            icon = "📖",
            title = "Lezioni",
            detail = if (lezioniFatte == lezioniTotali) "tutte fatte" else "$lezioniFatte su $lezioniTotali",
            open = chiaveLezioni in aperti.value,
            onClick = { apri(chiaveLezioni) },
        )
        if (chiaveLezioni in aperti.value) {
            // Un modulo per porta. «Le fondamenta» ne ha otto: aprirli tutti insieme
            // rifarebbe la colonna infinita un piano piu' sotto, che e' precisamente il
            // difetto che questa schermata e' stata piegata per togliere.
            level.modules.forEach { module ->
                val chiaveModulo = chiave(level.order, "modulo/${module.id}")
                val fatte = module.lessons.count { it.done }
                FoldRow(
                    icon = when {
                        !module.unlocked -> "🔒"
                        fatte == module.lessons.size -> "✓"
                        else -> "●"
                    },
                    iconColour = if (fatte == module.lessons.size) {
                        SenseiTheme.colors.correct
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    title = module.title,
                    detail = "$fatte/${module.lessons.size}",
                    locked = !module.unlocked,
                    lockedReason = "Si apre quando avrai finito il modulo precedente.",
                    open = chiaveModulo in aperti.value,
                    onClick = { apri(chiaveModulo) },
                )
                if (chiaveModulo in aperti.value) {
                    ModuleCard(module, onStartLesson, onStartQuiz)
                }
            }
        }

        val chiaveEsame = chiave(level.order, "esame")
        FoldRow(
            icon = if (level.examPassed) "🎓" else "📋",
            title = "Esame",
            detail = when {
                level.examPassed -> "superato"
                level.lessonsFinished -> "da fare"
                else -> null
            },
            locked = !level.lessonsFinished,
            lockedReason = "Si apre quando avrai finito tutte le lezioni del livello.",
            open = chiaveEsame in aperti.value,
            onClick = { apri(chiaveEsame) },
        )
        if (chiaveEsame in aperti.value) ExamCard(level, onStartExam)

        if (casi.isNotEmpty()) {
            val chiaveCasi = chiave(level.order, "casi")
            val apribili = casi.count { it.unlocked }
            FoldRow(
                icon = "🧩",
                title = "Casi da risolvere",
                subtitle = "Storie in cui non si risponde: si decide.",
                detail = if (apribili > 0) "$apribili su ${casi.size}" else null,
                locked = apribili == 0,
                lockedReason = "Si aprono studiando i moduli da cui sono fatti.",
                open = chiaveCasi in aperti.value,
                onClick = { apri(chiaveCasi) },
            )
            if (chiaveCasi in aperti.value) {
                casi.forEach { caso -> CaseCard(caso, onStartCapstone) }
            }
        }

        if (laboratori.isNotEmpty()) {
            val chiaveLab = chiave(level.order, "laboratori")
            FoldRow(
                icon = "🔬",
                title = "Laboratori",
                detail = "${laboratori.size}",
                open = chiaveLab in aperti.value,
                onClick = { apri(chiaveLab) },
            )
            if (chiaveLab in aperti.value) {
                laboratori.forEach { lab -> LabCard(lab, onOpenLab) }
            }
        }
    }
}

/**
 * A door: tap to open, tap to close, and it says what is behind before you open it.
 *
 * The count on the right is the reason a closed door is not a step backwards. A row that only
 * said «Lezioni» would hide the one thing worth knowing at a glance — how much is left — and
 * folding a page that way tidies it by making it less useful.
 */
@Composable
private fun FoldRow(
    icon: String,
    title: String,
    open: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    detail: String? = null,
    locked: Boolean = false,
    lockedReason: String? = null,
    emphasis: Boolean = false,
    iconColour: Color? = null,
) {
    val stato = when {
        locked -> "Chiuso"
        open -> "Aperto"
        else -> "Chiuso, tocca per aprire"
    }
    Surface(
        onClick = onClick,
        enabled = !locked,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title. $stato. ${detail.orEmpty()}" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (locked) "🔒" else icon,
                style = MaterialTheme.typography.titleMedium,
                color = iconColour ?: MaterialTheme.colorScheme.onSurface,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = if (emphasis) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = if (locked) {
                        SenseiTheme.colors.lockedContent
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (locked) {
                            SenseiTheme.colors.lockedContent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (locked) {
                    lockedReason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = SenseiTheme.colors.lockedContent,
                        )
                    }
                }
            }
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // La freccia non e' solo decorazione: senza, «aperto» e «chiuso» si
            // distinguerebbero soltanto da quello che c'e' sotto, che a volte e' fuori schermo.
            if (!locked) {
                Text(
                    text = if (open) "▾" else "▸",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** La chiave con cui una porta si ricorda di essere rimasta aperta. */
private fun chiave(level: Int, sezione: String? = null): String =
    if (sezione == null) "livello-$level" else "livello-$level/$sezione"

/**
 * A level the student has not earned yet.
 *
 * Given the same card as everything else on this screen on purpose. Drawn as a bare row on the
 * background it read as a piece of the page that had failed to load, rather than as a door
 * that is shut — and «chiuso» has to look deliberate, otherwise the padlock stops meaning
 * anything anywhere else.
 */
@Composable
private fun LockedLevelCard(level: LevelRow) {
    SenseiCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "🔒", style = MaterialTheme.typography.titleLarge)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = level.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = SenseiTheme.colors.lockedContent,
                )
                Text(
                    text = level.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SenseiTheme.colors.lockedContent,
                )
                level.lockedReason?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SenseiTheme.colors.lockedContent,
                    )
                }
                Text(
                    text = "${level.modules.size} moduli · " +
                        "${level.modules.sumOf { it.lessons.size }} lezioni · " +
                        "${level.cases.size} casi",
                    style = MaterialTheme.typography.labelSmall,
                    color = SenseiTheme.colors.lockedContent,
                )
            }
        }
    }
}

/**
 * A case: a short branching story, offered inside the level whose material it is built from.
 *
 * It is not a quiz with a plot. Nothing here is scored on knowing a fact — every choice is a
 * decision somebody actually has to take, and the debriefing explains all of them, including
 * the ones taken well. That is why the cases can be spread through the programme instead of
 * waiting at the end: a case only ever asks about material the student has already read.
 */
@Composable
private fun CaseCard(caso: CaseRow, onOpen: (String) -> Unit) {
    SenseiCard(
        modifier = if (caso.unlocked) Modifier.clickable { onOpen(caso.id) } else Modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when {
                    !caso.unlocked -> "\uD83D\uDD12"
                    caso.played -> "\u2713"
                    else -> "\uD83E\uDDE9"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Caso — ${caso.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (caso.unlocked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        SenseiTheme.colors.lockedContent
                    },
                )
                Text(
                    text = caso.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (caso.unlocked) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        SenseiTheme.colors.lockedContent
                    },
                )
                Text(
                    text = when {
                        !caso.unlocked && caso.opensWith.isEmpty() ->
                            "Si apre più avanti nel programma."
                        !caso.unlocked ->
                            "Si apre quando avrai letto ${caso.opensWith.joinToString(", ")}."
                        caso.played ->
                            "Già affrontato. Puoi rigiocarlo: le decisioni cambiano il finale."
                        else ->
                            "${caso.minutes} minuti, si decide e basta. Non ci sono domande."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SenseiTheme.colors.lockedContent,
                )
            }
        }
    }
}

/**
 * One module of the programme, opened one step at a time.
 *
 * A module still behind the student's own shows its name and what opens it, and nothing else:
 * listing four lessons that cannot be tapped would be saying no four times over, and hiding
 * the module entirely would take away the map. It is the same shape the levels above use, for
 * the same reason.
 */
@Composable
private fun ModuleCard(
    module: ModuleRow,
    onStartLesson: (String) -> Unit,
    onStartQuiz: (String) -> Unit,
) {
    SenseiCard {
        // Niente titolo: sta gia' sulla porta che si e' appena aperta, e ripeterlo due
        // righe piu' sotto fa sembrare che siano due cose diverse.
        Text(
            text = module.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        module.lessons.forEach { lesson -> LessonRowView(lesson, onStartLesson) }

        // L'interrogazione chiude il modulo: e' l'ultima riga perche' e' l'ultima cosa da fare.
        val label = "Interrogazione — ${module.questionCount} domande"
        if (module.quizUnlocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStartQuiz(module.id) }
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = "$label. ${module.masteryPercent}%." },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${module.masteryPercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = "Chiusa. $label." },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "🔒", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SenseiTheme.colors.lockedContent,
                    modifier = Modifier.weight(1f),
                )
            }
            LockedLine(text = "Si apre quando avrai letto le lezioni di questo modulo.")
        }
    }
}

/**
 * One lesson.
 *
 * Three states, each with its own glyph as well as its own colour, because a padlock has to
 * survive greyscale and a screen reader: read, next up, closed.
 */
@Composable
private fun LessonRowView(lesson: LessonRow, onStartLesson: (String) -> Unit) {
    val state = when {
        lesson.done -> "Fatta"
        lesson.read -> "Letta, manca l'interrogazione"
        lesson.unlocked -> "Da fare"
        else -> "Chiusa"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (lesson.unlocked) {
                    Modifier.clickable { onStartLesson(lesson.id) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 6.dp)
            .semantics {
                contentDescription = "$state. ${lesson.title}. ${lesson.minutes} minuti."
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = when {
                lesson.done -> "✓"
                lesson.read -> "◑"
                lesson.unlocked -> "▸"
                else -> "🔒"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                lesson.done -> SenseiTheme.colors.correct
                lesson.read -> SenseiTheme.colors.warning
                lesson.unlocked -> MaterialTheme.colorScheme.primary
                else -> SenseiTheme.colors.lockedContent
            },
        )
        // Il titolo tiene tutta la larghezza e la nota gli sta sotto. Messa di fianco
        // rubava spazio al titolo, che andava a capo tre volte: una riga che si spezza
        // quando compare un avviso fa sembrare rotto lo schermo, non attento.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = lesson.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (lesson.unlocked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    SenseiTheme.colors.lockedContent
                },
            )
            if (lesson.read) {
                Text(
                    text = "manca l'interrogazione",
                    style = MaterialTheme.typography.labelSmall,
                    color = SenseiTheme.colors.warning,
                )
            }
        }
        Text(
            text = "${lesson.minutes}′",
            style = MaterialTheme.typography.bodyMedium,
            color = if (lesson.unlocked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                SenseiTheme.colors.lockedContent
            },
        )
    }
}

/** Why something is closed, said in the same words everywhere in the app. */
@Composable
private fun LockedLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = SenseiTheme.colors.lockedContent,
    )
}

/**
 * The exam for a level.
 *
 * Offered only once every lesson is done, because an exam sat before the material has been
 * read measures nothing and teaches the student that the exam is noise. Passing it is shown
 * separately from the level being unlocked: the gate opens on accumulated mastery, the exam
 * is one sitting that covers everything at once, and conflating them would let a good
 * average stand in for having been examined.
 */
@Composable
private fun ExamCard(level: LevelRow, onStartExam: (Int) -> Unit) {
    SenseiCard(
        modifier = if (level.lessonsFinished) {
            Modifier.clickable { onStartExam(level.order) }
        } else {
            Modifier
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when {
                    level.examPassed -> "🎓"
                    level.lessonsFinished -> "📋"
                    else -> "🔒"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Esame — ${level.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (level.lessonsFinished) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        SenseiTheme.colors.lockedContent
                    },
                )
                Text(
                    text = when {
                        level.examPassed ->
                            "Superato. Puoi rifarlo quando vuoi: le domande cambiano."
                        !level.lessonsFinished ->
                            "Si apre quando avrai finito tutte le lezioni del livello."
                        else ->
                            "Una domanda per ogni competenza del livello, tutte in fila. " +
                                "Serve il 75% e nessun modulo sotto il 60% — la media da sola non basta."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (level.lessonsFinished) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        SenseiTheme.colors.lockedContent
                    },
                )
            }
        }
    }
}

/** A workshop, offered next to the level whose material it exercises. */
@Composable
private fun LabCard(lab: Lab, onOpenLab: (String) -> Unit) {
    SenseiCard(modifier = Modifier.clickable { onOpenLab(lab.id) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = lab.icon, style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Laboratorio — ${lab.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = lab.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The case the diploma asks for, kept at the bottom of the path rather than inside a level. */
private const val FINAL_CASE_ID = SchoolRepository.FINAL_CASE_ID
