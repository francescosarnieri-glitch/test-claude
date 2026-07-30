package com.cybersensei.academy.core.curriculum

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One page of the book.
 *
 * A page is a unit the *writer* decided, not one a measuring algorithm produced: one piece of
 * reasoning that starts and finishes, written to fit a screen. A paper book has pages because
 * paper runs out; this one has them because somebody chose where the reader gets to breathe —
 * and a break chosen by the author beats one chosen by a font metric every time.
 */
@Serializable
data class PaginaManuale(
    val titolo: String,
    /** Its number in the whole book, running from one. */
    val numero: Int,
    /** Empty while the page is still to be written. */
    val corpo: String = "",
) {
    val scritta: Boolean get() = corpo.isNotBlank()
}

@Serializable
data class CapitoloManuale(
    val id: String,
    /** Zero for the front matter, which is not numbered. */
    val numero: Int,
    val titolo: String,
    /** Which module of the programme this chapter goes with, in plain words. */
    val fonte: String = "",
    val pagine: List<PaginaManuale>,
) {
    val scritte: Int get() = pagine.count { it.scritta }
    val finito: Boolean get() = pagine.isNotEmpty() && scritte == pagine.size

    val daPagina: Int get() = pagine.firstOrNull()?.numero ?: 0
    val aPagina: Int get() = pagine.lastOrNull()?.numero ?: 0

    /** «3 · Phishing e ingegneria sociale», or just the title for the front matter. */
    val etichetta: String get() = if (numero == 0) titolo else "$numero · $titolo"
}

@Serializable
data class ParteManuale(
    val nome: String,
    val titolo: String,
    val occhiello: String = "",
    val capitoli: List<CapitoloManuale>,
)

/**
 * The book.
 *
 * Content like everything else the student reads: JSON outside the code, so a chapter is written
 * by writing, not by shipping a new build of the app.
 */
@Serializable
data class Manuale(
    val titolo: String,
    val sottotitolo: String,
    val parti: List<ParteManuale>,
) {
    val capitoli: List<CapitoloManuale> get() = parti.flatMap { it.capitoli }
    val pagine: List<PaginaManuale> get() = capitoli.flatMap { it.pagine }

    val paginePerCapitolo: Int get() = if (capitoli.isEmpty()) 0 else pagine.size / capitoli.size
    val pagineScritte: Int get() = pagine.count { it.scritta }

    fun capitolo(id: String): CapitoloManuale? = capitoli.firstOrNull { it.id == id }

    fun parteDi(capitoloId: String): ParteManuale? =
        parti.firstOrNull { parte -> parte.capitoli.any { it.id == capitoloId } }

    /** Where a page number lands, for reopening the book at the bookmark. */
    fun capitoloDellaPagina(numero: Int): CapitoloManuale? =
        capitoli.firstOrNull { numero in it.daPagina..it.aPagina }

    fun validate(): List<String> = buildList {
        if (parti.isEmpty()) add("Il manuale non ha parti")

        capitoli.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Capitolo duplicato: '$it'") }

        capitoli.forEach { capitolo ->
            if (capitolo.pagine.isEmpty()) add("Il capitolo '${capitolo.id}' non ha pagine")
            capitolo.pagine.filter { it.titolo.isBlank() }
                .forEach { add("Pagina senza titolo nel capitolo '${capitolo.id}'") }
        }

        // Un libro ha una numerazione sola, che corre dall'inizio alla fine: due pagine con lo
        // stesso numero, o un salto, rendono inutile il segnalibro e bugiardo l'indice.
        pagine.forEachIndexed { index, pagina ->
            if (pagina.numero != index + 1) {
                add("La pagina «${pagina.titolo}» ha il numero ${pagina.numero} invece di ${index + 1}")
            }
        }

        // Una pagina troppo lunga costringe a scorrere, ed e' esattamente la cosa che le pagine
        // esistono per evitare. Il limite e' generoso — un libro e' piu' denso di una lezione —
        // ma c'e'.
        pagine.filter { it.corpo.length > MASSIMO_PAGINA }
            .forEach { add("La pagina «${it.titolo}» e' lunga ${it.corpo.length}: non ci sta in una schermata") }
    }

    companion object {
        const val RESOURCE_PATH = "/manuale/manuale.json"

        /** Quanto puo' essere lunga una pagina prima di diventare uno scorrimento. */
        const val MASSIMO_PAGINA = 1600

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): Manuale = json.decodeFromString(raw)

        fun fromResources(path: String = RESOURCE_PATH): Manuale {
            val stream = Manuale::class.java.getResourceAsStream(path)
                ?: error("Manuale non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
