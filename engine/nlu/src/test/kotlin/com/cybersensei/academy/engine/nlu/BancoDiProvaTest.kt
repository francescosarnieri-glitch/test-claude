package com.cybersensei.academy.engine.nlu

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test bench: the same question, asked the way different people would ask it.
 *
 * This is the only honest way to claim the professor "understands". Every line is a phrasing
 * a real person might type and the answer it has to reach; most of them were never written
 * into the content as aliases, which is the point — they measure what the engine generalises
 * rather than what it was handed.
 *
 * It is also the place where a complaint becomes permanent. A question the professor gets
 * wrong is added here first, fixed second: from then on the build fails if it ever regresses.
 *
 * Three kinds of expectation:
 *  - an entry id: that exact answer must come out;
 *  - LEZIONE: any answer from the syllabus, never a conversational one;
 *  - MAI_CONVERSAZIONE: the corpus has nothing to say, and admitting it is correct — as
 *    long as the admission does not turn into the app's price list.
 */
class BancoDiProvaTest {

    private val answerer = QuestionAnswerer(KnowledgeBase.fromResources())

    private val casi = listOf(
        "ciao chi sei?" to "conv_chi_sei",
        "ma chi sei tu?" to "conv_chi_sei",
        "chi sei??" to "conv_chi_sei",
        "scusa ma tu chi saresti esattamente" to "conv_chi_sei",
        "con chi sto parlando" to "conv_chi_sei",
        "come ti chiami" to "conv_come_ti_chiami",
        "tu come ti chiami" to "conv_come_ti_chiami",
        "dimmi come ti chiami" to "conv_come_ti_chiami",
        "qual e' il tuo nome" to "conv_come_ti_chiami",
        "sei un'intelligenza artificiale?" to "conv_sei_intelligenza_artificiale",
        "sei chatgpt" to "conv_sei_intelligenza_artificiale",
        "ma sei una intelligenza artificiale vera" to "conv_sei_intelligenza_artificiale",
        "sei una ia come chatgpt" to "conv_sei_intelligenza_artificiale",
        "sei una persona vera" to "conv_sei_umano",
        "quindi sei un bot" to "conv_sei_umano",
        "sei una persona in carne e ossa" to "conv_sei_umano",
        "chi ti ha creato" to "conv_chi_ti_ha_creato",
        "ma chi ti ha programmato" to "conv_chi_ti_ha_creato",
        "chi e' che ha creato questa app" to "conv_chi_ti_ha_creato",
        "quanti anni hai prof" to "conv_quanti_anni",
        "come va oggi" to "conv_come_stai",
        "cosa sai fare" to "conv_cosa_sai_fare",
        "allora cosa sai fare tu" to "conv_cosa_sai_fare",
        "di cosa possiamo parlare" to "conv_cosa_sai_fare",
        "che cosa posso chiederti" to "conv_cosa_sai_fare",
        "quali argomenti conosci tu" to "conv_cosa_sai_fare",
        "non ho capito" to "conv_non_ho_capito",
        "non ho capito niente" to "conv_non_ho_capito",
        "puoi rispiegarmelo" to "conv_non_ho_capito",
        "come funziona l'app" to "conv_come_funziona_app",
        "da dove comincio" to "conv_come_funziona_app",
        "cosa devo studiare oggi" to "conv_cosa_devo_studiare",
        "cosa mi consigli di studiare" to "conv_cosa_devo_studiare",
        "come prendo il diploma" to "conv_cos_e_il_diploma",
        "cosa serve per prendere il diploma" to "conv_cos_e_il_diploma",
        "come funziona l'esame" to "conv_cos_e_l_esame",
        "come faccio a sbloccare il livello successivo" to "conv_sbloccare_livello",
        "perche' il livello e' ancora bloccato" to "conv_sbloccare_livello",
        "cosa sono i laboratori" to "conv_cosa_sono_i_laboratori",
        "cosa sono i laboratori pratici" to "conv_cosa_sono_i_laboratori",
        "a cosa serve la pagella" to "conv_cos_e_la_pagella",
        "perche' mi fai ripassare le stesse cose" to "conv_cos_e_il_ripasso",
        "cos'e' la notte dell'incidente" to "conv_cos_e_l_incidente",
        "voglio ricominciare da capo" to "conv_ricominciare",
        "voglio cancellare tutti i miei dati" to "conv_ricominciare",
        "come faccio a resettare l'app" to "conv_ricominciare",
        "come attivo le notifiche" to "conv_promemoria",
        "come faccio ad attivare le notifiche" to "conv_promemoria",
        "puoi essere piu' severo con me" to "conv_cambiare_tono",
        "i miei dati dove finiscono" to "conv_dati_dove_finiscono",
        "i miei dati vengono venduti?" to "conv_dati_dove_finiscono",
        "questa app raccoglie dati personali" to "conv_dati_dove_finiscono",
        "l'app funziona senza internet" to "conv_serve_internet",
        "l'app si collega a internet" to "conv_serve_internet",
        "serve internet per usarla" to "conv_serve_internet",
        "quanto costa" to "conv_quanto_costa",
        "e' gratis?" to "conv_quanto_costa",
        "devo pagare qualcosa" to "conv_quanto_costa",
        "ci sono pubblicita' dentro" to "conv_quanto_costa",
        "ciao" to "conv_saluto",
        "buongiorno prof" to "conv_saluto",
        "buonasera professore" to "conv_saluto",
        "ehi ciao" to "conv_saluto",
        "grazie mille" to "conv_grazie",
        "grazie prof sei stato utile" to "conv_grazie",
        "ok grazie" to "conv_grazie",
        "va bene ci vediamo domani" to "conv_congedo",
        "come entro nel telefono della mia ragazza" to "conv_insegnami_ad_attaccare",
        "insegnami a hackerare un account" to "conv_insegnami_ad_attaccare",
        "mi insegni a hackerare il telefono di una persona" to "conv_insegnami_ad_attaccare",
        "come faccio a entrare nel wifi del vicino" to "conv_insegnami_ad_attaccare",
        "come si ruba una password" to "conv_insegnami_ad_attaccare",
        "che tempo fa domani" to "conv_fuori_tema_meteo",
        "che tempo fara' domani a milano" to "conv_fuori_tema_meteo",
        "come si fa la carbonara" to "conv_fuori_tema_cucina",
        "mi dai la ricetta della carbonara" to "conv_fuori_tema_cucina",
        "chi ha vinto la partita ieri" to "conv_fuori_tema_sport",
        "dimmi una barzelletta" to "conv_fuori_tema_generico",
        "mi racconti una barzelletta" to "conv_fuori_tema_generico",
        "mi aiuti con i compiti di matematica" to "conv_fuori_tema_generico",
        "quando mi sono iscritto" to "fatto_iscrizione",
        "quando e' stata installata questa applicazione" to "fatto_iscrizione",
        // Attiva e non passiva: la forma passiva passava e questa no, e la differenza fra le
        // due e' invisibile a chi scrive il contenuto. La voce sulla superficie di attacco
        // contiene "applicazioni installate" nella risposta, e tanto bastava a rubarla.
        "quando ho installato l'applicazione" to "fatto_iscrizione",
        "quando ho installato l'app" to "fatto_iscrizione",
        "da quanto tempo uso questa app" to "fatto_iscrizione",
        "a che punto sono" to "fatto_a_che_punto",
        "come sto andando" to "fatto_a_che_punto",
        "qual e' il mio punto debole" to "fatto_punto_debole",
        "dove sono piu' fragile" to "fatto_punto_debole",
        "quanti giorni di fila ho studiato" to "fatto_streak",
        "qual e' il mio record" to "fatto_streak",
        "quante lezioni ho fatto" to "fatto_lezioni",
        "quanto tempo ho studiato" to "fatto_tempo",
        "come mi chiamo" to "fatto_nome",
        "ti ricordi il mio nome" to "fatto_nome",
        "quanti ripassi ho in scadenza" to "fatto_ripassi",
        "quali esami ho superato" to "fatto_esami",
        "quanti anni ho" to "fatto_eta",
        "che segno sono" to "fatto_eta",
        "quali domande ti ho fatto finora" to "mem_domande_fatte",
        "cosa ti ho chiesto" to "mem_domande_fatte",
        "quante domande ti ho fatto" to "mem_domande_fatte",
        "di cosa abbiamo parlato" to "mem_argomenti",
        "quali argomenti abbiamo toccato" to "mem_argomenti",
        "puoi ripetere" to "mem_ripeti",
        "ripeti" to "mem_ripeti",
        "me lo ripeti" to "mem_ripeti",
        "cosa mi hai detto prima" to "mem_prima",
        "cos'e' il phishing" to "LEZIONE",
        "come riconosco una mail truffa" to "LEZIONE",
        "come scelgo una password sicura" to "LEZIONE",
        "cos'e' l'autenticazione a due fattori" to "LEZIONE",
        "il wifi pubblico e' pericoloso" to "LEZIONE",
        "cos'e' il ransomware" to "LEZIONE",
        "cosa vuol dire crittografia" to "LEZIONE",
        "cos'e' un certificato https" to "LEZIONE",
        "cosa sono i cookie" to "LEZIONE",
        "cos'e' l'ingegneria sociale" to "LEZIONE",
        "come funziona un attacco ransomware" to "LEZIONE",
        "cosa faccio se mi rubano l'account" to "LEZIONE",
        "cos'e' il gestore di password" to "LEZIONE",
        "come si difende un'azienda da un attacco" to "LEZIONE",
        "quanto costa un attacco informatico a un'azienda" to "MAI_CONVERSAZIONE",
        "quanto costa un antivirus" to "MAI_CONVERSAZIONE"
    )

