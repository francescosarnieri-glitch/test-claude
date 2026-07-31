"""Persistenza su SQLite.

Tiene lo stato tra un riavvio e l'altro: candidati visti, alert gia' mandati
(per non spammare) e, soprattutto, lo storico delle chiamate con il picco
raggiunto dopo l'alert. Senza quello storico non c'e' modo di sapere se i
filtri stanno davvero funzionando o se stanno solo generando rumore.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from typing import Any, Iterable

from .config import settings
from .util import get_logger, now

log = get_logger("memescan.store")

SCHEMA = """
CREATE TABLE IF NOT EXISTS candidates (
    token_address     TEXT PRIMARY KEY,
    pair_address      TEXT,
    symbol            TEXT,
    name              TEXT,
    dex               TEXT,
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
    peak_notified     REAL DEFAULT 0
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
            },
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

        # I nomi delle origini sono passati all'inglese dopo il primo rilascio:
        # le righe scritte nel frattempo finirebbero fuori da ogni filtro.
        for vecchio, nuovo in (("balene", "whales"), ("scanner_balene", "scanner_whales")):
            self._exec(
                "UPDATE candidates SET alert_kind = ? WHERE alert_kind = ?", (nuovo, vecchio)
            )

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # -- helper generici ----------------------------------------------------

    def _exec(self, sql: str, params: Iterable = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._conn.execute(sql, tuple(params))
            self._conn.commit()
            return cur

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
            "score, alert_kind FROM candidates "
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
                           win_rate: float = 0) -> None:
        self._exec(
            "INSERT INTO tracked_wallets(address, label, added_at, pnl_usd, win_rate) "
            "VALUES(?, ?, ?, ?, ?) ON CONFLICT(address) DO UPDATE SET "
            "label = excluded.label, pnl_usd = excluded.pnl_usd, win_rate = excluded.win_rate",
            (address.lower(), label, now(), pnl_usd, win_rate),
        )

    def remove_tracked_wallet(self, address: str) -> None:
        self._exec("DELETE FROM tracked_wallets WHERE address = ?", (address.lower(),))

    def list_tracked_wallets(self, enabled_only: bool = True) -> list[dict]:
        sql = "SELECT * FROM tracked_wallets"
        if enabled_only:
            sql += " WHERE enabled = 1"
        sql += " ORDER BY pnl_usd DESC"
        return self._query(sql)

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

    # Chi compra piu' di cosi' token diversi in un giorno non sta scegliendo:
    # sta rastrellando. Vale come sotto-query dentro i conteggi, cosi' i bot
    # non entrano nel punteggio anche se sono ancora nella lista.
    _NON_BOT = (
        " AND wallet NOT IN ("
        "   SELECT wallet FROM wallet_events"
        "   WHERE direction = 'buy' AND ts > ?"
        "   GROUP BY wallet HAVING COUNT(DISTINCT token_address) > ?"
        " )"
    )

    def wallet_activity(self, within_seconds: int = 86400) -> dict[str, int]:
        """Quanti token diversi ha comprato ogni wallet tracciato.

        E' il numero che smaschera i bot: una balena vera compra due o tre cose
        al giorno, uno sniper automatico ne compra decine.
        """
        rows = self._query(
            "SELECT wallet, COUNT(DISTINCT token_address) AS n FROM wallet_events "
            "WHERE direction = 'buy' AND ts > ? GROUP BY wallet",
            (now() - within_seconds,),
        )
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
            "  FROM wallet_events WHERE token_address = ?"
            ") WHERE rn = 1 AND direction = 'buy' AND ts > ?"
        )
        params: list[Any] = [token_address.lower(), now() - within_seconds]
        if max_tokens_per_day > 0:
            sql += self._NON_BOT
            params += [now() - 86400, max_tokens_per_day]
        row = self._query_one(sql, params)
        return row["n"] if row else 0

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
        perf = self._query_one(
            "SELECT COUNT(*) AS n, AVG(peak_multiple) AS avg_peak, "
            "SUM(peak_multiple >= 2) AS x2, SUM(peak_multiple >= 5) AS x5, "
            "SUM(peak_multiple >= 10) AS x10 "
            "FROM candidates WHERE status = 'alerted' AND price_at_alert > 0"
        ) or {}
        last_24h = self._query_one(
            "SELECT COUNT(*) AS n FROM alerts WHERE ts > ?", (now() - 86400,)
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
        }


_store: Store | None = None


def get_store() -> Store:
    global _store
    if _store is None:
        _store = Store()
    return _store
