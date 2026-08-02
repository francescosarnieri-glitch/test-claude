"""Wallet tracker: seguire chi compra bene invece di cercare i token.

E' il modulo che da' il segnale piu' anticipato. Un token compare su
Dexscreener quando ha gia' volume; un wallet bravo lo compra prima che quel
volume esista. Quando due o tre wallet indipendenti comprano la stessa cosa
nel giro di poche ore, quella convergenza vale piu' di qualsiasi metrica.
"""

from __future__ import annotations

import asyncio
from collections import Counter, defaultdict
from datetime import datetime, timezone

from . import tunables
from .chain import TOPIC_TRANSFER, get_rpc
from .config import settings
from .store import get_store
from .util import get_logger, now, safe_int

log = get_logger("memescan.wallets")

# Ricevere uno di questi non e' "comprare un meme": e' incassare o cambiare.
IGNORED_SYMBOLS = {
    "WETH", "ETH", "USDC", "USDT", "USDG", "DAI", "USDS", "WBTC", "FRAX", "HOOD", "WHOOD",
}


def _parse_timestamp(value: str | int) -> int:
    if isinstance(value, int):
        return value
    if not value:
        return now()
    if value.isdigit():
        return int(value)
    try:
        return int(
            datetime.fromisoformat(value.replace("Z", "+00:00"))
            .astimezone(timezone.utc)
            .timestamp()
        )
    except ValueError:
        return now()


