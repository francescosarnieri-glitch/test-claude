"""Persistenza su SQLite.

Tiene lo stato tra un riavvio e l'altro: candidati visti, alert gia' mandati
(per non spammare) e, soprattutto, lo storico delle chiamate con il picco
raggiunto dopo l'alert. Senza quello storico non c'e' modo di sapere se i
filtri stanno davvero funzionando o se stanno solo generando rumore.
"""

from __future__ import annotations

import json
import shutil
import sqlite3
import threading
from pathlib import Path
from typing import Any, Iterable

from .config import settings
from .util import get_logger, now

#: Ricariche e token di scambio: non sono posizioni prese da nessuno.
IGNORED_SYMBOLS = {
    "WETH", "ETH", "USDC", "USDT", "USDG", "DAI", "USDS", "WBTC", "FRAX", "HOOD", "WHOOD",
}

#: Sotto questa pozza una moneta e' morta, comunque segni il prezzo: con
#: mezzo migliaio di dollari di liquidita' non si esce piu' per nessuna cifra
#: che valesse la pena metterci dentro.
POZZA_MORTA = 500.0

log = get_logger("memescan.store")

SCHEMA = """
CREATE TABLE IF NOT EXISTS candidates (
    token_address     TEXT PRIMARY KEY,
    pair_address      TEXT,
    symbol            TEXT,
    name              TEXT,
    dex               TEXT,
    pool_version      TEXT DEFAULT '',
    pair_created_at   INTEGER,
    first_seen        INTEGER,
    last_updated      INTEGER,
    source            TEXT,
    price_usd         REAL DEFAULT 0,
    liquidity_usd     REAL DEFAULT 0,
    fdv               REAL DEFAULT 0,
    market_cap        REAL DEFAULT 0,
    volume_5m         REAL DEFAULT 0,
    volume_1h         REAL DEFAULT 0,
    volume_24h        REAL DEFAULT 0,
    price_change_5m   REAL DEFAULT 0,
    price_change_1h   REAL DEFAULT 0,
    price_change_24h  REAL DEFAULT 0,
    buys_5m           INTEGER DEFAULT 0,
    sells_5m          INTEGER DEFAULT 0,
    holders           INTEGER DEFAULT 0,
    score             REAL DEFAULT 0,
    safety_json       TEXT DEFAULT '{}',
    breakdown_json    TEXT DEFAULT '{}',
    status            TEXT DEFAULT 'new',
    reject_reason     TEXT DEFAULT '',
    alerted_at        INTEGER DEFAULT 0,
    mcap_at_alert     REAL DEFAULT 0,
    price_at_alert    REAL DEFAULT 0,
    peak_price        REAL DEFAULT 0,
    peak_multiple     REAL DEFAULT 0,
    wallet_hits       INTEGER DEFAULT 0,
    watchlisted       INTEGER DEFAULT 0,
    alert_kind        TEXT DEFAULT '',
    peak_notified     REAL DEFAULT 0,
    liq_notified      REAL DEFAULT 0,
    -- Il punto fisso da cui si misura quanta pozza e' sparita: il massimo che
    -- ha avuto, col prezzo di quel momento. Confrontare con la lettura
    -- precedente non funziona - chi la svuota poco per volta non supera mai
    -- nessuna soglia, e una pozza calata dell'88% a fette non fa scattare
    -- niente.
    liq_riferimento   REAL DEFAULT 0,
    prezzo_riferimento REAL DEFAULT 0,
    -- Che quota delle balene entrate era gia' uscita all'ultimo avviso.
    -- Volutamente NULL finche' il token non e' stato misurato almeno una
    -- volta: la prima lettura serve solo a fotografare la situazione, se
    -- avvisasse manderebbe una notifica per ogni uscita gia' avvenuta prima
    -- che il token entrasse fra i sorvegliati.
    uscite_notified   REAL
);

CREATE INDEX IF NOT EXISTS idx_candidates_status  ON candidates(status);
CREATE INDEX IF NOT EXISTS idx_candidates_score   ON candidates(score DESC);
CREATE INDEX IF NOT EXISTS idx_candidates_seen    ON candidates(first_seen DESC);

CREATE TABLE IF NOT EXISTS wallet_events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    wallet        TEXT NOT NULL,
    token_address TEXT NOT NULL,
    symbol        TEXT,
    direction     TEXT,
    amount        REAL DEFAULT 0,
    tx_hash       TEXT,
    block_number  INTEGER DEFAULT 0,
    ts            INTEGER,
    UNIQUE(tx_hash, wallet, token_address, direction)
);
CREATE INDEX IF NOT EXISTS idx_wallet_events_token ON wallet_events(token_address, ts DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_events_ts    ON wallet_events(ts DESC);

CREATE TABLE IF NOT EXISTS tracked_wallets (
    address    TEXT PRIMARY KEY,
    label      TEXT DEFAULT '',
    -- Chi l'ha messo in lista: 'mia' se l'ha scelto una persona, 'scanner' se
    -- l'ha trovato la ricerca automatica. Sono due cose che si leggono in modo
    -- diverso - una e' una convinzione, l'altra e' una statistica - e vanno
    -- tenute distinte invece che dedotte dall'etichetta.
    origine    TEXT DEFAULT 'mia',
    added_at   INTEGER,
    win_rate   REAL DEFAULT 0,
    pnl_usd    REAL DEFAULT 0,
    enabled    INTEGER DEFAULT 1,
    last_block INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS alerts (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    token_address TEXT NOT NULL,
    kind          TEXT NOT NULL,
    score         REAL DEFAULT 0,
    ts            INTEGER,
    payload_json  TEXT DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts DESC);

-- Quando e' nata la pozza di liquidita' di ogni token incontrato. Serve a
-- distinguere un lancio da una cosa che sta li' da sempre: su Robinhood Chain
-- girano anche azioni tokenizzate (AMD, Intel, Micron) con pozze vecchie di
-- settimane, che non sono lanci e non c'entrano niente con questo scanner.
CREATE TABLE IF NOT EXISTS token_pools (
    token_address   TEXT PRIMARY KEY,
    symbol          TEXT DEFAULT '',
    pair_created_at INTEGER DEFAULT 0,
    natura          TEXT DEFAULT '',
    updated_at      INTEGER
);

-- Chi ha ricevuto un token nei suoi primi minuti di vita. E' un fatto storico:
-- una volta letto non cambia mai piu', eppure veniva riletto dalla blockchain a
-- ogni ricerca, sempre uguale. Tenerlo qui serve a due cose: la ricerca smette
-- di rifare il lavoro gia' fatto, e il voto di un portafoglio appena aggiunto
-- si calcola all'istante invece di aspettare il giro successivo.
CREATE TABLE IF NOT EXISTS early_buyers (
    token_address TEXT NOT NULL,
    wallet        TEXT NOT NULL,
    PRIMARY KEY (token_address, wallet)
);
CREATE INDEX IF NOT EXISTS idx_early_wallet ON early_buyers(wallet);

-- Quali monete sono gia' state lette. Sta a parte perche' una moneta puo'
-- legittimamente non avere nessun primo acquirente leggibile, e senza questa
-- riga verrebbe riletta all'infinito. E' anche il denominatore del voto: dice
-- su quante monete andate bene e' stata fatta la misura.
CREATE TABLE IF NOT EXISTS early_scans (
    token_address TEXT PRIMARY KEY,
    buyers        INTEGER DEFAULT 0,
    scanned_at    INTEGER
);

-- Il voto di un portafoglio: delle monete che gli sono entrate, quante sono
-- andate bene. Si tiene calcolato perche' ricavarlo richiede lo storico dei
-- prezzi di ogni moneta, e non si puo' rifare a ogni apertura della dashboard.
CREATE TABLE IF NOT EXISTS wallet_voti (
    wallet        TEXT PRIMARY KEY,
    valutate      INTEGER DEFAULT 0,
    andate_bene   INTEGER DEFAULT 0,
    picco_medio   REAL DEFAULT 0,
    senza_storico INTEGER DEFAULT 0,
    senza_pozza   INTEGER DEFAULT 0,
    aggiornato_at INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);

-- Impostazioni modificabili dalla dashboard. Restano separate da `meta`, che
-- serve allo stato interno dello scanner: qui c'e' solo cio' che l'utente puo'
-- cambiare, e vince su quanto scritto nel file di configurazione.
CREATE TABLE IF NOT EXISTS settings (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    updated_at INTEGER
);
"""


