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
