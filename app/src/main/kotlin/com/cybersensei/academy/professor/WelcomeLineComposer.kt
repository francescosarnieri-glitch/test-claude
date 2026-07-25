package com.cybersensei.academy.professor

import com.cybersensei.academy.core.common.DayPart
import com.cybersensei.academy.core.common.DeterministicRandom
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.common.pickAvoidingRecent

/**
 * A first, deliberately small taste of how Prof. Hackstein White speaks.
 *
 * This is scaffolding: in Fase 1 it is replaced by the real dialogue engine
 * (`engine:tutor`) with its rule set, student model and episodic memory. What it already
 * demonstrates is the mechanism the whole illusion rests on — templates chosen by context,
 * filled with the student's own data, never repeating the previous line.
 */
class WelcomeLineComposer(
    private val timeProvider: TimeProvider,
) {

    private val recentlyUsed = ArrayDeque<String>()

    fun compose(studentName: String?, openingCount: Int): String {
        val dayPart = timeProvider.dayPart()
        val pool = poolFor(dayPart, studentName == null)
        val random = DeterministicRandom.forSeed(studentName, dayPart.name, openingCount)
        val template = pool.pickAvoidingRecent(random, recentlyUsed)

        remember(template)
        return template.replace(NAME_SLOT, studentName ?: "")
            .replace(GREETING_SLOT, dayPart.italianGreeting)
            .replace(Regex("\\s+([,.!?])"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun remember(template: String) {
        recentlyUsed.addLast(template)
        while (recentlyUsed.size > MEMORY_SIZE) {
            recentlyUsed.removeFirst()
        }
    }

    private fun poolFor(dayPart: DayPart, anonymous: Boolean): List<String> = when {
        anonymous -> FIRST_MEETING
        dayPart == DayPart.DEEP_NIGHT -> DEEP_NIGHT
        else -> STANDARD
    }

    private companion object {
        const val NAME_SLOT = "{nome}"
        const val GREETING_SLOT = "{saluto}"

        /** Two lines are never enough to hide a repetition; four already are. */
        const val MEMORY_SIZE = 3

        val FIRST_MEETING = listOf(
            "Buongiorno. Io sono Hackstein White, e da oggi sono il tuo professore. " +
                "Prima di insegnarti qualcosa, però, voglio sapere chi ho davanti.",
            "Benvenuto. Mi chiamo Hackstein White — White di cognome, e non è un caso: " +
                "qui si impara ad attaccare solo per imparare a difendere.",
            "Eccoti. Sono il Prof. Hackstein White. Non ti insegnerò trucchi: " +
                "ti insegnerò a ragionare. È molto più pericoloso, per chi ti vuole fregare.",
        )

        val STANDARD = listOf(
            "{saluto}, {nome}. Pronto per quattro minuti di scuola vera?",
            "{saluto}, {nome}. Ho preparato la lezione: manchi solo tu.",
            "{saluto}, {nome}. Oggi si torna in aula — la sicurezza non aspetta.",
            "{saluto}, {nome}. Ti avviso: oggi ti chiederò anche il perché delle tue risposte.",
        )

        val DEEP_NIGHT = listOf(
            "{nome}, sono le ore piccole. A quest'ora il tuo cervello fissa i concetti " +
                "molto peggio: facciamo un ripasso leggero invece di roba nuova?",
            "Le ore piccole, {nome}. Studiare di notte è da hacker, dormire è da persone " +
                "che il giorno dopo si ricordano le cose. Dieci minuti e ti lascio andare.",
        )
    }
}