    @Test
    fun `il professore capisce la stessa domanda posta in modi diversi`() {
        val fallite = casi.mapNotNull { (domanda, atteso) ->
            val ottenuto = when (val esito = answerer.ask(domanda)) {
                is AnswerResult.Found -> esito.entry.id
                // Chiedere quale delle due non e' un errore, ma nemmeno una risposta: il
                // banco lo segna come tale, cosi' resta visibile quante volte succede.
                is AnswerResult.Ambiguous -> "SCELTA:" + esito.options.joinToString("|") { it.id }
                // Proporre non e' rispondere, ma non e' nemmeno silenzio: se la voce giusta
                // e' fra quelle proposte lo studente ci arriva con un tocco, e il banco lo
                // accetta. Se non c'e', vale come non capita.
                is AnswerResult.Unsure ->
                    if (esito.options.any { it.id == atteso }) atteso
                    else "SCELTA:" + esito.options.joinToString("|") { it.id }
                is AnswerResult.NotUnderstood -> NON_CAPITO
            }
            val corretto = when (atteso) {
                "LEZIONE" -> ottenuto != NON_CAPITO && !ottenuto.startsWith(CONVERSAZIONE) &&
                    !ottenuto.startsWith("SCELTA:")
                "MAI_CONVERSAZIONE" -> !ottenuto.startsWith(CONVERSAZIONE)
                else -> ottenuto == atteso
            }
            if (corretto) null else "  «$domanda» -> $ottenuto (atteso: $atteso)"
        }

        assertTrue(
            "${fallite.size} formulazioni su ${casi.size} non arrivano dove devono:\n" +
                fallite.joinToString("\n"),
            fallite.isEmpty(),
        )
    }

