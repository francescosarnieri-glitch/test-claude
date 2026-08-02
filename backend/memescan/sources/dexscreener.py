"""Sorgente Dexscreener.

Dexscreener non espone un endpoint pubblico "new pairs" (quella pagina e' solo
web). La scoperta vera la fa il modulo on-chain; qui si usano gli endpoint
disponibili per due cose: arricchire con prezzi/volumi i token gia' trovati, e
pescare i token che entrano nei profili e nei boost, che spesso anticipano di
poco l'attenzione social.
"""

from __future__ import annotations

from ..config import settings
from ..models import PairSnapshot
from ..util import HttpClient, RateLimiter, get_logger, safe_float, safe_int

log = get_logger("memescan.dexscreener")

BASE_URL = "https://api.dexscreener.com"
# Gli endpoint /latest/dex/* consentono 300 req/min, quelli /token-*/ 60 req/min.
# Si usa il limite piu' basso per stare tranquilli su entrambi.
_limiter = RateLimiter(rate=55, per=60.0)


#: Le versioni che sappiamo distinguere. Tutto il resto finisce in "altro":
#: su questa chain girano anche pozze di altri exchange (flapsh) che non
#: seguono nessuno di questi tre schemi.
VERSIONI_NOTE = ("v2", "v3", "v4")


def _versione_pozza(pair: dict) -> str:
    """Ricava lo stile della pozza da quello che Dexscreener manda gia'.

    Arriva in `labels` sulla stessa risposta che lo scanner scarica a ogni
    giro: nessuna chiamata in piu' e si aggiorna da solo. Se manca resta la
    prova di riserva: un id di pozza v4 e' una parola da 32 byte, mentre un
    indirizzo ne ha 20, e la differenza si vede dalla lunghezza.
    """
    for etichetta in pair.get("labels") or []:
        if str(etichetta).lower() in VERSIONI_NOTE:
            return str(etichetta).lower()
    if len(pair.get("pairAddress") or "") > 42:
        return "v4"
    return ""


class DexscreenerSource:
    name = "dexscreener"

    def __init__(self) -> None:
        self.chain = settings.dexscreener_chain
        self.http = HttpClient(base_url=BASE_URL, rate_limit=_limiter)

    async def close(self) -> None:
        await self.http.close()

    # -- conversione --------------------------------------------------------

    def _to_snapshot(self, pair: dict) -> PairSnapshot | None:
        base = pair.get("baseToken") or {}
        if not base.get("address"):
            return None
        txns = pair.get("txns") or {}
        m5 = txns.get("m5") or {}
        h1 = txns.get("h1") or {}
        volume = pair.get("volume") or {}
        change = pair.get("priceChange") or {}
        liquidity = pair.get("liquidity") or {}
        info = pair.get("info") or {}

        created_ms = safe_int(pair.get("pairCreatedAt"))
        socials = {
            item.get("type", item.get("label", "link")): item.get("url", "")
            for item in (info.get("socials") or [])
            if isinstance(item, dict)
        }
        for site in info.get("websites") or []:
            if isinstance(site, dict) and site.get("url"):
                socials.setdefault("website", site["url"])

        return PairSnapshot(
            token_address=base.get("address", ""),
            pair_address=pair.get("pairAddress", "") or "",
            symbol=base.get("symbol", "") or "",
            name=base.get("name", "") or "",
            dex=pair.get("dexId", "") or "",
            pool_version=_versione_pozza(pair),
            quote_symbol=(pair.get("quoteToken") or {}).get("symbol", "") or "",
            quote_address=(pair.get("quoteToken") or {}).get("address", "") or "",
            source=self.name,
            pair_created_at=created_ms // 1000 if created_ms else 0,
            price_usd=safe_float(pair.get("priceUsd")),
            liquidity_usd=safe_float(liquidity.get("usd")),
            fdv=safe_float(pair.get("fdv")),
            market_cap=safe_float(pair.get("marketCap")) or safe_float(pair.get("fdv")),
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
            image_url=info.get("imageUrl", "") or "",
            socials=socials,
        )

    def _best_pair(self, pairs: list[dict]) -> dict | None:
        """Un token puo' avere piu' pool: interessa quello con piu' liquidita'."""
        candidates = [
            p for p in pairs
            if isinstance(p, dict) and (p.get("chainId") or "").lower() == self.chain
        ]
        if not candidates:
            return None
        return max(candidates, key=lambda p: safe_float((p.get("liquidity") or {}).get("usd")))

    # -- lookup -------------------------------------------------------------

    async def get_token(self, token_address: str) -> PairSnapshot | None:
        """Dati di mercato di un token, scegliendo il pool piu' liquido."""
        data = await self.http.get(f"/token-pairs/v1/{self.chain}/{token_address}")
        pairs = data if isinstance(data, list) else (data or {}).get("pairs") or []
        best = self._best_pair(pairs)
        return self._to_snapshot(best) if best else None

    async def get_tokens(self, token_addresses: list[str]) -> dict[str, PairSnapshot]:
        """Batch: l'endpoint accetta fino a 30 indirizzi per chiamata."""
        out: dict[str, PairSnapshot] = {}
        for start in range(0, len(token_addresses), 30):
            chunk = token_addresses[start : start + 30]
            data = await self.http.get(f"/tokens/v1/{self.chain}/{','.join(chunk)}")
            pairs = data if isinstance(data, list) else (data or {}).get("pairs") or []
            by_token: dict[str, list[dict]] = {}
            for pair in pairs:
                if not isinstance(pair, dict):
                    continue
                address = ((pair.get("baseToken") or {}).get("address") or "").lower()
                if address:
                    by_token.setdefault(address, []).append(pair)
            for address, token_pairs in by_token.items():
                best = self._best_pair(token_pairs)
                snapshot = self._to_snapshot(best) if best else None
                if snapshot:
                    out[address] = snapshot
        return out

    async def get_pair(self, pair_address: str) -> PairSnapshot | None:
        data = await self.http.get(f"/latest/dex/pairs/{self.chain}/{pair_address}")
        pairs = (data or {}).get("pairs") or []
        if not pairs:
            return None
        return self._to_snapshot(pairs[0])

    async def search(self, query: str) -> list[PairSnapshot]:
        data = await self.http.get("/latest/dex/search", params={"q": query})
        pairs = (data or {}).get("pairs") or []
        out = []
        for pair in pairs:
            if (pair.get("chainId") or "").lower() != self.chain:
                continue
            snapshot = self._to_snapshot(pair)
            if snapshot:
                out.append(snapshot)
        return out

    # -- scoperta -----------------------------------------------------------

    async def discover(self) -> list[PairSnapshot]:
        """Token appena profilati o "boostati" sulla chain configurata.

        Un boost e' a pagamento: significa che qualcuno sta spendendo per farsi
        vedere. Non e' di per se' un buon segno, ma e' un segnale di attenzione
        in arrivo, e passa comunque dai controlli di sicurezza come tutti.
        """
        addresses: set[str] = set()
        for endpoint in ("/token-profiles/latest/v1", "/token-boosts/latest/v1",
                         "/token-boosts/top/v1"):
            data = await self.http.get(endpoint)
            if not isinstance(data, list):
                continue
            for item in data:
                if not isinstance(item, dict):
                    continue
                if (item.get("chainId") or "").lower() != self.chain:
                    continue
                address = (item.get("tokenAddress") or "").lower()
                if address:
                    addresses.add(address)

        if not addresses:
            return []
        snapshots = await self.get_tokens(sorted(addresses))
        log.debug("discovery dexscreener: %d token", len(snapshots))
        return list(snapshots.values())
