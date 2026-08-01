"""Client Blockscout (explorer di Robinhood Chain).

Fornisce i dati che dall'RPC costerebbero troppe chiamate: numero di holder,
distribuzione delle quote, indirizzo del deployer, stato di verifica del
contratto e storico dei trasferimenti di un wallet.

Si usa l'API v2 (REST) con ricaduta sull'API v1 in stile Etherscan, perche' non
tutte le istanze Blockscout espongono entrambe.
"""

from __future__ import annotations

from ..config import settings
from ..util import HttpClient, RateLimiter, get_logger, safe_float, safe_int

log = get_logger("memescan.blockscout")

_limiter = RateLimiter(rate=100, per=60.0)


class BlockscoutSource:
    name = "blockscout"

    def __init__(self) -> None:
        self.base = settings.blockscout_url.rstrip("/")
        self.http = HttpClient(base_url=self.base, rate_limit=_limiter)
        self.api_key = settings.blockscout_api_key

    async def close(self) -> None:
        await self.http.close()

    async def ping(self) -> dict:
        """Verifica che l'istanza risponda e restituisce due numeri di contesto."""
        data = await self.http.get("/api/v2/stats")
        if not isinstance(data, dict):
            return {}
        return {
            "ok": True,
            "average_block_time_ms": safe_float(data.get("average_block_time")),
            "total_blocks": data.get("total_blocks") or "",
        }

    def _v1_params(self, params: dict) -> dict:
        if self.api_key:
            params = {**params, "apikey": self.api_key}
        return params

    # -- token --------------------------------------------------------------

    async def token_info(self, token: str) -> dict:
        """Metadati del token, incluso il conteggio degli holder."""
        data = await self.http.get(f"/api/v2/tokens/{token}")
        if isinstance(data, dict) and data.get("address_hash"):
            return {
                "symbol": data.get("symbol") or "",
                "name": data.get("name") or "",
                "decimals": safe_int(data.get("decimals"), 18),
                "total_supply": safe_float(data.get("total_supply")),
                "holders": safe_int(data.get("holders_count") or data.get("holders")),
                "market_cap": safe_float(data.get("circulating_market_cap")),
            }
        return {}

    async def search_tokens(self, query: str) -> list[dict]:
        """Token che corrispondono a un testo, per simbolo o per nome.

        Serve a scoprire se esiste un'azione ufficiale con lo stesso simbolo
        di un token che stiamo valutando.
        """
        data = await self.http.get("/api/v2/tokens", params={"q": query})
        voci = (data or {}).get("items") if isinstance(data, dict) else None
        out = []
        for voce in voci or []:
            out.append({
                "address": voce.get("address_hash") or voce.get("address") or "",
                "symbol": voce.get("symbol") or "",
                "name": voce.get("name") or "",
                "holders": safe_int(voce.get("holders_count")),
            })
        return out

    async def holders(self, token: str, limit: int = 50) -> list[dict]:
        """Prime N posizioni ordinate per quantita' detenuta.

        L'endpoint restituisce 50 righe per pagina e rifiuta un parametro
        `limit` (risponde 422), quindi il taglio si fa qui. Ogni riga porta
        anche `is_contract`: serve a distinguere un pool di liquidita' da una
        persona che puo' venderti addosso.
        """
        data = await self.http.get(f"/api/v2/tokens/{token}/holders")
        items = (data or {}).get("items") if isinstance(data, dict) else None

        if items is None:
            # Ricaduta sull'API in stile Etherscan.
            legacy = await self.http.get(
                "/api",
                params=self._v1_params(
                    {
                        "module": "token",
                        "action": "getTokenHolders",
                        "contractaddress": token,
                        "page": 1,
                        "offset": limit,
                    }
                ),
            )
            raw = (legacy or {}).get("result") or []
            return [
                {
                    "address": (item.get("address") or "").lower(),
                    "value": safe_float(item.get("value")),
                    "is_contract": False,
                }
                for item in raw[:limit]
                if isinstance(item, dict)
            ]

        out = []
        for item in items[:limit]:
            address_info = item.get("address") or {}
            out.append(
                {
                    "address": (address_info.get("hash") or "").lower(),
                    "value": safe_float(item.get("value")),
                    "is_contract": bool(address_info.get("is_contract")),
                }
            )
        return out

    async def address_info(self, address: str) -> dict:
        """Info su un indirizzo: se e' un contratto, chi lo ha creato, se e' verificato."""
        data = await self.http.get(f"/api/v2/addresses/{address}")
        if not isinstance(data, dict):
            return {}
        return {
            "is_contract": bool(data.get("is_contract")),
            "is_verified": bool(data.get("is_verified")),
            "creator": (data.get("creator_address_hash") or "").lower(),
            "creation_tx": data.get("creation_transaction_hash")
            or data.get("creation_tx_hash")
            or "",
            "is_scam": bool(data.get("is_scam")),
            "proxy_type": data.get("proxy_type") or "",
        }

    async def is_verified(self, address: str) -> bool:
        info = await self.address_info(address)
        return bool(info.get("is_verified"))

    # -- wallet -------------------------------------------------------------

    async def token_transfers(self, address: str, limit: int = 50) -> list[dict]:
        """Trasferimenti ERC-20 recenti di un indirizzo, dal piu' recente."""
        data = await self.http.get(
            f"/api/v2/addresses/{address}/token-transfers", params={"type": "ERC-20"}
        )
        items = (data or {}).get("items") if isinstance(data, dict) else None

        if items is not None:
            out = []
            for item in items[:limit]:
                token = item.get("token") or {}
                total = item.get("total") or {}
                # `total` porta i propri decimali: sono piu' affidabili di quelli
                # del token quando si tratta di un contratto non standard.
                decimals = safe_int(total.get("decimals") or token.get("decimals"), 18)
                raw_value = safe_float(total.get("value"))
                out.append(
                    {
                        "token_address": ((token.get("address") or token.get("address_hash") or "")
                                          .lower()),
                        "symbol": token.get("symbol") or "",
                        "from": ((item.get("from") or {}).get("hash") or "").lower(),
                        "to": ((item.get("to") or {}).get("hash") or "").lower(),
                        "amount": raw_value / (10**decimals) if decimals <= 36 else raw_value,
                        "tx_hash": item.get("transaction_hash") or item.get("tx_hash") or "",
                        "block_number": safe_int(item.get("block_number")),
                        "timestamp": item.get("timestamp") or "",
                    }
                )
            return out

        legacy = await self.http.get(
            "/api",
            params=self._v1_params(
                {
                    "module": "account",
                    "action": "tokentx",
                    "address": address,
                    "sort": "desc",
                    "page": 1,
                    "offset": limit,
                }
            ),
        )
        raw = (legacy or {}).get("result") or []
        out = []
        for item in raw:
            if not isinstance(item, dict):
                continue
            decimals = safe_int(item.get("tokenDecimal"), 18)
            value = safe_float(item.get("value"))
            out.append(
                {
                    "token_address": (item.get("contractAddress") or "").lower(),
                    "symbol": item.get("tokenSymbol") or "",
                    "from": (item.get("from") or "").lower(),
                    "to": (item.get("to") or "").lower(),
                    "amount": value / (10**decimals) if decimals <= 36 else value,
                    "tx_hash": item.get("hash") or "",
                    "block_number": safe_int(item.get("blockNumber")),
                    "timestamp": item.get("timeStamp") or "",
                }
            )
        return out
