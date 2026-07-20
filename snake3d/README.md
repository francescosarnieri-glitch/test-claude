# 🐍 Snake 3D — Dal Bosco al Lago

Un videogioco Snake **completamente in 3D** (Three.js/WebGL), pensato per **Android touch screen**: joypad virtuale, swipe, livelli a tema che ti portano dal bosco fitto fino al grande lago al tramonto. Funziona **offline**, senza server e senza dipendenze esterne (Three.js è incluso nella cartella `vendor/`).

## ▶ Come si gioca

**Sul telefono (il modo più semplice):** apri `index.html` con un server statico qualsiasi, oppure attiva GitHub Pages sul repository e visita `.../snake3d/`. Aggiungi la pagina alla home screen per giocare a schermo intero.

**Sul computer:** `cd snake3d && python3 -m http.server 8080` poi apri `http://localhost:8080` (frecce o WASD per muoverti, Spazio per la pausa).

**Come app Android nativa:** apri la cartella `snake3d-android/` con Android Studio e premi Run — il progetto copia automaticamente il gioco negli assets e lo avvia in una WebView a schermo intero (vibrazione, schermo sempre acceso, record salvati).

## 🎮 Controlli

- **🕹 Joypad virtuale** in basso a sinistra (si sposta dove tocchi)
- **👆 Swipe** in qualunque punto dello schermo
- Entrambi attivabili/disattivabili dal menu
- **🎥** cambia telecamera: vista dall'alto ↔ inseguimento dietro la testa
- **⏸** pausa (automatica se metti l'app in background)

## 🌲 I livelli

| # | Livello | Ambiente |
|---|---------|----------|
| 1 | Bosco Fitto | alberi ovunque, foglie che cadono |
| 2 | Radura Fiorita | prati, fiori, farfalle, massi |
| 3 | Sentiero Roccioso | pini e rocce, ritmo più veloce |
| 4 | Stagno delle Ninfee | prime acque con ninfee |
| 5 | Grande Lago | isole sabbiose, acque profonde, pesci |
| 6 | Lago al Tramonto | cielo arancione, lucciole, velocità massima |
| ∞ | Livelli infiniti | i laghi si alternano, sempre più veloci |

I livelli si **sbloccano** progredendo e restano sbloccati (salvataggio locale). Ogni livello ha un obiettivo di punti; completandolo ricevi un bonus.

## 🍎 Cibi e power-up (9 varietà)

| Cibo | Effetto |
|------|---------|
| 🍎 Mela | +10 punti |
| 🫐 Mirtilli | +20 punti |
| 🍄 Fungo Scattante | +15, super velocità per 6s |
| ✨ Bacca d'Oro | +50, ma sparisce in fretta! |
| 🍒 Ciliegie Magiche | +5 e ti accorciano di 3 segmenti |
| ⭐ Stella Boschiva | invincibile 8s: attraversi alberi, acqua, muri e te stesso |
| 💎 Cristallo Magnete | attira il cibo vicino per 8s |
| 🌸 Fiore del Tempo | rallenta il tempo per 6s |
| 🐟 Pesce di Lago | +40, appare solo vicino all'acqua |

## ✨ Altre funzioni

- **Combo**: mangia entro 3,5 secondi dal boccone precedente per moltiplicare i punti fino a ×5
- **3 vite** con respawn, flash e vibrazione alla collisione
- **Record e impostazioni salvati** in localStorage
- **Acqua letale** (i serpenti di bosco non nuotano…), alberi e rocce da schivare
- Ombre in tempo reale, nebbia atmosferica, nuvole in movimento, particelle ambientali (foglie/farfalle/lucciole), esplosioni di particelle e punteggi volanti quando mangi
- **Audio 100% procedurale** (WebAudio): effetti, fanfare e musica di sottofondo generativa — nessun file audio
- Wake lock (lo schermo non si spegne), pausa automatica in background, pulsante schermo intero

## 🗂 Struttura

```
snake3d/
├── index.html          interfaccia, HUD, menu, joypad (CSS incluso)
├── game.js             tutta la logica di gioco e la grafica 3D
└── vendor/three.module.js   Three.js r160 incluso per l'uso offline
snake3d-android/        wrapper WebView per generare l'APK con Android Studio
```
