# Cyber Sensei

**Scuola di white hacking difensivo in un APK.** Un corso completo di cybersecurity con un
professore che ti conosce, ti interroga e ti spiega *sempre* perché una risposta è giusta o
sbagliata — anche quando indovini.

- 🧠 **Prof. Hackstein White**, un docente simulato interamente offline: nessuna API, nessun
  modello linguistico, nessun costo. Regole, memoria e migliaia di frasi combinate.
- 📴 **Zero rete.** L'app non chiede nemmeno il permesso `INTERNET`. I tuoi dati non possono
  uscire dal telefono perché non esiste il canale per farli uscire.
- 🎯 **Anti-fortuna.** Rispondere giusto per caso non basta: contano la confidenza dichiarata,
  le varianti della stessa domanda e la padronanza misurata nel tempo.
- 🛡️ **Solo difesa.** Nessun tool offensivo, nessun bersaglio reale: tutti i laboratori sono
  simulatori chiusi dentro l'app.

Il progetto completo — didattica, motore del professore, architettura, roadmap — è in
**[PROGETTO.md](PROGETTO.md)**.

---

## Stato

| Fase | Contenuto | Stato |
|------|-----------|-------|
| 0 | Fondamenta: progetto multi-modulo, design system, navigazione, CI | ✅ fatta |
| 1 | Il motore del professore (regole, memoria, padronanza, ripasso, mini-NLU) | ✅ fatta |
| 2 | Onboarding + Livello 0 completo (primo APK davvero usabile) | ✅ fatta |
| 3–7 | Livelli Facile / Intermedio / Difficile, laboratori, diploma, release | ⏳ prossima |

## Come si compila

Serve solo un JDK 21; l'Android SDK viene risolto da `local.properties` o da `ANDROID_HOME`.

```bash
./gradlew assembleDebug     # APK in app/build/outputs/apk/debug/
./gradlew test              # test unitari di tutti i moduli
./gradlew lintDebug         # Android Lint
```

**Non serve compilare nulla in locale:** ogni push a GitHub produce un APK scaricabile dalla
scheda *Actions* → ultima esecuzione → artifact `CyberSensei-APK`.

## Struttura

```
app/                 assemblaggio, navigazione, schermate
core/model/          dominio (livelli, profilo studente, segno zodiacale)
core/common/         tempo, casualità deterministica, utility condivise
core/ui/             design system: tema notturno, componenti, bolla del professore
engine/mastery/      padronanza, verdetti sulle risposte, anti-fortuna
engine/scheduler/    ripasso a intervalli crescenti (SM-2)
engine/tutor/        regole di dialogo, memoria episodica, composizione delle frasi
engine/nlu/          comprensione offline delle domande libere (TF-IDF)
core/database/       Room: profilo, padronanza, ripassi, diario, progressi
core/curriculum/     modelli e caricamento di lezioni e domande
content/             copione del professore e FAQ, in JSON: contenuti, non codice
build-logic/         convention plugin Gradle condivisi da tutti i moduli
```

I moduli `core/` ed `engine/` sono **Kotlin puro**, senza dipendenze da Android: i loro test
girano in millisecondi, ed è lì che vive tutta la logica didattica.

## Uso dei contenuti

I contenuti didattici sono pensati per **formazione difensiva**. Testare sistemi che non ti
appartengono, senza autorizzazione scritta, è un reato — in Italia lo dice l'art. 615‑ter del
codice penale, e l'app te lo ripete ogni volta che serve.