class WalletTracker:
    def __init__(self, blockscout) -> None:
        self.blockscout = blockscout
        self.rpc = get_rpc()
        self.store = get_store()

    def sync_from_config(self) -> None:
        """Porta nel database i wallet elencati in TRACKED_WALLETS."""
        for address in settings.tracked_wallets:
            if address.startswith("0x") and len(address) == 42:
                self.store.add_tracked_wallet(address, label="da .env", origine="mia")

    async def poll(self) -> list[dict]:
        """Cerca movimenti nuovi sui wallet tracciati, acquisti e vendite.

        Ritorna solo gli eventi mai visti prima: la deduplica e' sull'hash della
        transazione, quindi un riavvio del processo non rigenera vecchi alert.
        """
        wallets = self.store.list_tracked_wallets()
        if not wallets:
            return []

        new_events: list[dict] = []
        for wallet in wallets:
            address = wallet["address"]
            try:
                transfers = await self.blockscout.token_transfers(address, limit=40)
            except Exception as exc:  # pragma: no cover - dipende dalla rete
                log.debug("lettura trasferimenti fallita per %s: %s", address, exc)
                continue

            highest_block = safe_int(wallet.get("last_block"))
            for transfer in transfers:
                block_number = transfer.get("block_number", 0)
                highest_block = max(highest_block, block_number)

                symbol = (transfer.get("symbol") or "").upper()
                if symbol in IGNORED_SYMBOLS:
                    continue

                if transfer.get("to") == address:
                    direction = "buy"
                elif transfer.get("from") == address:
                    direction = "sell"
                else:
                    continue

                event = {
                    "wallet": address,
                    "token_address": transfer["token_address"],
                    "symbol": transfer.get("symbol", ""),
                    "direction": direction,
                    "amount": transfer.get("amount", 0),
                    "tx_hash": transfer.get("tx_hash", ""),
                    "block_number": block_number,
                    "ts": _parse_timestamp(transfer.get("timestamp", "")),
                }
                if not event["token_address"] or not event["tx_hash"]:
                    continue

                is_new = self.store.record_wallet_event(event)
                # Alla primissima sincronizzazione lo storico e' tutto "nuovo":
                # si registra ma non si notifica, altrimenti parte una raffica.
                #
                # Escono anche le vendite, non solo gli acquisti: e' l'unico
                # momento in cui si scopre che una balena e' uscita da un token,
                # e chi legge deve poterlo riclassificare. Chi vuole solo gli
                # acquisti filtra per direction.
                if is_new and wallet.get("last_block"):
                    new_events.append(event)

            if highest_block:
                self.store.set_wallet_cursor(address, highest_block)

        if new_events:
            acquisti = sum(1 for e in new_events if e["direction"] == "buy")
            log.info(
                "wallet tracker: %d acquisti, %d vendite",
                acquisti, len(new_events) - acquisti,
            )
        return new_events

    def _window(self, window_hours: int | None) -> int:
        """Per quanto tempo un acquisto vale come segnale, in secondi.

        Non e' una costante ma un'impostazione: quanto ci si voglia fidare di
        un acquisto di ieri e' un giudizio, non un fatto tecnico.
        """
        if window_hours is None:
            window_hours = tunables.get("wallet_window_hours")
        return int(window_hours * 3600)

    def convergence(self, token_address: str, window_hours: int | None = None) -> int:
        """Quante balene hanno comprato di recente: serve a far scattare l'alert."""
        return self.store.count_distinct_wallet_buyers(
            token_address, self._window(window_hours),
            tunables.get("max_wallet_tokens_per_day"),
        )

    def holders(self, token_address: str, window_hours: int | None = None) -> int:
        """Balene entrate di recente e ancora dentro: il segnale vivo.

        Stessa finestra della convergenza, cosi' i due numeri parlano dello
        stesso periodo e differiscono solo per chi nel frattempo ha venduto.
        """
        return self.store.count_wallet_holders(
            token_address, self._window(window_hours),
            tunables.get("max_wallet_tokens_per_day"),
        )

    # -- scoperta automatica di wallet bravi --------------------------------

    async def _block_at_timestamp(self, target_ts: int) -> int:
        """Trova per bisezione il blocco piu' vicino a un timestamp.

        Serve perche' l'unico dato che abbiamo di un token e' quando e' nato,
        mentre eth_getLogs ragiona per numero di blocco.
        """
        high = await self.rpc.block_number()
        low = 1
        while low < high:
            mid = (low + high) // 2
            ts = await self.rpc.get_block_timestamp(mid)
            if not ts:
                break
            if ts < target_ts:
                low = mid + 1
            else:
                high = mid
        return max(1, low)

    async def _early_buyers(self, token_address: str, created_at: int, window: int = 900) -> set[str]:
        """Indirizzi che hanno ricevuto il token nei primi minuti di vita.

        Si legge dalla blockchain una volta sola nella vita della moneta e poi
        si tiene: chi e' arrivato presto nei primi quindici minuti e' un fatto
        chiuso, non cambia piu'. Prima veniva riletto a ogni ricerca, sempre
        identico, e siccome trovare il punto giusto della blockchain costa una
        ventina di domande al nodo per moneta era il motivo per cui la ricerca
        poteva girare solo ogni sei ore.
        """
        gia_noti = self.store.early_buyers_noti(token_address)
        if gia_noti is not None:
            return gia_noti
        if not created_at:
            return set()
        start_block = await self._block_at_timestamp(created_at)
        end_block = await self._block_at_timestamp(created_at + window)
        if end_block <= start_block:
            return set()

        buyers: set[str] = set()
        cursor = start_block
        while cursor <= end_block:
            chunk_end = min(cursor + 5_000 - 1, end_block)
            logs = await self.rpc.get_logs(
                from_block=cursor,
                to_block=chunk_end,
                address=token_address,
                topics=[TOPIC_TRANSFER],
            )
            for entry in logs:
                topics = entry.get("topics") or []
                if len(topics) < 3:
                    continue
                receiver = "0x" + topics[2][-40:].lower()
                buyers.add(receiver)
            cursor = chunk_end + 1
        self.store.salva_early_buyers(token_address, buyers)
        return buyers

    async def _compra_troppo(self, address: str, limite: int) -> int:
        """Quanti token diversi ha comprato di recente. 0 se non si sa.

        Il conteggio dei vincenti da solo non basta a distinguere una balena da
        uno sniper: chi compra tutto quello che nasce finisce per forza su
        qualunque token poi esploso, e piu' si abbassa l'asticella piu' sono i
        bot ad avere la meglio. Questo invece li separa davvero, perche' guarda
        quante cose comprano invece di quante ne indovinano.
        """
        if limite <= 0:
            return 0
        try:
            transfers = await self.blockscout.token_transfers(address, limit=40)
        except Exception as exc:  # pragma: no cover - dipende dalla rete
            log.debug("attivita' non verificabile per %s: %s", address, exc)
            return 0
        recenti = {
            t.get("token_address")
            for t in transfers
            if t.get("to") == address
            and t.get("token_address")
            and _parse_timestamp(t.get("timestamp", "")) > now() - 86400
        }
        return len(recenti)

    async def discover_top_traders(
        self, dexscreener, geckoterminal, min_winners: int | None = None, top: int = 30
    ) -> list[dict]:
        """Trova wallet che erano presto su piu' token poi esplosi.

        Un wallet fortunato compare in un vincitore. Un wallet bravo compare in
        tre. Si prendono i token che hanno gia' fatto un buon movimento, si
        guarda chi c'era nei primi minuti, e si tengono gli indirizzi che si
        ripetono. I contratti (pool, router, aggregatori) vengono esclusi
        perche' non sono operatori, e chi compra troppo viene escluso perche'
        non sta scegliendo niente.
        """
        if min_winners is None:
            min_winners = tunables.get("wallet_min_winners")
        limite_bot = tunables.get("max_wallet_tokens_per_day")
        self.vincenti_esaminati = 0
        winners = []

        # Vincitori gia' osservati da noi: sono i piu' affidabili perche'
        # sappiamo esattamente quando li abbiamo visti nascere.
        for row in self.store.list_candidates(limit=200):
            if (row.get("peak_multiple") or 0) >= 3 and row.get("pair_created_at"):
                winners.append((row["token_address"], row["pair_created_at"]))

        # Piu' quelli che il mercato indica come vincenti in questo momento.
        try:
            trending = await geckoterminal.trending()
        except Exception:
            trending = []
        for snapshot in trending:
            if snapshot.price_change_24h > 200 and snapshot.pair_created_at:
                winners.append((snapshot.token_address, snapshot.pair_created_at))

        if not winners:
            log.warning(
                "nessun token vincente disponibile: lascia girare lo scanner qualche "
                "giorno e riprova, oppure aggiungi wallet a mano."
            )
            return []

        # Dedup mantenendo l'ordine.
        seen: set[str] = set()
        unique_winners = []
        for token, created in winners:
            if token not in seen:
                seen.add(token)
                unique_winners.append((token, created))
        unique_winners = unique_winners[:25]
        # Serve a chi chiama per distinguere "non ho trovato nessuno" da "non
        # avevo niente da guardare": sono due situazioni opposte.
        self.vincenti_esaminati = len(unique_winners)

        # Non si puo' chiedere di comparire su quattro vincenti quando i
        # vincenti disponibili sono tre: e' un requisito impossibile, non
        # severo. E' quello che e' successo dopo una pulizia del database, con
        # la memoria azzerata e nessun token ancora esploso da cui partire.
        # L'asticella scende quando c'e' poco materiale e risale da sola.
        soglia = max(2, min(min_winners, len(unique_winners) // 2))
        log.info(
            "analizzo i primi acquirenti di %d token vincenti, servono %d presenze",
            len(unique_winners), soglia,
        )

        counter: Counter[str] = Counter()
        appearances: dict[str, list[str]] = defaultdict(list)
        for token, created_at in unique_winners:
            try:
                buyers = await self._early_buyers(token, created_at)
            except Exception as exc:  # pragma: no cover - dipende dalla rete
                log.debug("primi acquirenti non recuperabili per %s: %s", token, exc)
                continue
            for buyer in buyers:
                counter[buyer] += 1
                appearances[buyer].append(token)
            await asyncio.sleep(0)  # cede il controllo tra un token e l'altro

        candidates = [(addr, count) for addr, count in counter.items() if count >= soglia]
        candidates.sort(key=lambda item: item[1], reverse=True)

        results: list[dict] = []
        for address, count in candidates[: top * 3]:
            if len(results) >= top:
                break
            if address in ("0x" + "0" * 40,):
                continue
            code = await self.rpc.get_code(address)
            if code and code != "0x":
                continue  # e' un contratto, non un operatore
            attivita = await self._compra_troppo(address, limite_bot)
            if attivita > limite_bot:
                log.info(
                    "scartato %s: ha comprato %d token diversi in un giorno",
                    address[:10], attivita,
                )
                continue
            results.append(
                {"address": address, "winners": count, "tokens": appearances[address][:5]}
            )

        for entry in results:
            self.store.add_tracked_wallet(
                entry["address"],
                label=f"early su {entry['winners']} vincenti",
                origine="scanner",
                win_rate=entry["winners"],
            )
        log.info("trovati %d wallet candidati e salvati come tracciati", len(results))
        return results
