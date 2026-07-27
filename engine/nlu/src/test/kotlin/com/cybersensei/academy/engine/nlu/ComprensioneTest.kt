package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le domande di chi non siamo noi.
 *
 * Il banco di prova misura le formulazioni che io e Francesco abbiamo immaginato. Questo
 * misura l'altra cosa, quella che nessun elenco di alias puo' risolvere: frasi scritte come
 * le direbbe una persona qualunque, senza una parola in comune con il modo in cui il
 * contenuto e' scritto. «Quando mi arriva una mail strana che vuole la password» non
 * condivide niente con «Come riconosco un'email di phishing».
 *
 * Prima dell'indice semantico il professore ne capiva sei su venti. Il numero qui sotto e'
 * la misura del guadagno, e il motivo per cui esiste un file di vettori da sette megabyte.
 */
class ComprensioneTest {

    private val knowledgeBase = KnowledgeBase.fromResources()
    private val soloParole = QuestionAnswerer(knowledgeBase)
    private val conSignificato = QuestionAnswerer(
        knowledgeBase,
        semantic = SemanticIndex(knowledgeBase.entries, WordVectors.fromResources()),
    )

    /** Formulazioni mai scritte in nessun alias, con la voce che deve rispondere. */
    private val prove = listOf(
        "quando mi arriva una mail strana che vuole la password" to "faq_phishing_riconoscere",
        "mi e' arrivato un messaggio che dice che il pacco e' bloccato" to "faq_phishing_riconoscere",
        "uno mi ha telefonato dicendo di essere della banca" to "faq_ingegneria_sociale",
        "se mi bloccano tutti i file e vogliono soldi" to "faq_ransomware",
        "mi hanno criptato il computer e chiedono un riscatto" to "faq_ransomware",
        "come faccio a scegliere una parola d'ordine robusta" to "faq_password_sicura",
        "conviene salvare le chiavi di accesso nel browser" to "faq_gestore_password",
        "serve il secondo codice quando entro nel conto" to "faq_2fa",
        "il simbolo del lucchetto vuol dire che il sito e' affidabile" to "faq_lucchetto",
        "posso usare la rete del bar per pagare" to "faq_wifi_pubblico",
        "navigare in incognito mi nasconde davvero" to "faq_incognito",
        "quelli che mi seguono con la pubblicita' su tutti i siti" to "faq_impronta_digitale",
        "ogni quanto devo aggiornare il telefono" to "faq_aggiornamenti",
        "dove tengo le copie dei miei file per stare tranquillo" to "faq_backup_321",
        "hanno pubblicato le password rubate di un sito che uso" to "faq_data_breach",
        "mi conviene mettere una rete privata virtuale" to "faq_vpn_anonima",
        "le password sui siti sono scritte in chiaro" to "faq_hash_vs_cifratura",
        "cosa vede il gestore della linea quando navigo" to "faq_metadati",
        "uno e' entrato nel mio profilo senza sapere la parola d'ordine" to "faq_furto_sessione",
        "i programmi che scarico gratis sono pericolosi" to "faq_app_craccate",
    )

    /**
     * Cosa il professore mette davanti allo studente: una risposta quando e' sicuro, due o
     * tre proposte quando non lo e'. Vuota solo quando non ha proprio niente.
     *
     * La misura e' cambiata con il professore. Prima contava la risposta secca, perche' il
     * motore ne dava sempre una; ora quando non e' sicuro propone, e cio' che conta e' se la
     * voce giusta e' fra quelle che offre — allo studente basta un tocco.
     */
    private fun offerte(answerer: QuestionAnswerer, domanda: String): List<String> =
        when (val r = answerer.ask(domanda)) {
            is AnswerResult.Found -> listOf(r.entry.id)
            is AnswerResult.Ambiguous -> r.options.map { it.id }
            is AnswerResult.Unsure -> r.options.map { it.id }
            is AnswerResult.NotUnderstood -> emptyList()
        }

    private fun giuste(answerer: QuestionAnswerer) =
        prove.count { (domanda, atteso) -> atteso in offerte(answerer, domanda) }

