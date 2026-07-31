# memescan

Scanner automatico di meme coin su **Robinhood Chain** (chain id 4663), con
controlli anti-rug, tracciamento di wallet profittevoli, alert su Telegram e
dashboard mobile.

Non fa trading e non tocca mai una chiave privata: produce **segnali**, la
decisione resta tua.

---

## Come funziona

```
      SCOPERTA                  FILTRO                  SEGNALE
 ┌──────────────────┐    ┌──────────────────┐    ┌───────────────────┐
 │ eventi on-chain  │    │  prefiltro       │    │  Telegram         │
 │ (PairCreated /   │───▶│  liquidita, eta, │───▶│  (push istantanea)│
 │  PoolCreated)    │    │  volume, txns    │    │                   │
 ├──────────────────┤    ├──────────────────┤    ├───────────────────┤
 │ GeckoTerminal    │    │  anti-rug        │    │  dashboard / APK  │
 │ (new pools)      │───▶│  honeypot, mint, │───▶│  (watchlist e     │
 ├──────────────────┤    │  LP, holder      │    │   storico)        │
 │ Dexscreener      │    ├──────────────────┤    └───────────────────┘
 │ (profili, boost) │───▶│  punteggio 0-100 │
 ├──────────────────┤    └──────────────────┘
 │ wallet tracciati │────────────▲
 │ (segnale piu'    │            │  il peso maggiore
 │  anticipato)     │────────────┘
 └──────────────────┘
```

Il punto chiave: **gli eventi on-chain arrivano nel blocco stesso in cui il
pool viene creato**, mentre gli aggregatori indicizzano con minuti di ritardo.
Su un meme coin quei minuti sono tutta la differenza.

### Il punteggio

| Componente | Peso | Cosa misura |
|---|---|---|
| Wallet tracciati | 25 | quanti wallet bravi, indipendenti, stanno comprando |
| Momentum | 28 | accelerazione del volume, pressione in acquisto, prezzo non gia' esploso |
| Liquidita' | 18 | abbastanza per entrare e uscire, non tanta da essere gia' finita |
| Distribuzione | 17 | numero di holder, concentrazione, LP bruciata |
| Sicurezza | 12 | ownership rinunciata, contratto verificato, nessuna funzione critica |

Le penalita' dei warning si sottraggono (max −30). Un motivo di scarto grave
(honeypot, mint aperto, blacklist, scam segnalato) elimina il candidato a
prescindere dal punteggio.

### I controlli anti-rug

| Controllo | Come |
|---|---|
| Honeypot | serie lunga di acquisti con zero vendite: non si puo' uscire |
| Mint aperto | selettore `mint` nel bytecode con ownership non rinunciata |
| Blacklist | il contratto puo' impedire a un indirizzo di vendere |
| Tasse mutabili | `setFee` / `setTaxes` ancora nelle mani del proprietario |
| Liquidita' | quota di LP token bruciata (pool in stile V2) |
| Concentrazione | quota dei primi 10 wallet, **esclusi pool e contratti** |
| Deployer | quanta supply si e' tenuto chi ha creato il token |
| Proxy | logica sostituibile dopo il lancio |
| Wash trading | rapporto volume/liquidita' fuori scala |

---

## Setup

### 1. Configurazione

```bash
cd backend
cp .env.example .env
```

Riempi almeno questi due campi:

| Variabile | Dove si prende |
|---|---|
| `TELEGRAM_BOT_TOKEN` | apri **@BotFather** su Telegram → `/newbot` → copia il token |
| `TELEGRAM_CHAT_ID` | apri **@userinfobot** → copia il campo `Id` |

