package com.cybersensei.academy.engine.labs

import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordStrengthTest {

    /**
     * The lab exists because of this password. It computes to a respectable number of bits
     * and is found instantly, so a tool that reports only the arithmetic teaches the exact
     * habit the school is trying to break.
     */
    @Test
    fun `a leet-spelled common word is not saved by its bit count`() {
        val verdict = PasswordStrength.evaluate("P@ssw0rd123!")

        assertTrue("L'aritmetica da sola la promuoverebbe", verdict.entropyBits > 60)
        assertTrue(verdict.weaknesses.contains(Weakness.LEET_SUBSTITUTION))
        assertTrue(
            "Ma la banda deve restare bassa: ${verdict.band}",
            verdict.band <= StrengthBand.WEAK,
        )
        assertTrue(verdict.entropyIsOptimistic)
    }

    @Test
    fun `a passphrase beats a short mess of symbols`() {
        val passphrase = PasswordStrength.evaluate("cavallo-batteria-graffetta-42")
        val mess = PasswordStrength.evaluate("Xk9!qZ")

        assertTrue(passphrase.entropyBits > mess.entropyBits)
        assertTrue(passphrase.band >= StrengthBand.STRONG)
        assertTrue(mess.band <= StrengthBand.WEAK)
    }

    @Test
    fun `the best known passwords are called what they are`() {
        listOf("password", "123456", "qwerty", "admin").forEach { common ->
            val verdict = PasswordStrength.evaluate(common)
            assertEquals("«$common» deve essere ridicola", StrengthBand.LAUGHABLE, verdict.band)
            assertTrue(verdict.weaknesses.isNotEmpty())
        }
    }

    @Test
    fun `length matters more than symbols`() {
        val long = PasswordStrength.evaluate("questaepiuttostolunga")
        val short = PasswordStrength.evaluate("A1!b")
        assertTrue(long.entropyBits > short.entropyBits)
    }

    /**
     * The whole point of showing three scenarios: "quanto è forte" has no answer without
     * "contro chi". Nine mixed characters are two million years of guessing at a login form
     * and a single night against a stolen archive hashed carelessly.
     */
    @Test
    fun `the same password survives one scenario and not another`() {
        val verdict = PasswordStrength.evaluate("Trentini9")

        val online = verdict.crackTimes.getValue(AttackScenario.ONLINE)
        val fast = verdict.crackTimes.getValue(AttackScenario.STOLEN_HASHES_FAST)

        assertTrue("Online deve reggere per secoli", online > A_CENTURY_IN_SECONDS)
        assertTrue("Con hash veloce deve cadere in una notte", fast < A_DAY_IN_SECONDS)
    }

    @Test
    fun `an empty password does not crash the bench`() {
        val verdict = PasswordStrength.evaluate("")
        assertEquals(0.0, verdict.entropyBits, 0.0)
        assertEquals(StrengthBand.LAUGHABLE, verdict.band)
    }

    @Test
    fun `keyboard runs and repeats are noticed`() {
        assertTrue(
            PasswordStrength.evaluate("qwertyuiop123").weaknesses.contains(Weakness.KEYBOARD_SEQUENCE),
        )
        assertTrue(
            PasswordStrength.evaluate("Aaaabbbcccdddd!").weaknesses.contains(Weakness.REPEATED_CHARACTERS),
        )
        assertTrue(
            PasswordStrength.evaluate("Milano1987!").weaknesses.contains(Weakness.DATE_LIKE),
        )
    }

    private companion object {
        const val A_DAY_IN_SECONDS = 86_400.0
        const val A_CENTURY_IN_SECONDS = 3_153_600_000.0
    }
}

class CryptoBenchTest {

    @Test
    fun `Caesar goes there and comes back`() {
        val secret = CryptoBench.caesar("Attacco all'alba", 3)
        assertNotEquals("Attacco all'alba", secret)
        assertEquals("Attacco all'alba", CryptoBench.caesar(secret, -3))
    }