    /**
     * Il numero che giustifica i sette megabyte. Se scende, qualcosa nel motore o nei
     * vettori si e' rotto — ed e' l'unico modo di accorgersene senza provare a mano.
     */
    @Test
    fun `capire il significato batte nettamente il capire le parole`() {
        val prima = giuste(soloParole)
        val dopo = giuste(conSignificato)

        assertTrue(
            "Il significato non aggiunge abbastanza: parole $prima, significato $dopo su ${prove.size}",
            dopo >= prima + 4,
        )
        assertTrue("Troppo poche risposte giuste: $dopo su ${prove.size}", dopo >= 15)
    }

    /** Nessuna deve restare senza risposta: il silenzio era il difetto da cui siamo partiti. */
    @Test
    fun `nessuna di queste domande resta senza risposta`() {
        val mute = prove.filter { (domanda, _) -> offerte(conSignificato, domanda).isEmpty() }

        assertTrue(
            "Domande rimaste senza risposta:\n${mute.joinToString("\n") { it.first }}",
            mute.isEmpty(),
        )
    }

    /**
     * Capire di piu' non deve voler dire rispondere a chiunque con aria sicura.
     *
     * Una domanda fuori materia puo' somigliare a una lezione — «come si cambia una gomma
     * dell'auto» somiglia alla voce sui certificati quanto «se mi bloccano tutti i file»
     * somiglia al ransomware, e nessun numero le separa. Cio' che non e' ammesso e' che una
     * somiglianza diventi una risposta: fuori materia il professore puo' proporre, mai
     * affermare.
     */
    @Test
    fun `fuori materia il professore non risponde mai con aria sicura`() {
        listOf(
            "qual e' la ricetta della carbonara",
            "come si pota un ulivo",
            "chi ha vinto il mondiale nel 2006",
            "come si cambia una gomma dell'auto",
            "consigliami un film di paura",
        ).forEach { domanda ->
            val entry = (conSignificato.ask(domanda) as? AnswerResult.Found)?.entry
            assertTrue(
                "«$domanda» ha ricevuto una lezione con certezza: ${entry?.id}",
                entry == null || entry.kind == EntryKind.CONVERSATION,
            )
        }
    }

    /**
     * Il file dei vettori e le soglie del motore sono tarati l'uno sulle altre: cambiare le
     * dimensioni e lasciare le soglie dov'erano fa peggiorare il professore in silenzio.
     * Duecentocinquantasei sono tutte quelle che il modello di partenza ha — oltre non c'e'
     * niente da comprare, per nessuna cifra di megabyte.
     */
    @Test
    fun `la tabella dei significati e' quella attesa`() {
        val vectors = WordVectors.fromResources()

        assertTrue("Vocabolario troppo piccolo: ${vectors.size}", vectors.size > 50_000)
        assertTrue("Dimensioni inattese: ${vectors.dimensions}", vectors.dimensions == 256)
        assertTrue("Manca una parola comune", "phishing" in vectors && "password" in vectors)
    }

    /**
     * Nessuna parola di una domanda vera deve mancare dalla tabella: se ne mancassero, il
     * vocabolario sarebbe il collo di bottiglia e varrebbe la pena allargarlo. La misura dice
     * il contrario, ed e' il motivo per cui i megabyte in piu' sono andati nelle dimensioni.
     */
    @Test
    fun `le parole delle domande vere stanno tutte nella tabella`() {
        val vectors = WordVectors.fromResources()
        val fuori = (prove.map { it.first } + FRASI_DI_TUTTI_I_GIORNI)
            .flatMap { ItalianText.normalise(it).split(' ') }
            .filter { it.length > 1 && it !in ItalianText.STOPWORDS }
            .toSet()
            .filterNot { it in vectors }

        assertTrue("Parole fuori dalla tabella: $fuori", fuori.isEmpty())
    }

    private companion object {
        /** Come parla chi non ha mai letto una riga di questo progetto. */
        val FRASI_DI_TUTTI_I_GIORNI = listOf(
            "mio nipote scarica giochi strani sul tablet e mi preoccupo",
            "mia moglie dice che le hanno clonato la carta di credito",
            "mi e' arrivata una bolletta che non ho mai chiesto",
            "il telefono e' diventato lentissimo da ieri sera",
            "un tizio su whatsapp dice di essere mio figlio",
            "ho ricevuto una multa via email con un link",
            "sul computer di mia madre e' comparso un avviso rosso",
            "quanti anni hai e come ti chiami",
        )
    }
}
