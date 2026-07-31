"""Impostazioni regolabili a caldo, senza riavviare e senza toccare il server.

I valori del file di configurazione restano il punto di partenza, ma qualunque
modifica fatta dalla dashboard li sovrascrive e viene salvata nel database.
Serve perche' la macchina su cui gira lo scanner non e' raggiungibile: senza
questo, cambiare una soglia richiederebbe di ricrearla da zero.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from .config import settings
from .store import get_store
from .util import get_logger

log = get_logger("memescan.tunables")

# Le letture avvengono dentro i cicli di scansione: una cache brevissima evita
# di interrogare SQLite per ogni token, restando comunque reattiva a una
# modifica fatta dal telefono.
_CACHE_TTL = 5.0
_cache: dict[str, str] = {}
_cache_at = 0.0


@dataclass(slots=True, frozen=True)
class Tunable:
    key: str
    label: str
    help: str
    kind: str  # "int", "float" o "bool"
    minimum: float = 0.0
    maximum: float = 0.0

    def default(self):
        return _DEFAULTS[self.key]()


# I default si leggono dal file di configurazione al momento della richiesta,
# non all'import: cosi' restano coerenti anche se .env cambia.
_DEFAULTS = {
    "alert_min_score": lambda: settings.alert_min_score,
    "alert_cooldown_minutes": lambda: settings.alert_cooldown_minutes,
    "wallet_convergence_threshold": lambda: settings.wallet_convergence_threshold,
    "min_liquidity_usd": lambda: settings.filters.min_liquidity_usd,
    "min_volume_1h_usd": lambda: settings.filters.min_volume_1h_usd,
    "min_holders": lambda: settings.filters.min_holders,
    "min_age_minutes": lambda: settings.filters.min_age_minutes,
    "max_age_hours": lambda: settings.filters.max_age_hours,
    "max_price_change_1h": lambda: 300.0,
    "max_top10_holder_pct": lambda: settings.filters.max_top10_holder_pct,
    "clone_guard": lambda: True,
    "wallet_window_hours": lambda: 24,
    "max_wallet_tokens_per_day": lambda: 6,
    "wallet_min_winners": lambda: 4,
    "silenzio_da": lambda: 0,
    "silenzio_a": lambda: 0,
    "backup_ora": lambda: 3,
}

TUNABLES: list[Tunable] = [
    Tunable(
        "alert_min_score", "Soglia di alert",
        "Punteggio minimo perche' arrivi la notifica. Senza whales tracciate il "
        "massimo raggiungibile e' 75, quindi oltre 72 non arriverebbe quasi nulla.",
        "int", 30, 90,
    ),
    Tunable(
        "min_age_minutes", "Eta' minima del pool (minuti)",
        "Nei primissimi minuti i dati sono inaffidabili e ci sono solo bot. "
        "Alzarlo e' il modo piu' efficace per evitare i lanci appena nati.",
        "int", 0, 240,
    ),
    Tunable(
        "max_age_hours", "Eta' massima del pool (ore)",
        "Oltre questa eta' un token non e' piu' una novita' e smette di essere "
        "valutato.",
        "int", 1, 720,
    ),
    Tunable(
        "max_price_change_1h", "Rialzo massimo gia' fatto (%)",
        "Scarta i token gia' esplosi: sopra questa soglia si comprerebbe il "
        "massimo di qualcun altro. Metti 0 per disattivare il controllo.",
        "int", 0, 2000,
    ),
    Tunable(
        "clone_guard", "Blocca i cloni",
        "Scarta i token che copiano il simbolo di uno gia' esistente, piu' "
        "vecchio e piu' liquido. E' il caso del falso SESTRI.",
        "bool",
    ),
    Tunable(
        "min_liquidity_usd", "Liquidita' minima ($)",
        "Sotto questa soglia il token non viene nemmeno valutato.",
        "int", 0, 500_000,
    ),
    Tunable(
        "min_volume_1h_usd", "Volume minimo in un'ora ($)",
        "Serve a scartare i pool creati e poi abbandonati.",
        "int", 0, 500_000,
    ),
    Tunable(
        "min_holders", "Holder minimi",
        "Quanti portafogli diversi devono possedere il token.",
        "int", 0, 5_000,
    ),
    Tunable(
        "max_top10_holder_pct", "Concentrazione massima top 10 (%)",
        "Quota massima della supply nelle mani dei primi dieci wallet, "
        "esclusi pool e contratti.",
        "int", 5, 90,
    ),
    Tunable(
        "wallet_convergence_threshold", "Whales per l'alert immediato",
        "Quante whales devono comprare lo stesso token perche' "
        "l'alert parta a prescindere dal punteggio.",
        "int", 1, 10,
    ),
    Tunable(
        "wallet_window_hours", "Quanto vale un acquisto delle whales (ore)",
        "Per quanto tempo l'acquisto di una whale conta come segnale "
        "vivo. Passato questo tempo il token non risulta piu' \"con le whales "
        "dentro\" e perde quei punti, anche se non hanno venduto. Basso vuol "
        "dire fidarsi solo di cosa comprano adesso; alto vuol dire tenere conto "
        "anche di posizioni aperte da giorni.",
        "int", 1, 336,
    ),
    Tunable(
        "max_wallet_tokens_per_day", "Token al giorno oltre cui e' un bot",
        "Chi compra piu' di cosi' monete diverse in un giorno non sta "
        "scegliendo, sta rastrellando: i suoi acquisti smettono di valere punti. "
        "Nella scheda Whales vedi quanti ne compra ciascuno. Metti 0 per "
        "contarli tutti.",
        "int", 0, 100,
    ),
    Tunable(
        "wallet_min_winners", "Vincenti richiesti per entrare nella lista",
        "Su quanti token poi esplosi deve essere arrivato presto per "
        "essere considerato una whale. Basso fa entrare i bot, che comprano "
        "tutto e quindi capitano su qualunque vincente per forza.",
        "int", 2, 10,
    ),
    Tunable(
        "alert_cooldown_minutes", "Attesa tra due alert sullo stesso token (minuti)",
        "Evita di ricevere piu' volte la stessa segnalazione.",
        "int", 5, 1440,
    ),
    Tunable(
        "backup_ora", "Ora del backup giornaliero (ora italiana)",
        "A che ora mandare la copia del database su GitHub, una volta al "
        "giorno. Di notte perche' e' il momento in cui c'e' meno da fare. Se "
        "la macchina era spenta a quell'ora, la copia si recupera appena "
        "torna su invece di saltare il giorno.",
        "int", 0, 23,
    ),
    Tunable(
        "silenzio_da", "Non suonare dalle (ora italiana)",
        "Da quest'ora il telefono non squilla piu'. Gli alert continuano ad "
        "arrivare nell'app: al mattino li trovi nella scheda Alert. Metti "
        "l'ora di inizio e quella di fine uguali per ricevere sempre.",
        "int", 0, 23,
    ),
    Tunable(
        "silenzio_a", "Torna a suonare alle (ora italiana)",
        "L'ora in cui riprendono le notifiche. Puo' essere piu' piccola "
        "dell'altra: da 23 a 8 vuol dire tutta la notte.",
        "int", 0, 23,
    ),
]

_BY_KEY = {t.key: t for t in TUNABLES}


def _stored() -> dict[str, str]:
    global _cache, _cache_at
    if time.monotonic() - _cache_at > _CACHE_TTL:
        try:
            _cache = get_store().get_settings()
        except Exception as exc:  # pragma: no cover - database non pronto
            log.debug("lettura impostazioni non riuscita: %s", exc)
            _cache = {}
        _cache_at = time.monotonic()
    return _cache


def invalidate() -> None:
    global _cache_at
    _cache_at = 0.0


def get(key: str):
    """Valore corrente: quello salvato dall'utente, altrimenti quello del file."""
    tunable = _BY_KEY.get(key)
    if tunable is None:
        raise KeyError(f"impostazione sconosciuta: {key}")

    raw = _stored().get(key)
    if raw is None:
        return tunable.default()

    try:
        if tunable.kind == "bool":
            return raw.lower() in ("1", "true", "yes", "on")
        if tunable.kind == "int":
            return int(float(raw))
        return float(raw)
    except (TypeError, ValueError):
        return tunable.default()