    /** Twenty-six lines, one of which is Italian. That is the entire security of Caesar. */
    @Test
    fun `every Caesar shift can simply be listed`() {
        val secret = CryptoBench.caesar("ciao professore", 7)
        val attempts = CryptoBench.breakCaesarByHand(secret)

        assertEquals(25, attempts.size)
        assertTrue(attempts.any { it.second == "ciao professore" })
    }

    @Test
    fun `XOR with the same key undoes itself`() {
        val once = CryptoBench.xorHex("messaggio", "chiave")
        assertNotEquals("messaggio", once)
        // Applied twice with the same key, the bytes come back: symmetric means one key.
        val bytes = once.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val back = String(
            bytes.mapIndexed { i, b ->
                (b.toInt() xor "chiave".toByteArray()[i % 6].toInt()).toByte()
            }.toByteArray(),
        )
        assertEquals("messaggio", back)
    }

    @Test
    fun `the same input always hashes to the same thing`() {
        assertEquals(CryptoBench.sha256("prova"), CryptoBench.sha256("prova"))
        assertEquals(64, CryptoBench.sha256("prova").length)
    }

    /** The measurement that makes the avalanche effect believable instead of asserted. */
    @Test
    fun `one changed character flips about half the output`() {
        val result = CryptoBench.avalanche("password", "passwordd")

        assertNotEquals(result.first, result.second)
        assertTrue(
            "Cambiati solo ${result.percentChanged}%: l'effetto valanga non si vede",
            result.percentChanged in 35..65,
        )
    }

    @Test
    fun `the same password with two salts gives two unrelated hashes`() {
        val demo = CryptoBench.saltedPair("cavallo", "a1b2", "z9y8")

        assertTrue(demo.hashesDiffer)
        assertNotEquals(demo.withoutSalt, demo.firstHash)
        assertNotEquals(demo.withoutSalt, demo.secondHash)
    }
}

class TokenAnatomyTest {

    private val now = Instant.parse("2026-03-02T10:00:00Z")

    private fun token(header: String, payload: String, signature: String = "firma"): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            encoder.encodeToString(header.toByteArray()),
            encoder.encodeToString(payload.toByteArray()),
            signature,
        ).joinToString(".")
    }

    /** The misconception the lab exists for: a signed token is not an encrypted one. */
    @Test
    fun `a token opens with no key at all`() {
        val reading = TokenAnatomy.read(
            token("""{"alg":"HS256","typ":"JWT"}""", """{"sub":"1","email":"a@b.it","exp":1772452800}"""),
            now,
        ) as TokenReading.Opened

        assertTrue(reading.payload.contains("a@b.it"))
        assertTrue(reading.signaturePresent)
        assertEquals("HS256", reading.algorithm)
        assertTrue(
            reading.observations.any { it.title.contains("senza nessuna chiave") },
        )
    }

    @Test
    fun `a token with no expiry is flagged`() {
        val reading = TokenAnatomy.read(
            token("""{"alg":"HS256"}""", """{"sub":"1"}"""),
            now,
        ) as TokenReading.Opened

        assertTrue(reading.observations.any { it.title.contains("Non ha scadenza") && it.alarming })
    }

    @Test
    fun `the none algorithm is called out`() {
        val reading = TokenAnatomy.read(
            token("""{"alg":"none"}""", """{"sub":"1","exp":1772452800}""", signature = ""),
            now,
        ) as TokenReading.Opened

        assertFalse(reading.signaturePresent)
        assertTrue(reading.observations.any { it.title.contains("none") && it.alarming })
    }

    @Test
    fun `an expired token is recognised as expired`() {
        val past = now.minusSeconds(3600).epochSecond
        val reading = TokenAnatomy.read(
            token("""{"alg":"HS256"}""", """{"sub":"1","exp":$past}"""),
            now,
        ) as TokenReading.Opened

        assertTrue(reading.observations.any { it.title == "È scaduto" })
    }

    @Test
    fun `something that is not a token is refused politely`() {
        assertTrue(TokenAnatomy.read("", now) is TokenReading.NotTheRightShape)
        assertTrue(TokenAnatomy.read("non-sono-un-token", now) is TokenReading.NotTheRightShape)
    }
}