class Store:
    def __init__(self, path: str | None = None) -> None:
        self.path = path or settings.db_path
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        # WAL evita che una lettura dalla dashboard blocchi una scrittura del worker.
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=NORMAL")
        with self._lock:
            self._conn.executescript(SCHEMA)
            self._conn.commit()
        self._migrate()
        log.info("database pronto: %s", self.path)

    def _migrate(self) -> None:
        """Aggiunge le colonne comparse dopo la creazione del database.

        `CREATE TABLE IF NOT EXISTS` non tocca una tabella che esiste gia', per
        cui su un'installazione avviata prima le colonne nuove mancherebbero e
        ogni scrittura fallirebbe. Aggiungerle qui evita di dover cancellare i
        dati a ogni aggiornamento.
        """
        attese = {
            "candidates": {
                "alert_kind": "TEXT DEFAULT ''",
                "peak_notified": "REAL DEFAULT 0",
                "liq_notified": "REAL DEFAULT 0",
                "liq_riferimento": "REAL DEFAULT 0",
                "pool_version": "TEXT DEFAULT ''",
                "prezzo_riferimento": "REAL DEFAULT 0",
                "uscite_notified": "REAL",
            },
            "token_pools": {"natura": "TEXT DEFAULT ''"},
            "tracked_wallets": {"origine": "TEXT DEFAULT 'mia'"},
        }
        for tabella, colonne in attese.items():
            with self._lock:
                presenti = {
                    row["name"] for row in self._conn.execute(f"PRAGMA table_info({tabella})")
                }
            for nome, definizione in colonne.items():
                if nome not in presenti:
                    log.info("aggiungo la colonna %s.%s", tabella, nome)
                    self._exec(f"ALTER TABLE {tabella} ADD COLUMN {nome} {definizione}")

        # Chi c'era prima della colonna `origine` va classificato una volta
        # sola, e l'unico indizio rimasto e' l'etichetta: la ricerca automatica
        # scrive "early su N vincenti", la configurazione scrive "da .env".
        # Tutto il resto - etichette scritte a mano e, soprattutto, quelle
        # vuote - e' roba scelta da una persona.
        self._exec(
            "UPDATE tracked_wallets SET origine = 'scanner' "
            "WHERE origine = 'mia' AND label LIKE 'early su %'"
        )

        # I nomi delle origini sono passati all'inglese dopo il primo rilascio:
        # le righe scritte nel frattempo finirebbero fuori da ogni filtro.
        for vecchio, nuovo in (("balene", "whales"), ("scanner_balene", "scanner_whales")):
            self._exec(
                "UPDATE candidates SET alert_kind = ? WHERE alert_kind = ?", (nuovo, vecchio)
            )

    def wipe(self) -> dict[str, int]:
        """Svuota i dati raccolti e riparte da zero. Ritorna cosa ha buttato.

        Non tocca due cose, per motivi opposti:

        - `settings`, le soglie regolate dalla dashboard, perche' sono la
          configurazione e non i dati. Hanno gia' il loro tasto di ripristino.
        - `meta`, dove stanno le factory della chain (che al primo avvio
          costano minuti di lettura) e il blocco a cui e' arrivata la
          scansione. Tenere il blocco e' il punto: si riparte da adesso
          invece di rileggere il passato che si e' appena buttato.
        """
        buttati = {}
        for tabella in ("candidates", "alerts", "wallet_events", "tracked_wallets"):
            row = self._query_one(f"SELECT COUNT(*) AS n FROM {tabella}") or {}
            buttati[tabella] = row.get("n") or 0
            self._exec(f"DELETE FROM {tabella}")
        # Lo spazio liberato torna al disco: sul server ce n'e' poco.
        with self._lock:
            self._conn.execute("VACUUM")
        log.warning("database svuotato su richiesta: %s", buttati)
        return buttati

    def sostituisci(self, sorgente: str) -> dict:
        """Rimpiazza il database con un altro file, a servizio acceso.

        Si fa tutto sotto al lucchetto che protegge ogni lettura e scrittura,
        quindi nessun ciclo puo' trovarsi con la connessione chiusa a meta' di
        una query: al massimo aspetta il tempo di una copia di file.

        Prima di sovrascrivere si mette da parte quello attuale. Un ripristino
        e' l'operazione in cui e' piu' facile accorgersi un secondo dopo di
        aver scelto il backup sbagliato, e senza la copia non ci sarebbe modo
        di tornare indietro.
        """
        salvataggio = f"{self.path}.prima-del-ripristino"
        with self._lock:
            # La copia si fa con il backup di SQLite e non con `cp`: in WAL le
            # ultime scritture stanno in un file a parte e si salverebbe un
            # database indietro di qualche minuto.
            vecchio = sqlite3.connect(salvataggio)
            try:
                self._conn.backup(vecchio)
            finally:
                vecchio.close()

            self._conn.close()
            try:
                for coda in ("", "-wal", "-shm"):
                    Path(self.path + coda).unlink(missing_ok=True)
                shutil.copy2(sorgente, self.path)
            finally:
                # Qualunque cosa vada storta, si riapre: un motore senza
                # database non riparte piu' da solo.
                self._conn = sqlite3.connect(self.path, check_same_thread=False)
                self._conn.row_factory = sqlite3.Row
                self._conn.execute("PRAGMA journal_mode=WAL")
                self._conn.execute("PRAGMA synchronous=NORMAL")
                self._conn.executescript(SCHEMA)
                self._conn.commit()

        # Il backup puo' venire da una versione precedente del programma: le
        # colonne aggiunte nel frattempo vanno rimesse, o le scritture
        # fallirebbero tutte subito dopo il ripristino.
        self._migrate()
        log.warning("database ripristinato da %s", sorgente)
        return {
            "candidati": (self._query_one("SELECT COUNT(*) AS n FROM candidates") or {}).get("n", 0),
            "whales": (
                self._query_one("SELECT COUNT(*) AS n FROM tracked_wallets") or {}
            ).get("n", 0),
            "copia_di_sicurezza": salvataggio,
        }

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # -- helper generici ----------------------------------------------------

    def _exec(self, sql: str, params: Iterable = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._conn.execute(sql, tuple(params))
            self._conn.commit()
            return cur

    def _many(self, sql: str, righe: list) -> None:
        """Tante scritture uguali in una transazione sola.

        Gli elenchi di primi acquirenti arrivano a centinaia di righe per
        moneta: farne una `_exec` ciascuna vorrebbe dire altrettanti commit.
        """
        if not righe:
            return
        with self._lock:
            self._conn.executemany(sql, righe)
            self._conn.commit()

    def _query(self, sql: str, params: Iterable = ()) -> list[dict]:
        with self._lock:
            rows = self._conn.execute(sql, tuple(params)).fetchall()
        return [dict(r) for r in rows]

    def _query_one(self, sql: str, params: Iterable = ()) -> dict | None:
        rows = self._query(sql, params)
        return rows[0] if rows else None

    # -- meta ---------------------------------------------------------------

    def get_meta(self, key: str, default: str = "") -> str:
        row = self._query_one("SELECT value FROM meta WHERE key = ?", (key,))
        return row["value"] if row else default

    def set_meta(self, key: str, value: str) -> None:
        self._exec(
            "INSERT INTO meta(key, value) VALUES(?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )

    # -- impostazioni -------------------------------------------------------

    def get_settings(self) -> dict[str, str]:
        return {row["key"]: row["value"] for row in self._query("SELECT key, value FROM settings")}

    def set_setting(self, key: str, value: str) -> None:
        self._exec(
            "INSERT INTO settings(key, value, updated_at) VALUES(?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value, "
            "updated_at = excluded.updated_at",
            (key, value, now()),
        )

    def clear_setting(self, key: str) -> None:
        """Rimuove la personalizzazione: il valore torna a quello del file."""
        self._exec("DELETE FROM settings WHERE key = ?", (key,))

    # -- candidati ----------------------------------------------------------

    def get_candidate(self, token_address: str) -> dict | None:
        return self._query_one(
            "SELECT * FROM candidates WHERE token_address = ?", (token_address.lower(),)
        )

    def upsert_candidate(self, data: dict[str, Any]) -> None:
        """Inserisce o aggiorna un candidato.

        `first_seen` e i campi dello snapshot al momento dell'alert non vengono
        mai sovrascritti da un update: servono a misurare la performance.
        """
        data = dict(data)
        data["token_address"] = data["token_address"].lower()
        data.setdefault("first_seen", now())
        data["last_updated"] = now()
        for key in ("safety_json", "breakdown_json"):
            if key in data and not isinstance(data[key], str):
                data[key] = json.dumps(data[key], separators=(",", ":"))

        columns = list(data.keys())
        placeholders = ", ".join("?" for _ in columns)
        # first_seen e lo snapshot dell'alert sono immutabili dopo il primo insert.
        immutable = {"token_address", "first_seen", "mcap_at_alert", "price_at_alert", "alerted_at"}
        updates = ", ".join(f"{c} = excluded.{c}" for c in columns if c not in immutable)
        sql = (
            f"INSERT INTO candidates ({', '.join(columns)}) VALUES ({placeholders}) "
            f"ON CONFLICT(token_address) DO UPDATE SET {updates}"
        )
        self._exec(sql, [data[c] for c in columns])

    def mark_alerted(
        self, token_address: str, score: float, price: float, mcap: float,
        kind: str = "scanner",
    ) -> None:
        self._exec(
            "UPDATE candidates SET status = 'alerted', alerted_at = ?, score = ?, "
            "price_at_alert = ?, mcap_at_alert = ?, peak_price = MAX(peak_price, ?), "
            "alert_kind = ? WHERE token_address = ?",
            (now(), score, price, mcap, price, kind, token_address.lower()),
        )

    def set_alert_kind(self, token_address: str, kind: str) -> None:
        """Riclassifica un alert gia' inviato senza toccare il resto.

        L'origine non e' scolpita nella pietra: le balene entrano ed escono, e
        la scheda deve dire com'e' il token adesso, non com'era.
        """
        self._exec(
            "UPDATE candidates SET alert_kind = ? WHERE token_address = ?",
            (kind, token_address.lower()),
        )

    def update_peak(self, token_address: str, price: float) -> None:
        """Aggiorna il massimo raggiunto dopo l'alert e il multiplo corrispondente."""
        self._exec(
            "UPDATE candidates SET peak_price = MAX(peak_price, ?), "
            "peak_multiple = CASE WHEN price_at_alert > 0 "
            "  THEN MAX(peak_price, ?) / price_at_alert ELSE 0 END "
            "WHERE token_address = ?",
            (price, price, token_address.lower()),
        )

    def set_peak_notified(self, token_address: str, multiple: float) -> None:
        """Segna fin dove si e' gia' avvisato, per non ripetere lo stesso 2x."""
        self._exec(
            "UPDATE candidates SET peak_notified = ? WHERE token_address = ?",
            (multiple, token_address.lower()),
        )

    def tokens_da_sorvegliare(self, entro_ore: int = 12, limit: int = 80) -> list[dict]:
        """I token su cui vale la pena guardare la pozza ogni pochi secondi.

        Non tutti: solo quelli che possono avere ancora qualcuno dentro, cioe'
        i salvati e quelli segnalati da poco. Guardare per giorni una moneta
        che nessuno segue costerebbe chiamate senza dire niente di nuovo.
        """
        return self._query(
            "SELECT token_address, symbol, score, alert_kind, alerted_at, "
            "liquidity_usd, price_usd, liq_notified, liq_riferimento, prezzo_riferimento, "
            "price_at_alert, peak_notified, uscite_notified "
            "FROM candidates "
            "WHERE (watchlisted = 1 OR (status = 'alerted' AND alerted_at > ?)) "
            "ORDER BY alerted_at DESC LIMIT ?",
            (now() - entro_ore * 3600, limit),
        )

    def set_riferimento_pozza(self, token_address: str, liquidita: float, prezzo: float) -> None:
        """Sposta in alto il punto da cui si misura, e riapre gli avvisi.

        Se la pozza e' cresciuta, il massimo di prima non e' piu' il metro
        giusto. E siccome si riparte da un livello nuovo, le soglie gia'
        annunciate vanno riazzerate: altrimenti una moneta che cala, risale e
        ricade non avviserebbe mai la seconda volta.
        """
        self._exec(
            "UPDATE candidates SET liq_riferimento = ?, prezzo_riferimento = ?, "
            "liq_notified = 0 WHERE token_address = ?",
            (liquidita, prezzo, token_address.lower()),
        )

    def set_liq_notified(self, token_address: str, frazione: float) -> None:
        """Segna quanta pozza era gia' sparita l'ultima volta che si e' avvisato."""
        self._exec(
            "UPDATE candidates SET liq_notified = ? WHERE token_address = ?",
            (frazione, token_address.lower()),
        )

    def recently_alerted(self, token_address: str, within_seconds: int) -> bool:
        row = self._query_one(
            "SELECT alerted_at FROM candidates WHERE token_address = ?", (token_address.lower(),)
        )
        if not row or not row["alerted_at"]:
            return False
        return (now() - row["alerted_at"]) < within_seconds

    def list_candidates(
        self, status: str | None = None, limit: int = 100, min_score: float = 0
    ) -> list[dict]:
        sql = "SELECT * FROM candidates WHERE score >= ?"
        params: list[Any] = [min_score]
        if status:
            sql += " AND status = ?"
            params.append(status)
        sql += " ORDER BY score DESC, last_updated DESC LIMIT ?"
        params.append(limit)
        return self._query(sql, params)

    def list_watchlist(self, limit: int = 100) -> list[dict]:
        """I token messi da parte con la stella, dal piu' recente.

        In ordine di quando sono stati visti l'ultima volta e non di punteggio:
        chi li ha salvati li ha scelti a mano, quindi il punteggio ha gia'
        detto la sua e conta di piu' sapere cos'e' successo dopo.
        """
        return self._query(
            "SELECT * FROM candidates WHERE watchlisted = 1 "
            "ORDER BY last_updated DESC LIMIT ?",
            (limit,),
        )

    def rejection_stats(self, within_seconds: int = 86400) -> list[dict]:
        """Perche' i token vengono scartati, dal motivo piu' frequente.

        Serve a capire quale filtro sta facendo il lavoro: se novanta scarti
        su cento sono "troppo giovane", la manopola da girare e' quella e non
        un'altra. Il motivo c'era gia' salvato, ma non lo leggeva nessuno.
        """
        return self._query(
            "SELECT reject_reason AS motivo, COUNT(*) AS n FROM candidates "
            "WHERE status = 'rejected' AND reject_reason != '' AND last_updated > ? "
            "GROUP BY reject_reason ORDER BY n DESC",
            (now() - within_seconds,),
        )

    def list_recent(self, limit: int = 100, include_pending: bool = False) -> list[dict]:
        """Candidati valutabili, dal piu' recente.

        I pool ancora in attesa di dati sono esclusi per default: verrebbero
        aggiornati a ogni giro e finirebbero per occupare tutta la lista con
        righe vuote.
        """
        excluded = "('rejected')" if include_pending else "('rejected', 'pending')"
        return self._query(
            f"SELECT * FROM candidates WHERE status NOT IN {excluded} "
            "ORDER BY score DESC, last_updated DESC LIMIT ?",
            (limit,),
        )

    def list_pending(self, max_age_seconds: int = 21_600, limit: int = 80) -> list[dict]:
        """Token visti on-chain ma non ancora indicizzati dagli aggregatori.

        Un pool creato pochi secondi fa non ha ancora prezzo ne' volume da
        nessuna parte. Restano in attesa e vengono ricontrollati a ogni giro
        finche' i dati compaiono, o finche' scadono.
        """
        return self._query(
            "SELECT * FROM candidates WHERE status = 'pending' AND first_seen > ? "
            "ORDER BY first_seen DESC LIMIT ?",
            (now() - max_age_seconds, limit),
        )

    def expire_pending(self, max_age_seconds: int = 21_600) -> int:
        """Archivia i pool rimasti senza dati: quasi sempre lanci mai partiti."""
        cur = self._exec(
            "UPDATE candidates SET status = 'rejected', reject_reason = 'mai_partito' "
            "WHERE status = 'pending' AND first_seen <= ?",
            (now() - max_age_seconds,),
        )
        return cur.rowcount

    def active_tokens_for_tracking(self, limit: int = 200) -> list[dict]:
        """Token da riaggiornare: quelli su cui abbiamo mandato un alert o in watchlist."""
        return self._query(
            "SELECT token_address, pair_address, symbol, price_at_alert, peak_notified, "
            "score, alert_kind, liquidity_usd, price_usd, liq_notified, "
            "liq_riferimento, prezzo_riferimento FROM candidates "
            "WHERE (status = 'alerted' OR watchlisted = 1) AND last_updated > ? LIMIT ?",
            (now() - 7 * 86400, limit),
        )

    def set_watchlist(self, token_address: str, watched: bool) -> None:
        self._exec(
            "UPDATE candidates SET watchlisted = ? WHERE token_address = ?",
            (1 if watched else 0, token_address.lower()),
        )

    # -- wallet -------------------------------------------------------------

    def add_tracked_wallet(self, address: str, label: str = "", pnl_usd: float = 0,
                           win_rate: float = 0, origine: str = "mia") -> None:
        """Aggiunge o aggiorna un portafoglio seguito.

        `origine` non viene sovrascritta quando il portafoglio esiste gia': se
        una persona l'aveva scelto a mano e piu' tardi la ricerca automatica lo
        ritrova, resta suo. Il contrario - declassare una scelta a ritrovamento
        - cancellerebbe un'informazione che solo lui aveva.
        """
        self._exec(
            "INSERT INTO tracked_wallets(address, label, added_at, pnl_usd, win_rate, origine) "
            "VALUES(?, ?, ?, ?, ?, ?) ON CONFLICT(address) DO UPDATE SET "
            "label = excluded.label, pnl_usd = excluded.pnl_usd, win_rate = excluded.win_rate",
            (address.lower(), label, now(), pnl_usd, win_rate, origine),
        )

    def remove_tracked_wallet(self, address: str) -> None:
        self._exec("DELETE FROM tracked_wallets WHERE address = ?", (address.lower(),))

    def list_tracked_wallets(self, enabled_only: bool = True) -> list[dict]:
        sql = "SELECT * FROM tracked_wallets"
        if enabled_only:
            sql += " WHERE enabled = 1"
        sql += " ORDER BY pnl_usd DESC"
        return self._query(sql)

    # -- chi e' arrivato presto ---------------------------------------------

    def early_buyers_noti(self, token_address: str) -> set[str] | None:
        """Chi era presto su questa moneta, se e' gia' stata letta.

        `None` vuol dire "mai letta", che e' diverso da "letta e non c'era
        nessuno": senza distinguerli una moneta senza primi acquirenti verrebbe
        riletta dalla blockchain per sempre.
        """
        token = token_address.lower()
        if not self._query_one(
            "SELECT 1 AS c FROM early_scans WHERE token_address = ?", (token,)
        ):
            return None
        return {
            r["wallet"] for r in
            self._query("SELECT wallet FROM early_buyers WHERE token_address = ?", (token,))
        }

    def salva_early_buyers(self, token_address: str, wallets: Iterable[str]) -> None:
        token = token_address.lower()
        righe = [(token, w.lower()) for w in wallets]
        self._many(
            "INSERT OR IGNORE INTO early_buyers(token_address, wallet) VALUES(?, ?)", righe
        )
        self._exec(
            "INSERT INTO early_scans(token_address, buyers, scanned_at) VALUES(?, ?, ?) "
            "ON CONFLICT(token_address) DO UPDATE SET "
            "buyers = excluded.buyers, scanned_at = excluded.scanned_at",
            (token, len(righe), now()),
        )

    def voti_early(self) -> tuple[dict[str, int], int]:
        """Su quante monete andate bene ogni indirizzo e' arrivato presto.

        Ritorna anche il totale delle monete esaminate: senza denominatore uno
        zero non si sa leggere. "Zero su venticinque" dice qualcosa, "zero" da
        solo puo' voler dire tanto un portafoglio scarso quanto uno aggiunto
        cinque minuti fa.
        """
        totale = (self._query_one("SELECT COUNT(*) AS n FROM early_scans") or {}).get("n", 0)
        righe = self._query("SELECT wallet, COUNT(*) AS n FROM early_buyers GROUP BY wallet")
        return {r["wallet"]: r["n"] for r in righe}, totale

    def token_entrati(self, wallet: str, limite: int = 25) -> list[dict]:
        """Le monete finite in questo portafoglio, dalla piu' recente.

        Comprate o consegnate: per giudicare se una scelta valeva non conta chi
        ha premuto il tasto. Le ricariche - stablecoin e token di scambio - non
        sono posizioni e restano fuori.

        Porta con se' la pozza, che serve a chiedere lo storico dei prezzi: se
        non la conosciamo quella moneta non e' giudicabile, e va detto.
        """
        segnaposto = ",".join("?" * len(IGNORED_SYMBOLS))
        return self._query(
            "SELECT e.token_address, MAX(e.symbol) AS symbol, MIN(e.ts) AS entrato, "
            "       MAX(c.pair_address) AS pair_address "
            "FROM wallet_events e "
            "LEFT JOIN candidates c ON c.token_address = e.token_address "
            "WHERE e.wallet = ? AND e.direction IN ('buy', 'arrivo') "
            f"  AND UPPER(COALESCE(e.symbol, '')) NOT IN ({segnaposto}) "
            "GROUP BY e.token_address ORDER BY entrato DESC LIMIT ?",
            [wallet.lower(), *sorted(IGNORED_SYMBOLS), limite],
        )

    def salva_voto_wallet(self, wallet: str, voto) -> None:
        self._exec(
            "INSERT INTO wallet_voti(wallet, valutate, andate_bene, picco_medio, "
            "  senza_storico, senza_pozza, aggiornato_at) VALUES(?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(wallet) DO UPDATE SET valutate = excluded.valutate, "
            "  andate_bene = excluded.andate_bene, picco_medio = excluded.picco_medio, "
            "  senza_storico = excluded.senza_storico, senza_pozza = excluded.senza_pozza, "
            "  aggiornato_at = excluded.aggiornato_at",
            (wallet.lower(), voto.valutate, voto.andate_bene, round(voto.picco_medio, 2),
             voto.senza_storico, voto.senza_pozza, now()),
        )

    def voti_wallet(self) -> dict[str, dict]:
        return {r["wallet"]: r for r in self._query("SELECT * FROM wallet_voti")}

    def set_wallet_cursor(self, address: str, block_number: int) -> None:
        self._exec(
            "UPDATE tracked_wallets SET last_block = ? WHERE address = ?",
            (block_number, address.lower()),
        )

    def record_wallet_event(self, event: dict[str, Any]) -> bool:
        """Registra un movimento. Ritorna False se era gia' noto (dedup su tx_hash)."""
        cur = self._exec(
            "INSERT OR IGNORE INTO wallet_events"
            "(wallet, token_address, symbol, direction, amount, tx_hash, block_number, ts) "
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
            (
                event["wallet"].lower(),
                event["token_address"].lower(),
                event.get("symbol", ""),
                event.get("direction", "buy"),
                event.get("amount", 0),
                event.get("tx_hash", ""),
                event.get("block_number", 0),
                event.get("ts", now()),
            ),
        )
        return cur.rowcount > 0

    # Solo i lanci: fuori le azioni tokenizzate. Il criterio non e' l'eta' ma
    # l'identita': comprare cinque azioni insieme e' farsi un portafoglio, non
    # rastrellare meme coin, e una meme coin di quaranta giorni resta una meme
    # coin. Un token di cui non sappiamo niente conta: meglio uno in piu' che
    # perdere un lancio vero.
    _SOLO_LANCI = (
        " AND token_address NOT IN ("
        "   SELECT token_address FROM token_pools WHERE natura = 'azione'"
        " )"
    )

    # Chi compra piu' di cosi' token diversi in un giorno non sta scegliendo:
    # sta rastrellando. Vale come sotto-query dentro i conteggi, cosi' i bot
    # non entrano nel punteggio anche se sono ancora nella lista.
    _NON_BOT = (
        " AND wallet NOT IN ("
        "   SELECT wallet FROM wallet_events"
        "   WHERE direction = 'buy' AND ts > ?" + _SOLO_LANCI +
        "   GROUP BY wallet HAVING COUNT(DISTINCT token_address) > ?"
        " )"
    )

    def set_token_natura(self, token_address: str, natura: str, symbol: str = "") -> None:
        """Annota cos'e' un token: azione, copia di un'azione, o lancio.

        Non cambia mai nel tempo, quindi si chiede una volta sola e si tiene
        per sempre: ogni verifica costa una chiamata all'explorer.
        """
        self._exec(
            "INSERT INTO token_pools(token_address, symbol, natura, updated_at) "
            "VALUES(?, ?, ?, ?) ON CONFLICT(token_address) DO UPDATE SET "
            "natura = excluded.natura, updated_at = excluded.updated_at, "
            "symbol = CASE WHEN excluded.symbol != '' THEN excluded.symbol "
            "              ELSE token_pools.symbol END",
            (token_address.lower(), symbol, natura, now()),
        )

    def get_token_natura(self, token_address: str) -> str:
        row = self._query_one(
            "SELECT natura FROM token_pools WHERE token_address = ?",
            (token_address.lower(),),
        )
        return (row["natura"] if row else "") or ""

    def e_un_lancio(self, token_address: str) -> bool:
        """Falso solo per le azioni tokenizzate riconosciute con certezza."""
        return self.get_token_natura(token_address) != "azione"

    def wallet_activity(
        self, within_seconds: int = 86400, solo_lanci: bool = False
    ) -> dict[str, int]:
        """Quanti lanci diversi ha comprato ogni wallet tracciato.

        E' il numero che smaschera i bot: una balena vera compra due o tre cose
        al giorno, uno sniper automatico ne compra decine. Le azioni
        tokenizzate non contano: comprarne cinque insieme e' un portafoglio.
        """
        sql = (
            "SELECT wallet, COUNT(DISTINCT token_address) AS n FROM wallet_events "
            "WHERE direction = 'buy' AND ts > ?"
        )
        params: list[Any] = [now() - within_seconds]
        if solo_lanci:
            sql += self._SOLO_LANCI
        rows = self._query(sql + " GROUP BY wallet", params)
        return {row["wallet"]: row["n"] for row in rows}

    def count_distinct_wallet_buyers(
        self, token_address: str, within_seconds: int = 86400, max_tokens_per_day: int = 0
    ) -> int:
        """Quanti wallet tracciati diversi hanno comprato questo token di recente.

        E' il segnale di convergenza: un wallet bravo puo' sbagliare, tre che
        comprano la stessa cosa in poche ore molto meno. Con
        `max_tokens_per_day` i wallet troppo attivi vengono ignorati.
        """
        sql = (
            "SELECT COUNT(DISTINCT wallet) AS n FROM wallet_events "
            "WHERE token_address = ? AND direction = 'buy' AND ts > ?"
        )
        params: list[Any] = [token_address.lower(), now() - within_seconds]
        if max_tokens_per_day > 0:
            sql += self._NON_BOT
            params += [now() - 86400, max_tokens_per_day]
        row = self._query_one(sql, params)
        return row["n"] if row else 0

    def count_wallet_holders(
        self, token_address: str, within_seconds: int = 86400, max_tokens_per_day: int = 0
    ) -> int:
        """Balene entrate di recente e non ancora uscite.

        Servono tutte e due le condizioni. Senza la vendita, una balena che ha
        gia' scaricato continuerebbe a valere venticinque punti. Senza la
        finestra, conterebbe anche chi ha comprato quaranta giorni fa e si e'
        dimenticato il token nel portafoglio: e siccome la prima sincronizzazione
        carica tutto lo storico dei wallet tracciati, quasi ogni token che
        avessero mai toccato risulterebbe "con le balene dentro".

        Un sacchetto vecchio non e' un segnale su cosa comprare adesso.
        """
        sql = (
            "SELECT COUNT(*) AS n FROM ("
            "  SELECT wallet, direction, ts, ROW_NUMBER() OVER ("
            "    PARTITION BY wallet ORDER BY ts DESC, id DESC"
            "  ) AS rn"
            # Solo acquisti e vendite decidono se una whale e' dentro o
            # fuori. Un token arrivato senza pagare, o mandato via senza
            # incassare, non e' una posizione presa ne' chiusa: se contasse,
            # un airdrop ricevuto dopo un acquisto vero coprirebbe l'acquisto
            # e la whale sparirebbe dal conteggio.
            "  FROM wallet_events"
            "  WHERE token_address = ? AND direction IN ('buy', 'sell')"
            ") WHERE rn = 1 AND direction = 'buy' AND ts > ?"
        )
        params: list[Any] = [token_address.lower(), now() - within_seconds]
        if max_tokens_per_day > 0:
            sql += self._NON_BOT
            params += [now() - 86400, max_tokens_per_day]
        row = self._query_one(sql, params)
        return row["n"] if row else 0

    def balene_dentro_e_fuori(
        self, token_address: str, within_seconds: int = 86400, max_tokens_per_day: int = 0
    ) -> dict[str, int]:
        """Delle balene che sono entrate, quante ci sono ancora e quante no.

        `count_wallet_holders` da solo non basta per accorgersi di un'uscita.
        Li' la finestra si applica all'**ultimo** movimento, quindi una balena
        che ha comprato venticinque ore fa e non ha piu' toccato niente sparisce
        dal conteggio esattamente come una che ha venduto: guardando calare quel
        numero si manderebbero avvisi di fuga a gente che sta ancora dentro e
        non ha fatto niente.

        Qui la finestra si applica all'**acquisto**, che e' il fatto che decide
        se una balena c'entra con questo token adesso. Poi si guarda l'ultimo
        movimento per sapere da che parte sta. Cosi' i due numeri si sommano
        sempre allo stesso totale finche' non entra qualcuno di nuovo, e un calo
        del primo vuol dire davvero che qualcuno e' uscito.

        Torna anche quando e' avvenuta l'ultima vendita: un'uscita di ieri non
        e' una notizia, e senza quella data non ci sarebbe modo di distinguerla
        da una di adesso.
        """
        sql = (
            "SELECT "
            "  SUM(ultima = 'buy')  AS dentro, "
            "  SUM(ultima = 'sell') AS fuori, "
            "  MAX(CASE WHEN ultima = 'sell' THEN ultimo_movimento END) AS uscita_recente "
            "FROM ("
            "  SELECT wallet, "
            "    MAX(CASE WHEN direction = 'buy' THEN ts END) AS acquisto, "
            "    MAX(ts) AS ultimo_movimento, "
            "    ("
            "      SELECT e2.direction FROM wallet_events e2"
            "      WHERE e2.token_address = e.token_address AND e2.wallet = e.wallet"
            "        AND e2.direction IN ('buy', 'sell')"
            "      ORDER BY e2.ts DESC, e2.id DESC LIMIT 1"
            "    ) AS ultima "
            "  FROM wallet_events e"
            "  WHERE token_address = ? AND direction IN ('buy', 'sell')"
            "  GROUP BY wallet"
            ") WHERE acquisto > ?"
        )
        params: list[Any] = [token_address.lower(), now() - within_seconds]
        if max_tokens_per_day > 0:
            sql += self._NON_BOT
            params += [now() - 86400, max_tokens_per_day]
        row = self._query_one(sql, params) or {}
        return {
            "dentro": int(row.get("dentro") or 0),
            "fuori": int(row.get("fuori") or 0),
            "uscita_recente": int(row.get("uscita_recente") or 0),
        }

    def set_uscite_notified(self, token_address: str, frazione: float) -> None:
        """Segna quanta parte delle balene era gia' uscita all'ultimo avviso."""
        self._exec(
            "UPDATE candidates SET uscite_notified = ? WHERE token_address = ?",
            (frazione, token_address.lower()),
        )

    def recent_wallet_events(self, limit: int = 50) -> list[dict]:
        return self._query(
            "SELECT * FROM wallet_events ORDER BY ts DESC LIMIT ?", (limit,)
        )

    # -- alert --------------------------------------------------------------

    def record_alert(self, token_address: str, kind: str, score: float, payload: dict) -> None:
        self._exec(
            "INSERT INTO alerts(token_address, kind, score, ts, payload_json) VALUES(?, ?, ?, ?, ?)",
            (token_address.lower(), kind, score, now(), json.dumps(payload, separators=(",", ":"))),
        )

    def notifiche(self, limit: int = 50) -> list[dict]:
        """Gli avvisi da mostrare nella scheda Avvisi della dashboard.

        Solo le due cose che chiedono di fare qualcosa adesso invece di
        guardare un'occasione: la pozza che si ritira e le balene che escono.
        Gli alert sui candidati hanno gia' la loro scheda e non vanno mescolati
        qui, o la cosa urgente si perde in mezzo a quelle da leggere con calma.
        """
        # `alerted_at` viaggia insieme: e' l'altro capo della misura. Con la
        # sola ora del ritiro non si sa quanto e' durata la moneta, che e' il
        # numero da cui dipende se questo scanner serve a qualcosa.
        return self._query(
            "SELECT a.*, c.symbol AS symbol_candidato, c.liquidity_usd, "
            "c.alert_kind, c.alerted_at "
            "FROM alerts a LEFT JOIN candidates c ON c.token_address = a.token_address "
            "WHERE a.kind IN ('pozza_ritirata', 'balene_uscite') "
            "ORDER BY a.ts DESC LIMIT ?",
            (limit,),
        )

    def recent_alerts(self, limit: int = 50) -> list[dict]:
        return self._query(
            "SELECT a.*, c.symbol, c.peak_multiple, c.mcap_at_alert "
            "FROM alerts a LEFT JOIN candidates c ON c.token_address = a.token_address "
            "ORDER BY a.ts DESC LIMIT ?",
            (limit,),
        )

    # -- statistiche --------------------------------------------------------

    def stats(self) -> dict:
        """Numeri sintetici per la dashboard, incluso il tasso di riuscita reale."""
        totals = self._query_one(
            "SELECT COUNT(*) AS seen, "
            "SUM(status = 'alerted') AS alerted, "
            "SUM(status = 'rejected') AS rejected "
            "FROM candidates"
        ) or {}
        # `zero` sta nella stessa interrogazione delle altre percentuali perche'
        # deve avere lo stesso denominatore: senza, il numero delle monete
        # morte non si potrebbe confrontare con quello delle riuscite. Mezzo
        # migliaio di dollari di pozza vuol dire che uscire non e' piu'
        # possibile per nessuna cifra che valga la pena metterci: e' morta
        # anche se il prezzo segna ancora qualcosa.
        perf = self._query_one(
            "SELECT COUNT(*) AS n, AVG(peak_multiple) AS avg_peak, "
            "SUM(peak_multiple >= 2) AS x2, SUM(peak_multiple >= 5) AS x5, "
            "SUM(peak_multiple >= 10) AS x10, "
            f"SUM(liquidity_usd < {POZZA_MORTA}) AS zero "
            "FROM candidates WHERE status = 'alerted' AND price_at_alert > 0"
        ) or {}
        # Solo le segnalazioni di monete. Nella stessa tabella finiscono anche
        # gli avvisi di uscita - la pozza che si ritira, le balene che vendono -
        # che sono il contrario di una chiamata: contarli qui gonfiava il numero
        # proprio mentre serviva a capire quante monete lo scanner sta chiamando
        # davvero.
        last_24h = self._query_one(
            "SELECT COUNT(*) AS n FROM alerts WHERE ts > ? "
            "AND kind NOT IN ('pozza_ritirata', 'balene_uscite')",
            (now() - 86400,),
        ) or {}
        return {
            "tokens_seen": totals.get("seen") or 0,
            "alerted": totals.get("alerted") or 0,
            "rejected": totals.get("rejected") or 0,
            "alerts_24h": last_24h.get("n") or 0,
            "tracked_calls": perf.get("n") or 0,
            "avg_peak_multiple": round(perf.get("avg_peak") or 0, 2),
            "hits_2x": perf.get("x2") or 0,
            "hits_5x": perf.get("x5") or 0,
            "hits_10x": perf.get("x10") or 0,
            "andate_a_zero": perf.get("zero") or 0,
        }


_store: Store | None = None


def get_store() -> Store:
    global _store
    if _store is None:
        _store = Store()
    return _store