def set_value(key: str, value) -> None:
    tunable = _BY_KEY.get(key)
    if tunable is None:
        raise KeyError(f"impostazione sconosciuta: {key}")

    if tunable.kind == "bool":
        stored = "true" if value in (True, "true", "1", 1, "on", "yes") else "false"
    else:
        number = float(value)
        # I limiti non sono un vezzo: una soglia fuori scala spegnerebbe lo
        # scanner senza che sia evidente il perche'.
        if tunable.maximum > tunable.minimum:
            number = max(tunable.minimum, min(tunable.maximum, number))
        stored = str(int(number) if tunable.kind == "int" else number)

    get_store().set_setting(key, stored)
    invalidate()
    log.info("impostazione aggiornata: %s = %s", key, stored)


def reset(key: str) -> None:
    get_store().clear_setting(key)
    invalidate()


def snapshot() -> list[dict]:
    """Elenco completo per la dashboard, con valore attuale e default."""
    stored = _stored()
    out = []
    for tunable in TUNABLES:
        out.append(
            {
                "key": tunable.key,
                "label": tunable.label,
                "help": tunable.help,
                "kind": tunable.kind,
                "min": tunable.minimum,
                "max": tunable.maximum,
                "value": get(tunable.key),
                "default": tunable.default(),
                "customised": tunable.key in stored,
            }
        )
    return out
