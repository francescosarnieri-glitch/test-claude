package com.cybersensei.academy.engine.labs

import java.time.Instant
import java.util.Base64

/** What a token turned out to be, once opened. */
sealed interface TokenReading {
    data class Opened(
        val header: String,
        val payload: String,
        val signaturePresent: Boolean,
        val algorithm: String?,
        val issuedAt: Instant?,
        val expiresAt: Instant?,
        /** Everything that stands out about this particular token. */
        val observations: List<TokenObservation>,
    ) : TokenReading

    data class NotTheRightShape(val reason: String) : TokenReading
}

data class TokenObservation(val title: String, val detail: String, val alarming: Boolean)

/**
 * Opens a signed token and shows what is inside it.
 *
 * There is nothing offensive here and there could not be: opening one requires no key,
 * because a signed token is not encrypted — which is precisely the misconception the lab
 * exists to break. Every student who has been told "il token è cifrato" can paste one in and
 * read their own email address out of it.
 *
 * The signature is deliberately *not* verified. Verifying needs the server's key, and
 * pretending otherwise would teach that a token can be trusted by looking at it.
 */
object TokenAnatomy {

    fun read(raw: String, now: Instant): TokenReading {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return TokenReading.NotTheRightShape("Non hai incollato niente.")

        val parts = trimmed.split(".")
        if (parts.size < 2) {
            return TokenReading.NotTheRightShape(
                "Un token di questo tipo ha almeno due parti separate da un punto. " +
                    "Questa ne ha ${parts.size}.",
            )
        }

        val header = decode(parts[0]) ?: return TokenReading.NotTheRightShape(
            "La prima parte non è leggibile: non è codificata come dovrebbe.",
        )
        val payload = decode(parts[1]) ?: return TokenReading.NotTheRightShape(
            "La seconda parte non è leggibile: non è codificata come dovrebbe.",
        )

        val algorithm = stringField(header, "alg")
        val issuedAt = numberField(payload, "iat")?.let(Instant::ofEpochSecond)
        val expiresAt = numberField(payload, "exp")?.let(Instant::ofEpochSecond)

        return TokenReading.Opened(
            header = header,
            payload = payload,
            signaturePresent = parts.size >= 3 && parts[2].isNotBlank(),
            algorithm = algorithm,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            observations = observationsFor(payload, algorithm, expiresAt, now),
        )
    }

    private fun observationsFor(
        payload: String,
        algorithm: String?,
        expiresAt: Instant?,
        now: Instant,
    ): List<TokenObservation> = buildList {
        add(
            TokenObservation(
                title = "L'hai letto senza nessuna chiave",
                detail = "Firmato non vuol dire cifrato. La firma dimostra chi lo ha emesso e " +
                    "che non è stato alterato; il contenuto è in chiaro per chiunque lo abbia " +
                    "in mano. Nei token non si mettono segreti.",
                alarming = false,
            ),
        )

        if (algorithm.equals("none", ignoreCase = true)) {
            add(
                TokenObservation(
                    title = "Algoritmo dichiarato «none»",
                    detail = "Il token dichiara di non essere firmato affatto. Un server che " +
                        "si fida di questa dichiarazione accetta qualunque contenuto gli " +
                        "venga passato: è un difetto storico e grave.",
                    alarming = true,
                ),
            )
        }

        when {
            expiresAt == null -> add(
                TokenObservation(
                    title = "Non ha scadenza",
                    detail = "Senza scadenza resta valido finché il server non tiene una lista " +
                        "di revoca — e un token firmato non si può spegnere altrimenti.",
                    alarming = true,
                ),
            )
            expiresAt.isBefore(now) -> add(
                TokenObservation(
                    title = "È scaduto",
                    detail = "La data di scadenza è nel passato. Un server che lo controlla lo " +
                        "rifiuta, ed è il motivo per cui le scadenze brevi limitano il danno " +
                        "di un furto.",
                    alarming = false,
                ),
            )
            else -> {
                val minutes = (expiresAt.epochSecond - now.epochSecond) / 60
                add(
                    TokenObservation(
                        title = "Vale ancora per $minutes minuti",
                        detail = if (minutes > LONG_LIFE_MINUTES) {
                            "È una vita lunga per qualcosa che viaggia a ogni richiesta: " +
                                "chi lo rubasse avrebbe tutto questo tempo."
                        } else {
                            "Vita breve: è così che si limita il danno di un token rubato."
                        },
                        alarming = minutes > LONG_LIFE_MINUTES,
                    ),
                )
            }
        }

        SENSITIVE_HINTS.filter { it in payload.lowercase() }.forEach { hint ->
            add(
                TokenObservation(
                    title = "Contiene un dato personale: «$hint»",
                    detail = "Ricordati che è leggibile da chiunque abbia il token. Va bene per " +
                        "un identificativo, molto meno per dati che non vorresti diffondere.",
                    alarming = false,
                ),
            )
        }
    }

    private fun decode(part: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(part.padded()))
    }.getOrNull()

    /** Tokens usually drop the padding; the decoder wants it back. */
    private fun String.padded(): String = when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }

    private fun stringField(json: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun numberField(json: String, name: String): Long? =
        Regex("\"$name\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private val SENSITIVE_HINTS = listOf("email", "@", "codice_fiscale", "telefono", "iban")

    private const val LONG_LIFE_MINUTES = 60
}