    /** Nobody should be able to grow the corpus by loosening what it has to get right. */
    @Test
    fun `il banco resta abbastanza grande da significare qualcosa`() {
        assertTrue("Il banco di prova si e' svuotato: ${casi.size} casi", casi.size >= 120)
    }

    /**
     * Una domanda su di te non puo' mai ricevere una lezione.
     *
     * Non e' una preferenza, e' una cosa che nessuna lezione puo' fare: la data in cui ti sei
     * iscritto sta nell'archivio della scuola, non nel programma. Eppure e' successo — «quando
     * ho installato l'applicazione» ha ricevuto la superficie di attacco, perche' quella
     * risposta contiene le parole «applicazioni installate» e una regola nuova le ha permesso
     * di prendersi la domanda. Qui si difende il principio, non le singole formulazioni.
     */
    @Test
    fun `le domande sullo studente non finiscono mai su una lezione`() {
        val sbagliate = DOMANDE_SU_DI_ME.mapNotNull { domanda ->
            val voce = (answerer.ask(domanda) as? AnswerResult.Found)?.entry
            "«$domanda» -> ${voce?.id} [${voce?.kind}]".takeIf { voce?.kind == EntryKind.LESSON }
        }

        assertTrue(
            "Domande sullo studente finite su una lezione:\n${sbagliate.joinToString("\n")}",
            sbagliate.isEmpty(),
        )
    }

    private companion object {
        const val NON_CAPITO = "NON_CAPITO"
        const val CONVERSAZIONE = "conv_"

        /**
         * Domande che riguardano lo studente, scritte in modi che nessun alias contiene.
         * Ognuna nomina qualcosa che il programma insegna — app, lezioni, esami, ripassi — ed
         * e' esattamente questo che le rende pericolose: la parola tecnica c'e', ma la domanda
         * non e' sulla materia.
         */
        val DOMANDE_SU_DI_ME = listOf(
            "quando ho installato l'applicazione",
            "quando ho scaricato questa app",
            "da quando sto usando questa applicazione",
            "quante lezioni ho completato io",
            "quali esami ho passato io",
            "quanti ripassi mi restano da fare",
            "su quale argomento sono messo peggio",
            "quanti giorni di fila sono venuto a studiare",
            "cosa ti ho domandato poco fa",
            "di quali argomenti abbiamo parlato oggi",
        )
    }
}
