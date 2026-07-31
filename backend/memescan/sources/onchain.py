"""Sorgente on-chain: e' quella che arriva per prima.

Legge direttamente gli eventi `PairCreated` (Uniswap V2 e cloni) e `PoolCreated`
(Uniswap V3) emessi dai factory. Un pool compare qui nel blocco stesso in cui
viene creato, mentre gli aggregatori lo indicizzano con minuti di ritardo: su un
meme coin quei minuti sono tutta la differenza.

Gli indirizzi dei factory non sono scritti nel codice: vengono dedotti dai pool
reali della chain, cosi' funziona anche con i DEX che nasceranno dopo.
"""

from __future__ import annotations

import asyncio
import json

from ..chain import (
    TOPIC_PAIR_CREATED,
    TOPIC_POOL_CREATED,
    get_rpc,
)
from ..config import settings
from ..models import PairSnapshot
from ..store import get_store
from ..util import get_logger, now

log = get_logger("memescan.onchain")

_META_FACTORIES = "onchain_factories"
_META_QUOTES = "onchain_quote_tokens"
_META_CURSOR = "onchain_last_block"

# Un pool nuovo ha per forza un token noto dall'altro lato (WETH, una
# stablecoin...). Questi simboli identificano il lato "quote" del pool.
QUOTE_SYMBOLS = {
    "WETH", "ETH", "USDC", "USDT", "USDG", "DAI", "WBTC", "USDS", "FRAX", "HOOD", "WHOOD",
}

# Robinhood Chain produce un blocco ogni ~0,1s: 5.000 blocchi sono circa 8
# minuti di catena, che e' anche il massimo che molti RPC pubblici accettano.
MAX_LOG_RANGE = 5_000
MIN_LOG_RANGE = 500


def _topic_to_address(topic: str) -> str:
    return "0x" + topic[-40:].lower() if topic and len(topic) >= 42 else ""


