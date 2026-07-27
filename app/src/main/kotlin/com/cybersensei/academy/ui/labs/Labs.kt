package com.cybersensei.academy.ui.labs

/**
 * The workshops, and where each one belongs in the syllabus.
 *
 * Listed here rather than in content because a lab is code: unlike a lesson, it cannot be
 * added by writing JSON. Keeping the catalogue next to the screens means the path can offer
 * only the ones that actually exist, instead of promising a room that was never built.
 */
enum class Lab(
    val id: String,
    val title: String,
    val icon: String,
    val summary: String,
    /** Which level teaches what this lab exercises. */
    val level: Int,
) {
    PASSWORD_FORGE(
        id = "fucina",
        title = "Fucina delle password",
        icon = "🔐",
        summary = "Scrivi una password e guarda quanto regge davvero — contro chi prova dal " +
            "sito, e contro chi ha rubato l'archivio.",
        level = 1,
    ),
    SUSPICIOUS_INBOX(
        id = "casella",
        title = "Casella sospetta",
        icon = "📧",
        summary = "Dodici messaggi. Decidi tu quali sono phishing, quali spam e quali " +
            "semplicemente veri. Poi ti mostro cosa non hai visto.",
        level = 1,
    ),
    CRYPTO_BENCH(
        id = "banco",
        title = "Banco di crittografia",
        icon = "🧪",
        summary = "Cifra, calcola impronte, cambia un carattere e guarda l'effetto valanga. " +
            "E scopri perché il salt funziona anche se è pubblico.",
        level = 2,
    ),
    TOKEN_ANATOMY(
        id = "token",
        title = "Anatomia di un token",
        icon = "🎫",
        summary = "Incolla un token firmato e leggilo senza nessuna chiave. È il modo più " +
            "rapido per capire che firmato non vuol dire cifrato.",
        level = 2,
    ),
    PACKET_TRACE(
        id = "traccia",
        title = "Traccia il pacchetto",
        icon = "📦",
        summary = "La tua password attraversa cinque soggetti. Accendi e spegni HTTPS e VPN " +
            "e guarda cosa vede ciascuno.",
        level = 1,
    ),
    PACKET_READER(
        id = "pacchetti",
        title = "Lettore di pacchetti",
        icon = "📡",
        summary = "Sei righe di cattura di rete. Riconoscere cosa sta succedendo guardando " +
            "porta, direzione e volume.",
        level = 2,
    ),
    WORKSITE(
        id = "cantiere",
        title = "Cantiere Web",
        icon = "🏗️",
        summary = "Una riga di codice scritta in due modi. Scegli cosa scrive l'utente e " +
            "guarda cosa ne fa l'applicazione, prima e dopo la correzione.",
        level = 2,
    ),
    CERTIFICATE_INSPECTOR(
        id = "certificati",
        title = "Ispettore di certificati",
        icon = "📜",
        summary = "Sei connessioni. Prosegui o chiudi? Uno dei certificati è perfettamente " +
            "valido e porta nel posto sbagliato.",
        level = 2,
    ),
    MANIFEST_REVIEW(
        id = "manifesto",
        title = "Revisione del manifesto",
        icon = "📱",
        summary = "Sei app e i permessi che chiedono. Ciò che chiede è coerente con ciò " +
            "che offre?",
        level = 3,
    ),
    ANOMALY_HUNT(
        id = "anomalia",
        title = "Caccia all'anomalia",
        icon = "🔍",
        summary = "Cinquantadue righe di registro, tre raccontano un'intrusione. " +
            "Non cercare errori: cerca ciò che rompe il ritmo.",
        level = 3,
    ),
    ;

    companion object {
        fun byId(id: String): Lab? = entries.firstOrNull { it.id == id }

        fun forLevel(level: Int): List<Lab> = entries.filter { it.level == level }
    }
}