Consigliato ma non obbligatorio: una chiave RPC gratuita di
[Alchemy](https://alchemy.com) o [QuickNode](https://quicknode.com) in
`RPC_URL`. L'endpoint pubblico funziona, ma ha rate limit bassi per un polling
continuo.

### 2. Avvio con Docker (consigliato)

```bash
docker compose up -d          # dalla cartella principale
docker compose logs -f        # per vedere cosa sta facendo
```

Dashboard su `http://<ip-della-macchina>:8080`.

### 3. Avvio senza Docker

```bash
cd backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m memescan.cli doctor    # verifica che tutto risponda
.venv/bin/python -m memescan.cli serve
```

### 4. Wallet da tracciare

E' il modulo che da' il segnale piu' anticipato. Due modi:

```bash
# automatico: cerca chi era presto su piu' token poi esplosi
python -m memescan.cli discover-wallets --top 30

# manuale, se hai gia' indirizzi tuoi
python -m memescan.cli add-wallet 0x... --label "wallet bravo"
```

Il comando automatico ha bisogno di uno storico: al primo giorno trova poco.
Lascia girare lo scanner qualche giorno e rilancialo, oppure aggiungi a mano
qualche indirizzo preso dai top trader su Dexscreener.

---

## L'app Android

L'APK contiene la dashboard e parla con il backend via HTTP. **Lo scanner non
gira dentro il telefono**: Android sospende i processi in background dopo pochi
minuti e un polling continuo scaricherebbe la batteria. Il backend sta su un
VPS o sul PC, il telefono riceve.

Le notifiche push arrivano via **Telegram**, che e' gia' ottimizzata per quello.

### Costruire l'APK

Su GitHub: **Actions → Build APK → Run workflow**, mettendo nel campo
`backend_url` l'indirizzo del tuo backend (es. `http://192.168.1.50:8080`).
A fine build scarichi l'artefatto `memescan-apk` e installi il file sul
telefono (serve abilitare l'installazione da fonti sconosciute).

In locale, con Android SDK e JDK 21 installati:

```bash
cd mobile
npm install
MEMESCAN_BACKEND_URL=http://192.168.1.50:8080 npm run build:www
npx cap add android
npm run apk
# risultato: mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

Se non vuoi installare niente, la dashboard e' gia' una PWA: aprila nel browser
del telefono e usa "Aggiungi a schermata Home".

---

## Comandi

```bash
python -m memescan.cli doctor              # verifica configurazione e connettivita'
python -m memescan.cli serve               # backend + dashboard + alert
python -m memescan.cli scan-once           # una scansione singola, poi esce
python -m memescan.cli check 0xTOKEN       # analisi completa di un token
python -m memescan.cli discover-wallets    # trova wallet da tracciare
python -m memescan.cli add-wallet 0xADDR   # aggiunge un wallet
python -m memescan.cli test-telegram       # prova l'invio di un messaggio
```

## API

| Endpoint | Cosa restituisce |
|---|---|
| `GET /api/health` | stato del motore, RPC, Telegram |
| `GET /api/stats` | token visti, alert, **multiplo di picco medio** |
| `GET /api/recent` | candidati valutati, dal punteggio piu' alto |
| `GET /api/pending` | pool appena creati, non ancora indicizzati |
| `GET /api/candidates?status=alerted` | storico degli alert |
| `GET/POST/DELETE /api/wallets` | gestione dei wallet tracciati |
| `POST /api/rescan/{token}` | rivaluta un token adesso |

Se esponi il servizio su internet **imposta `API_TOKEN`** nel `.env`: senza,
l'API e' aperta a chiunque conosca l'indirizzo.

---

## Regolare i filtri

Tutto sta nel `.env`, non serve toccare il codice. I due che contano di piu':

- `MIN_LIQUIDITY_USD` (default 8000) — alzalo per meno rumore, abbassalo per
  vedere i lanci prima. In una scansione reale ha scartato circa il 75% dei
  pool nuovi.
- `ALERT_MIN_SCORE` (default 65) — quanti alert vuoi ricevere. A 75 ne arrivano
  pochi e selezionati, a 55 molti piu' falsi positivi.

**Il numero da guardare per capire se sta funzionando** e' `avg_peak_multiple`
in `/api/stats`: e' il multiplo medio raggiunto dai token dopo l'alert,
calcolato sui prezzi reali. Se dopo un paio di settimane resta sotto 1,5x, i
filtri vanno stretti, non allargati.

---

## Test

```bash
cd backend && python -m unittest discover -s tests -v
```

39 test, nessuno tocca la rete: coprono la decodifica degli eventi di creazione
pool, il riconoscimento degli honeypot, il punteggio e la contabilita' dello
storico alert.

---

## Limiti da conoscere

- **I controlli anti-rug non sono una garanzia.** Riconoscono le trappole note,
  non quelle nuove. Un contratto proxy o un hook Uniswap V4 possono nascondere
  comportamenti che l'analisi del bytecode non vede.
- **Sui pool V4 il blocco della liquidita' non e' verificabile** dal LP token,
  perche' non esiste: il controllo viene saltato e segnalato come tale.
- **Il modulo social e' spento** senza un piano X a pagamento. Non e' una
  perdita grave: il segnale on-chain e quello dei wallet arrivano comunque prima.
- **La maggior parte di questi token va a zero.** Lo scanner riduce il rumore,
  non il rischio. Usa un wallet separato e una size fissa decisa in anticipo.