class OnchainSource:
    name = "onchain"

    def __init__(self) -> None:
        self.rpc = get_rpc()
        self.store = get_store()
        self.factories: list[str] = []
        self.quote_tokens: set[str] = set()
        self._log_range = MAX_LOG_RANGE

    # -- bootstrap ----------------------------------------------------------

    async def bootstrap(self, dexscreener) -> None:
        """Deduce factory e token quote osservando i pool esistenti.

        Si parte dai pair piu' liquidi noti a Dexscreener, si chiede a ciascuno
        il proprio `factory()` e si tiene l'insieme risultante. Basta farlo una
        volta: il risultato viene salvato nel database.
        """
        cached_factories = self.store.get_meta(_META_FACTORIES)
        cached_quotes = self.store.get_meta(_META_QUOTES)
        if cached_factories and cached_quotes:
            self.factories = json.loads(cached_factories)
            self.quote_tokens = set(json.loads(cached_quotes))
            log.info("factory da cache: %d, token quote: %d",
                     len(self.factories), len(self.quote_tokens))
            return

        # La ricerca di Dexscreener e' globale su tutte le chain: senza il nome
        # della rete nella query i risultati sono dominati da Solana ed Ethereum
        # e non resta nulla dopo il filtro. Con il nome accanto al simbolo si
        # ottengono i pool principali proprio di questa chain.
        chain_name = settings.dexscreener_chain
        found: list = []
        for symbol in ("WETH", "USDC", "USDG", "ETH"):
            found.extend(await dexscreener.search(f"{symbol} {chain_name}"))

        # I token quote arrivano gia' pronti dalla risposta di Dexscreener
        # (simbolo + indirizzo del lato quote), quindi non serve interrogare
        # l'ERC-20 di ogni lato di ogni pool: sono decine di round trip in meno.
        quotes: set[str] = set()
        pairs: list[dict] = []
        for snapshot in found:
            if snapshot.quote_address and snapshot.quote_symbol.upper() in QUOTE_SYMBOLS:
                quotes.add(snapshot.quote_address)
            # I pool Uniswap V4 hanno un id a 32 byte, non un indirizzo:
            # non si possono interrogare come contratti.
            if snapshot.pair_address and len(snapshot.pair_address) == 42:
                pairs.append(
                    {"pair": snapshot.pair_address, "liquidity": snapshot.liquidity_usd}
                )

        if not pairs:
            log.warning("bootstrap on-chain: nessun pair trovato, riprovo al giro dopo")
            return

        pairs.sort(key=lambda p: p["liquidity"], reverse=True)
        seen: set[str] = set()
        unique_pairs = []
        for entry in pairs:
            if entry["pair"] not in seen:
                seen.add(entry["pair"])
                unique_pairs.append(entry["pair"])
        unique_pairs = unique_pairs[:15]

        # I factory si leggono in parallelo: sono chiamate indipendenti.
        infos = await asyncio.gather(
            *(self.rpc.pair_info(pair) for pair in unique_pairs), return_exceptions=True
        )
        factories: set[str] = set()
        for info in infos:
            if isinstance(info, dict) and info.get("factory"):
                factories.add(info["factory"])

        self.factories = sorted(factories)
        self.quote_tokens = quotes
        if self.factories:
            self.store.set_meta(_META_FACTORIES, json.dumps(self.factories))
        if self.quote_tokens:
            self.store.set_meta(_META_QUOTES, json.dumps(sorted(self.quote_tokens)))
        log.info(
            "bootstrap on-chain completato: %d factory, %d token quote",
            len(self.factories), len(self.quote_tokens),
        )

    # -- lettura eventi -----------------------------------------------------

    async def _get_logs_chunked(self, from_block: int, to_block: int) -> list[dict]:
        """eth_getLogs a blocchi, riducendo il range solo se l'RPC lo rifiuta.

        Un intervallo senza eventi e' normale e non deve far restringere niente:
        si riduce solo davanti a un errore vero (None), e al massimo per un
        numero limitato di tentativi, per non restare bloccati su un tratto.
        """
        logs: list[dict] = []
        cursor = from_block
        retries = 0
        while cursor <= to_block:
            end = min(cursor + self._log_range - 1, to_block)
            batch = await self.rpc.get_logs(
                from_block=cursor,
                to_block=end,
                address=self.factories or None,
                topics=[[TOPIC_PAIR_CREATED, TOPIC_POOL_CREATED]],
            )
            if batch is None:
                if self._log_range > MIN_LOG_RANGE and retries < 6:
                    self._log_range = max(MIN_LOG_RANGE, self._log_range // 2)
                    retries += 1
                    log.debug("range di eth_getLogs ridotto a %d blocchi", self._log_range)
                    continue
                # L'RPC continua a rifiutare: si salta il tratto invece di
                # bloccare lo scanner, il cursore avanza comunque.
                log.warning("eth_getLogs fallito sui blocchi %d-%d, tratto saltato", cursor, end)
                cursor = end + 1
                retries = 0
                continue
            logs.extend(batch)
            cursor = end + 1
            retries = 0
        return logs

    def _decode_creation(self, entry: dict) -> tuple[str, str, str] | None:
        """Ritorna (token0, token1, pool) da un log di creazione pool."""
        topics = entry.get("topics") or []
        if len(topics) < 3:
            return None
        token0 = _topic_to_address(topics[1])
        token1 = _topic_to_address(topics[2])
        data = (entry.get("data") or "0x")[2:]

        if topics[0].lower() == TOPIC_PAIR_CREATED.lower():
            # PairCreated(address,address,address pair,uint256): pair = prima word
            if len(data) < 64:
                return None
            pool = "0x" + data[24:64]
        else:
            # PoolCreated(address,address,uint24 fee,int24 tickSpacing,address pool)
            # con fee indicizzata: nei dati restano tickSpacing e pool.
            if len(data) < 128:
                return None
            pool = "0x" + data[88:128]
        return token0, token1, pool.lower()

    def _pick_base_token(self, token0: str, token1: str) -> str | None:
        """Sceglie quale dei due lati e' il token nuovo (non il quote)."""
        zero_is_quote = token0 in self.quote_tokens
        one_is_quote = token1 in self.quote_tokens
        if zero_is_quote and not one_is_quote:
            return token1
        if one_is_quote and not zero_is_quote:
            return token0
        # Nessuno dei due e' un quote noto (DEX nuovo o coppia esotica):
        # non si puo' decidere, lo lasciamo agli aggregatori.
        return None

    async def discover(self) -> list[PairSnapshot]:
        head = await self.rpc.block_number()
        if not head:
            return []

        cursor_raw = self.store.get_meta(_META_CURSOR)
        if cursor_raw:
            from_block = int(cursor_raw) + 1
        else:
            from_block = max(1, head - settings.onchain_backfill_blocks)
            log.info("primo avvio: leggo indietro %d blocchi", head - from_block)

        if from_block > head:
            return []

        logs = await self._get_logs_chunked(from_block, head)
        self.store.set_meta(_META_CURSOR, str(head))
        if not logs:
            return []

        # I timestamp dei blocchi si risolvono una volta per blocco, non per log.
        block_numbers = {int(entry["blockNumber"], 16) for entry in logs if entry.get("blockNumber")}
        timestamps: dict[int, int] = {}
        block_list = sorted(block_numbers)
        for start in range(0, len(block_list), 20):
            chunk = block_list[start : start + 20]
            results = await self.rpc.batch(
                [("eth_getBlockByNumber", [hex(b), False]) for b in chunk]
            )
            for block_number, result in zip(chunk, results):
                if isinstance(result, dict) and result.get("timestamp"):
                    timestamps[block_number] = int(result["timestamp"], 16)

        snapshots: list[PairSnapshot] = []
        for entry in logs:
            decoded = self._decode_creation(entry)
            if not decoded:
                continue
            token0, token1, pool = decoded
            base = self._pick_base_token(token0, token1)
            if not base:
                continue
            block_number = int(entry["blockNumber"], 16) if entry.get("blockNumber") else 0
            snapshots.append(
                PairSnapshot(
                    token_address=base,
                    pair_address=pool,
                    source=self.name,
                    pair_created_at=timestamps.get(block_number, now()),
                )
            )

        if snapshots:
            log.info("on-chain: %d nuovi pool tra i blocchi %d-%d",
                     len(snapshots), from_block, head)
        return snapshots

    async def fill_metadata(self, snapshots: list[PairSnapshot]) -> None:
        """Completa simbolo e nome leggendoli dal contratto ERC-20.

        Serve per poter gia' mostrare qualcosa nell'alert quando il token e'
        talmente nuovo che gli aggregatori non lo conoscono ancora.
        """
        for snapshot in snapshots:
            if snapshot.symbol:
                continue
            meta = await self.rpc.erc20_metadata(snapshot.token_address)
            snapshot.symbol = meta.get("symbol", "")
            snapshot.name = meta.get("name", "")
