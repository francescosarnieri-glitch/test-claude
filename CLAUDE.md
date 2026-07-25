# Cyber Sensei — note per chi lavora sul codice

Progetto completo e piano didattico: **PROGETTO.md**. Questo file contiene solo le regole
operative del repository.

## Comandi

```bash
./gradlew assembleDebug          # APK di debug
./gradlew test                   # unit test JVM di tutti i moduli
./gradlew lintDebug              # Android Lint
./gradlew :core:model:test       # test di un singolo modulo
```

L'SDK Android va indicato in `local.properties` (`sdk.dir=...`) oppure via `ANDROID_HOME`.
`local.properties` non è versionato.

## Architettura

- **Multi-modulo Gradle** con convention plugin in `build-logic/` (`cybersensei.android.*`).
  Un modulo nuovo si crea applicando i plugin, senza duplicare configurazione.
- **`core/model` e `core/common` sono Kotlin puro** (niente Android): i test ci girano in
  millisecondi ed è lì che deve vivere la logica del professore ovunque sia possibile.
- **`core/ui`** è il design system: colori semantici (`SenseiTheme.colors`), tipografia,
  componenti condivisi. Nessuna schermata di feature vive qui.
- **I contenuti didattici non sono codice**: da Fase 1 stanno in JSON sotto `content/` e
  vengono validati in CI. Non inserire testi di lezioni o domande dentro file Kotlin.

## Regole non negoziabili

1. **Nessun permesso di rete.** Il manifest non deve mai contenere `INTERNET`, e nessuna
   dipendenza deve introdurlo. È una promessa fatta allo studente, oltre che una scelta
   tecnica: senza rete non esistono chiamate API da pagare.
2. **Nessuna telemetria**, nessun analytics, nessun crash reporter remoto.
3. **Niente tool offensivi funzionanti.** I laboratori sono simulazioni chiuse: nessun
   payload utilizzabile fuori dall'app, nessun bersaglio reale, nessuna scansione di rete.
4. **Ogni risposta dello studente riceve una spiegazione**, giusta o sbagliata che sia. È il
   requisito centrale del prodotto: se una funzionalità lo aggira, è un bug.
5. **Il colore non è mai l'unico veicolo di significato**: sempre anche icona o etichetta.

## Stile

- Codice e commenti in inglese; **tutti i testi rivolti allo studente in italiano**.
- Il professore si chiama **Prof. Hackstein White** (breve: Prof. White). Il nome non va mai
  scritto a mano nelle schermate: passa dai contenuti o dalle costanti del design system.
- Il tono del professore è autorevole e diretto, mai canzonatorio verso lo studente.
- Logica temporale sempre attraverso `TimeProvider` (mai `Instant.now()` sparso nel codice),
  altrimenti diventa impossibile testare streak, assenze e ripassi.
- Casualità sempre attraverso `DeterministicRandom`: il professore deve essere riproducibile.
