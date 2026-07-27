package com.cybersensei.academy.engine.labs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How the connection is set up while the packet travels. */
@Serializable
data class Setup(
    val https: Boolean,
    val vpn: Boolean,
)

/** What one observer along the path can see, under one setup. */
@Serializable
data class Sighting(
    /** Matches the four combinations: "http", "http_vpn", "https", "https_vpn". */
    val setup: String,
    /** What this hop can read. Empty means it sees nothing beyond the fact of a connection. */
    val sees: List<String> = emptyList(),
    val comment: String,
)

@Serializable
data class Hop(
    val id: String,
    val name: String,
    val role: String,
    val sightings: List<Sighting>,
) {
    fun under(setup: Setup): Sighting? = sightings.firstOrNull { it.setup == setup.key() }
}

@Serializable
data class Journey(
    val title: String,
    val briefing: String,
    @SerialName("payload") val whatIsSent: String,
    val hops: List<Hop>,
    val debriefing: String,
) {
    fun validate(): List<String> = buildList {
        hops.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("Nodo duplicato: '$it'") }
        if (hops.size < MINIMUM_HOPS) add("Solo ${hops.size} nodi: il percorso non si vede")
        hops.forEach { hop ->
            ALL_SETUPS.forEach { setup ->
                if (hop.under(setup) == null) {
                    add("Il nodo '${hop.id}' non dice cosa vede con ${setup.key()}")
                }
            }
        }
        // The point of the exercise is that turning HTTPS on changes what someone sees. If it
        // changed nothing anywhere, the lab would be teaching the opposite of the truth.
        val httpsChangesSomething = hops.any { hop ->
            hop.under(Setup(https = false, vpn = false))?.sees !=
                hop.under(Setup(https = true, vpn = false))?.sees
        }
        if (!httpsChangesSomething) add("Attivare HTTPS non cambia niente da nessuna parte")
    }

    companion object {
        const val RESOURCE_PATH = "/laboratori/traccia.json"
        const val MINIMUM_HOPS = 4

        val ALL_SETUPS = listOf(
            Setup(https = false, vpn = false),
            Setup(https = false, vpn = true),
            Setup(https = true, vpn = false),
            Setup(https = true, vpn = true),
        )

        private val json = Json { ignoreUnknownKeys = false }

        fun parse(raw: String): Journey = json.decodeFromString(raw)

        fun fromResources(path: String = RESOURCE_PATH): Journey {
            val stream = Journey::class.java.getResourceAsStream(path)
                ?: error("Percorso non trovato: $path")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}

fun Setup.key(): String = when {
    https && vpn -> "https_vpn"
    https -> "https"
    vpn -> "http_vpn"
    else -> "http"
}