class ExerciseContentTest {

    private val inbox = Inbox.fromResources()
    private val hunt = LogHunt.fromResources()

    @Test
    fun `the shipped inbox is sound`() {
        val problems = inbox.validate()
        assertTrue("Problemi nella casella:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Not every message may be a trap. An exercise where everything hides something teaches
     * paranoia rather than attention, so legitimate mail has to be in there too.
     */
    @Test
    fun `the inbox contains genuinely innocent mail`() {
        val legitimate = inbox.messages.count { it.verdict == MessageVerdict.LEGITIMATE }
        assertTrue("Servono messaggi puliti: ne ho $legitimate", legitimate >= 2)
        assertTrue(
            "E almeno uno senza alcun indizio da trovare",
            inbox.messages.any { it.verdict == MessageVerdict.LEGITIMATE && it.clues.isEmpty() },
        )
    }

    @Test
    fun `every phishing message can actually be recognised`() {
        val unwinnable = inbox.messages
            .filter { it.verdict == MessageVerdict.PHISHING }
            .filter { message -> message.clues.none { it.decisive } }
            .map { it.id }
        assertTrue(
            "Phishing senza un indizio decisivo: sarebbe una trappola, non un esercizio: $unwinnable",
            unwinnable.isEmpty(),
        )
    }

    @Test
    fun `the shipped log hunt is sound`() {
        val problems = hunt.validate()
        assertTrue("Problemi nel registro:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `a perfect hunt is recognised, and so is a lucky one`() {
        val perfect = hunt.judge(hunt.guiltyLines.map { it.id }.toSet())
        assertTrue(perfect.perfect)
        assertEquals(3, perfect.found.size)

        // Selecting everything finds all three and accuses forty-nine innocents.
        val shotgun = hunt.judge(hunt.lines.map { it.id }.toSet())
        assertFalse("Selezionare tutto non è trovare", shotgun.perfect)
        assertTrue(shotgun.falseAccusations.size > 40)
    }

    @Test
    fun `an empty hunt misses everything without crashing`() {
        val nothing = hunt.judge(emptySet())
        assertEquals(hunt.guiltyLines.size, nothing.missed.size)
        assertTrue(nothing.found.isEmpty())
        assertTrue(nothing.falseAccusations.isEmpty())
    }

    /** Innocent lines that look suspicious are the exercise: they need their explanation too. */
    @Test
    fun `the decoys are explained as well as the culprits`() {
        val explainedDecoys = hunt.lines.count { !it.guilty && !it.note.isNullOrBlank() }
        assertTrue("Servono esche spiegate, ne ho $explainedDecoys", explainedDecoys >= 3)
    }
}

/**
 * The three inspection workshops share an engine, so they share a test: whatever the subject,
 * an exercise has to be winnable by looking rather than by guessing, and it must not be
 * possible to pass by always pressing the same row.
 */
class InspectionContentTest {

    private val labs = mapOf(
        "certificati" to InspectionLab.fromResources("/laboratori/certificati.json"),
        "pacchetti" to InspectionLab.fromResources("/laboratori/pacchetti.json"),
        "manifesto" to InspectionLab.fromResources("/laboratori/manifest.json"),
    )

    @Test
    fun `every shipped inspection lab is sound`() {
        labs.forEach { (name, lab) ->
            val problems = lab.validate()
            assertTrue("Problemi in «$name»:\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    /** If the answer were always in the same row, the workshop would be a button. */
    @Test
    fun `the right answer moves around`() {
        labs.forEach { (name, lab) ->
            val positions = lab.items.map { it.correct }.distinct()
            assertTrue("In «$name» la risposta è sempre in posizione ${positions.first()}", positions.size > 1)
        }
    }

    @Test
    fun `the hard cases carry a decisive clue`() {
        labs.forEach { (name, lab) ->
            val withClues = lab.items.count { item -> item.clues.any { it.decisive } }
            assertTrue(
                "In «$name» solo $withClues elementi hanno un indizio decisivo",
                withClues >= lab.items.size / 2,
            )
        }
    }

    /**
     * Each of the three has a case that exists to stop the wrong lesson being learnt: a valid
     * certificate on a phishing domain, a huge backup that is not an exfiltration, an app
     * that is invasive without being dangerous.
     */
    @Test
    fun `each lab contains the case that breaks the easy rule`() {
        assertTrue(
            "L'ispettore deve contenere un certificato valido su un dominio sbagliato",
            labs.getValue("certificati").items.any { it.id == "cert_nome" },
        )
        assertTrue(
            "Il lettore deve contenere il backup che assomiglia a un'esfiltrazione",
            labs.getValue("pacchetti").items.any { it.id == "pkt_backup" },
        )
        assertTrue(
            "La revisione deve contenere un'app invadente ma non pericolosa",
            labs.getValue("manifesto").items.any { it.id == "man_gioco" },
        )
    }
}

class JourneyTest {

    private val journey = Journey.fromResources()

    @Test
    fun `the shipped journey is sound`() {
        val problems = journey.validate()
        assertTrue("Problemi nel percorso:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** The lesson of the lab, asserted: HTTPS hides the content and not the conversation. */
    @Test
    fun `HTTPS removes the password from the people in the middle but not the destination`() {
        val wifi = journey.hops.first { it.id == "hop_wifi" }
        val plain = wifi.under(Setup(https = false, vpn = false))!!
        val secure = wifi.under(Setup(https = true, vpn = false))!!

        assertTrue("In chiaro la password si legge", plain.sees.any { it.contains("password") })
        assertFalse("Con HTTPS no", secure.sees.any { it.contains("password") })
        assertTrue("Ma la conversazione resta visibile", secure.sees.isNotEmpty())
    }

    /** The uncomfortable one: the destination gets the password under every configuration. */
    @Test
    fun `the destination receives the password whatever you turn on`() {
        val bank = journey.hops.first { it.id == "hop_banca" }
        Journey.ALL_SETUPS.forEach { setup ->
            assertTrue(
                "Con ${setup.key()} la banca dovrebbe comunque ricevere la password",
                bank.under(setup)!!.sees.any { it.contains("password") },
            )
        }
    }

    /** The VPN does not remove an observer, it swaps one in. */
    @Test
    fun `turning on the VPN moves the watching to the provider`() {
        val operator = journey.hops.first { it.id == "hop_operatore" }
        val provider = journey.hops.first { it.id == "hop_vpn" }

        val withoutVpn = Setup(https = true, vpn = false)
        val withVpn = Setup(https = true, vpn = true)

        assertTrue(
            "Senza VPN è l'operatore a vedere l'elenco dei siti",
            operator.under(withoutVpn)!!.sees.any { it.contains("elenco dei siti") },
        )
        assertTrue(
            "Con la VPN quell'elenco lo vede il fornitore",
            provider.under(withVpn)!!.sees.any { it.contains("elenco dei siti") },
        )
    }
}

class WorksiteTest {

    private val worksite = Worksite.fromResources()

    @Test
    fun `the shipped worksite is sound`() {
        val problems = worksite.validate()
        assertTrue("Problemi nel cantiere:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * The case that matters most in the whole lab: an Italian title with an apostrophe breaks
     * the naive version with no attacker anywhere. Without it a student concludes that the
     * problem is strange characters, which is the wrong lesson entirely.
     */
    @Test
    fun `an ordinary apostrophe breaks the naive version`() {
        val apostrophe = worksite.attempts.first { it.id == "att_apostrofo" }

        assertTrue(apostrophe.breaks)
        assertTrue(
            "Deve essere un input onesto, non un attacco",
            apostrophe.input == "L'informatica",
        )
    }

    @Test
    fun `the corrected version handles every input the same way`() {
        assertTrue(
            "Nella versione corretta nessun tentativo deve produrre un errore di sintassi",
            worksite.attempts.none { it.safeResult.contains("errore di sintassi") },
        )
    }

    @Test
    fun `harmless input is in the list too`() {
        val harmless = worksite.attempts.filter { !it.breaks }
        assertTrue("Servono tentativi che non rompono niente", harmless.size >= 2)
    }
}
