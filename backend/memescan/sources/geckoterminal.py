"""Sorgente GeckoTerminal.

E' l'unica API pubblica e gratuita che espone un vero endpoint "new pools",
quindi qui e' la sorgente di scoperta di riserva quando gli eventi on-chain
non sono disponibili (per esempio se l'RPC pubblico limita eth_getLogs).
Limite: 30 chiamate al minuto sul tier gratuito.
"""

from __future__ import annotations

from datetime import datetime, timezone

from ..config import settings
from ..models import PairSnapshot
from ..store import get_store
from ..util import HttpClient, RateLimiter, get_logger, safe_float, safe_int

log = get_logger("memescan.geckoterminal")

BASE_URL = "https://api.geckoterminal.com/api/v2"
_limiter = RateLimiter(rate=25, per=60.0)

_META_KEY = "geckoterminal_network"


def _parse_iso(value: str) -> int:
    if not value:
        return 0
    try:
        return int(datetime.fromisoformat(value.replace("Z", "+00:00"))
                   .astimezone(timezone.utc).timestamp())
    except ValueError:
        return 0


def _token_address_from_id(token_id: str) -> str:
    """Gli id sono nella forma "<network>_<address>"."""
    if not token_id:
        return ""
    return token_id.rsplit("_", 1)[-1].lower()


class GeckoTerminalSource:
    name = "geckoterminal"

    def __init__(self) -> None:
        self.http = HttpClient(
            base_url=BASE_URL,
            rate_limit=_limiter,
            headers={"Accept": "application/json;version=20230302"},
        )
        self.network = settings.geckoterminal_network or ""
        self._dex_names: dict[str, str] = {}

    async def close(self) -> None:
        await self.http.close()

    async def ensure_network(self) -> str:
        """Trova lo slug di rete di Robinhood Chain e lo memorizza.

        Lo slug non e' documentato e puo' cambiare, quindi invece di
        codificarlo si scorre l'elenco delle reti confrontando nome e id.
        Il risultato finisce nel database: la ricerca si fa una volta sola.
        """
        if self.network:
            return self.network

        store = get_store()
        cached = store.get_meta(_META_KEY)
        if cached:
            self.network = cached
            return cached

        needles = ("robinhood", "hood")
        for page in range(1, 8):
            data = await self.http.get("/networks", params={"page": page})
            entries = (data or {}).get("data") or []
            if not entries:
                break
            for entry in entries:
                network_id = (entry.get("id") or "").lower()
                name = ((entry.get("attributes") or {}).get("name") or "").lower()
                if any(n in network_id or n in name for n in needles):
                    self.network = network_id
                    store.set_meta(_META_KEY, network_id)
                    log.info("rete GeckoTerminal rilevata: %s", network_id)
                    return network_id

        log.warning(
            "rete Robinhood non trovata su GeckoTerminal; la sorgente resta spenta. "
            "Se conosci lo slug impostalo in GECKOTERMINAL_NETWORK."
        )
        return ""

    def _to_snapshot(self, pool: dict, included: dict[str, dict]) -> PairSnapshot | None:
        attributes = pool.get("attributes") or {}
        relationships = pool.get("relationships") or {}

        base_id = ((relationships.get("base_token") or {}).get("data") or {}).get("id", "")
        token_address = _token_address_from_id(base_id)
        if not token_address:
            return None

        volume = attributes.get("volume_usd") or {}
        change = attributes.get("price_change_percentage") or {}
        txns = attributes.get("transactions") or {}
        m5 = txns.get("m5") or {}
        h1 = txns.get("h1") or {}

        base_token = included.get(base_id, {})
        quote_id = ((relationships.get("quote_token") or {}).get("data") or {}).get("id", "")
        quote_token = included.get(quote_id, {})
        dex_id = ((relationships.get("dex") or {}).get("data") or {}).get("id", "")

        # "PEPE / WETH" -> tiene solo il simbolo del token base come fallback.
        pool_name = attributes.get("name") or ""
        fallback_symbol = pool_name.split("/")[0].strip() if "/" in pool_name else pool_name

        return PairSnapshot(
            token_address=token_address,
            pair_address=(attributes.get("address") or "").lower(),
            symbol=base_token.get("symbol") or fallback_symbol,
            name=base_token.get("name") or "",
            dex=dex_id,
            quote_symbol=quote_token.get("symbol", ""),
            source=self.name,
            pair_created_at=_parse_iso(attributes.get("pool_created_at", "")),
            price_usd=safe_float(attributes.get("base_token_price_usd")),
            liquidity_usd=safe_float(attributes.get("reserve_in_usd")),
            fdv=safe_float(attributes.get("fdv_usd")),
            market_cap=safe_float(attributes.get("market_cap_usd"))
            or safe_float(attributes.get("fdv_usd")),
            volume_5m=safe_float(volume.get("m5")),
            volume_1h=safe_float(volume.get("h1")),
            volume_24h=safe_float(volume.get("h24")),
            price_change_5m=safe_float(change.get("m5")),
            price_change_1h=safe_float(change.get("h1")),
            price_change_24h=safe_float(change.get("h24")),
            buys_5m=safe_int(m5.get("buys")),
            sells_5m=safe_int(m5.get("sells")),
            buys_1h=safe_int(h1.get("buys")),
            sells_1h=safe_int(h1.get("sells")),
        )

    @staticmethod
    def _index_included(payload: dict) -> dict[str, dict]:
        """Mappa id -> attributi per i token inclusi nella risposta."""
        index: dict[str, dict] = {}
        for item in payload.get("included") or []:
            if isinstance(item, dict) and item.get("id"):
                index[item["id"]] = item.get("attributes") or {}
        return index

    async def _fetch(self, endpoint: str, pages: int = 1) -> list[PairSnapshot]:
        network = await self.ensure_network()
        if not network:
            return []
        out: list[PairSnapshot] = []
        for page in range(1, pages + 1):
            data = await self.http.get(
                f"/networks/{network}/{endpoint}",
                params={"page": page, "include": "base_token,quote_token,dex"},
            )
            if not data:
                break
            included = self._index_included(data)
            pools = data.get("data") or []
            if not pools:
                break
            for pool in pools:
                snapshot = self._to_snapshot(pool, included)
                if snapshot:
                    out.append(snapshot)
        return out

    async def discover(self) -> list[PairSnapshot]:
        """Pool appena creati sulla rete."""
        pools = await self._fetch("new_pools", pages=2)
        log.debug("discovery geckoterminal: %d pool", len(pools))
        return pools

    async def candele(self, pool: str, minuti: int = 5, quante: int = 1000) -> list[list]:
        """Storico dei prezzi di una pozza, dal piu' recente al piu' vecchio.

        Ogni riga e' [istante, apertura, massimo, minimo, chiusura, volume].
        Serve a sapere quanto valeva un token in un momento preciso: senza,
        "questa moneta e' andata bene" resta un'impressione.
        """
        rete = await self.ensure_network()
        if not rete or not pool:
            return []
        dati = await self.http.get(
            f"/networks/{rete}/pools/{pool}/ohlcv/minute",
            params={"aggregate": minuti, "limit": quante},
        )
        if not isinstance(dati, dict):
            return []
        lista = (((dati.get("data") or {}).get("attributes") or {}).get("ohlcv_list")) or []
        return [r for r in lista if isinstance(r, list) and len(r) >= 5]

    async def trending(self) -> list[PairSnapshot]:
        return await self._fetch("trending_pools", pages=1)
