"""Riconoscimento dei token che copiano il nome di un altro.

E' il buco che i controlli anti-rug non coprono: il contratto puo' essere
pulito, l'ownership rinunciata e la distribuzione sana, e il token essere
comunque una copia creata per agganciarsi a un nome che sta gia' correndo.
Chi compra pensa di prendere quello di cui ha sentito parlare e ne prende un
altro.

Sul nome non ci si puo' basare: il simbolo non e' unico e chiunque puo'
riusarlo. L'unico dato che identifica un token e' il suo indirizzo. Quindi si
cerca se esiste un altro token con lo stesso simbolo che sia insieme piu'
vecchio e molto piu' liquido: in quel caso l'originale e' quello, e il nostro
e' la copia.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from .models import PairSnapshot
from .util import get_logger

log = get_logger("memescan.clones")

# Quanto piu' liquido deve essere l'altro token perche' il nostro sia
# considerato una copia. Tre volte e' una differenza che non capita per caso
# fra due lanci indipendenti che si sono trovati lo stesso nome.
LIQUIDITY_RATIO = 3.0

# Se manca la data di creazione ci si basa solo sulla liquidita', e allora
# serve uno scarto piu' netto per non scartare un originale sfortunato.
LIQUIDITY_RATIO_NO_AGE = 6.0

_CACHE_TTL = 300.0
_cache: dict[str, tuple[float, list[PairSnapshot]]] = {}


@dataclass(slots=True)
class CloneVerdict:
    is_clone: bool = False
    reason: str = ""
    original_address: str = ""
    original_liquidity: float = 0.0

    def to_dict(self) -> dict:
        return {
            "is_clone": self.is_clone,
            "reason": self.reason,
            "original_address": self.original_address,
            "original_liquidity": round(self.original_liquidity),
        }


async def _same_symbol_pairs(dexscreener, symbol: str) -> list[PairSnapshot]:
    """Tutti i token della chain che usano questo simbolo."""
    key = symbol.upper()
    cached = _cache.get(key)
    if cached and (time.monotonic() - cached[0]) < _CACHE_TTL:
        return cached[1]

    try:
        found = await dexscreener.search(symbol)
    except Exception as exc:  # pragma: no cover - dipende dalla rete
        log.debug("ricerca per simbolo non riuscita (%s): %s", symbol, exc)
        return []

    matches = [s for s in found if (s.symbol or "").strip().upper() == key]
    _cache[key] = (time.monotonic(), matches)

    # La cache non deve crescere all'infinito su un processo che gira per mesi.
    if len(_cache) > 500:
        oldest = sorted(_cache.items(), key=lambda kv: kv[1][0])
        for stale_key, _ in oldest[:200]:
            _cache.pop(stale_key, None)

    return matches


async def check(dexscreener, snapshot: PairSnapshot) -> CloneVerdict:
    symbol = (snapshot.symbol or "").strip()
    if len(symbol) < 2:
        # Senza simbolo non c'e' niente da confrontare.
        return CloneVerdict()

    others = [
        s for s in await _same_symbol_pairs(dexscreener, symbol)
        if s.token_address and s.token_address != snapshot.token_address
    ]
    if not others:
        return CloneVerdict()

    biggest = max(others, key=lambda s: s.liquidity_usd)
    if biggest.liquidity_usd <= 0:
        return CloneVerdict()

    ours = max(snapshot.liquidity_usd, 1.0)
    ratio = biggest.liquidity_usd / ours

    both_dated = bool(snapshot.pair_created_at and biggest.pair_created_at)
    if both_dated:
        # L'originale deve essere anche piu' vecchio: senza questa condizione
        # scarteremmo il primo token che parte piano e viene poi copiato da uno
        # che pompa di piu'.
        older = biggest.pair_created_at <= snapshot.pair_created_at
        is_clone = older and ratio >= LIQUIDITY_RATIO
    else:
        is_clone = ratio >= LIQUIDITY_RATIO_NO_AGE

    if not is_clone:
        return CloneVerdict()

    log.info(
        "clone sospetto: $%s %s (liq %.0f) contro %s (liq %.0f)",
        symbol, snapshot.token_address[:10], snapshot.liquidity_usd,
        biggest.token_address[:10], biggest.liquidity_usd,
    )
    return CloneVerdict(
        is_clone=True,
        reason=(
            f"Esiste gia' un ${symbol} piu' vecchio e {ratio:.0f} volte piu' "
            "liquido: questo e' quasi certamente una copia"
        ),
        original_address=biggest.token_address,
        original_liquidity=biggest.liquidity_usd,
    )
