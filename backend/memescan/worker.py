"""Motore: orchestra scoperta, arricchimento, controlli, punteggio e alert.

Girano tre cicli indipendenti, con periodi diversi perche' i costi lo sono:
- scan: cerca pool nuovi (veloce, quasi tutto on-chain)
- wallet: controlla i wallet tracciati (medio, dipende dall'explorer)
- track: aggiorna il prezzo dei token gia' segnalati per misurare il picco
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass

from .chain import get_rpc, probe_chain
from .config import settings
from .models import PairSnapshot, merge_snapshots, snapshot_from_row
from .notify import Notifier
from .safety import SafetyChecker, SafetyReport
from .scoring import compute_score, passes_prefilter
from .sources.blockscout import BlockscoutSource
from .sources.dexscreener import DexscreenerSource
from .sources.geckoterminal import GeckoTerminalSource
from .sources.onchain import OnchainSource
from .store import get_store
from .util import get_logger, now
from .wallets import WalletTracker

log = get_logger("memescan.worker")

# Un controllo di sicurezza costa parecchie chiamate: non si rifa' prima di
# questo intervallo sullo stesso token, salvo che arrivi un segnale wallet.
SAFETY_CACHE_SECONDS = 1_800

# Motivi di scarto definitivi: il token non viene piu' rivalutato.
PERMANENT_REJECTIONS = {
    "honeypot_probabile", "mint_aperto", "blacklist", "no_code", "segnalato_scam",
    "mai_partito",
}

# Quante letture di metadati ERC-20 fare per giro sui pool ancora senza dati.
MAX_METADATA_LOOKUPS = 40


@dataclass(slots=True)
class _CachedSafety:
    report: SafetyReport
    checked_at: int


class Engine:
    def __init__(self) -> None:
        self.store = get_store()
        self.rpc = get_rpc()
        self.dexscreener = DexscreenerSource()
        self.geckoterminal = GeckoTerminalSource()
        self.blockscout = BlockscoutSource()
        self.onchain = OnchainSource()
        self.safety = SafetyChecker(self.blockscout)
        self.wallets = WalletTracker(self.blockscout)
        self.notifier = Notifier()

        self._safety_cache: dict[str, _CachedSafety] = {}
        self._running = False
        self._tasks: list[asyncio.Task] = []
        self.status: dict = {
            "started_at": 0,
            "last_scan": 0,
            "last_wallet_poll": 0,
            "scans": 0,
            "errors": 0,
            "chain_ok": False,
        }

    # -- ciclo di vita ------------------------------------------------------

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        self.status["started_at"] = now()

        chain = await probe_chain()
        self.status["chain_ok"] = chain["ok"]
        if not chain["ok"]:
            log.error(
                "l'RPC non risponde come atteso; lo scanner parte comunque e riprova, "
                "ma controlla RPC_URL nel file .env"
            )

        self.wallets.sync_from_config()
        warnings = []
        missing = settings.missing_required()
        if missing:
            warnings.append("Configurazione incompleta: " + ", ".join(missing))
        if not self.store.list_tracked_wallets():
            warnings.append(
                "Nessun wallet tracciato: usa `discover-wallets` per popolarli automaticamente"
            )

        await self.notifier.send_startup(
            {
                "chain_id": chain.get("chain_id"),
                "block_number": chain.get("block_number", 0),
                "wallets": len(self.store.list_tracked_wallets()),
                "warnings": warnings,
            }
        )

        self._tasks = [
            asyncio.create_task(self._loop("scan", self.scan_once, settings.scan_interval_seconds)),
            asyncio.create_task(
                self._loop("wallet", self.wallet_once, settings.wallet_poll_seconds)
            ),
            asyncio.create_task(
                self._loop("track", self.track_once, settings.enrich_interval_seconds * 5)
            ),
        ]
        log.info("motore avviato: %d cicli attivi", len(self._tasks))

    async def stop(self) -> None:
        self._running = False
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        await asyncio.gather(
            self.dexscreener.close(),
            self.geckoterminal.close(),
            self.blockscout.close(),
            self.notifier.close(),
            self.rpc.close(),
            return_exceptions=True,
        )
        log.info("motore fermato")

    async def _loop(self, name: str, func, interval: int) -> None:
        """Esegue `func` a intervalli regolari senza morire su un errore.

        Un errore singolo (RPC che fa i capricci, explorer giu') non deve
        fermare lo scanner: si logga, si allunga la pausa e si riprova.
        """
        failures = 0
        while self._running:
            started = time.monotonic()
            try:
                await func()
                failures = 0
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                failures += 1
                self.status["errors"] += 1
                log.exception("errore nel ciclo %s (%d di fila): %s", name, failures, exc)
                if failures == 5:
                    await self.notifier.send_error(
                        f"Il ciclo «{name}» fallisce da 5 giri: {exc}"
                    )
            elapsed = time.monotonic() - started
            # Backoff progressivo quando qualcosa continua a rompersi.
            delay = interval * min(8, 2**failures) if failures else interval
            await asyncio.sleep(max(1.0, delay - elapsed))

    # -- ciclo di scansione -------------------------------------------------

    async def scan_once(self) -> None:
        if not self.onchain.factories:
            await self.onchain.bootstrap(self.dexscreener)

        results = await asyncio.gather(
            self.onchain.discover(),
            self.geckoterminal.discover(),
            self.dexscreener.discover(),
            return_exceptions=True,
        )
        snapshots: list[PairSnapshot] = []
        for source_name, result in zip(("onchain", "geckoterminal", "dexscreener"), results):
            if isinstance(result, Exception):
                log.warning("sorgente %s in errore: %s", source_name, result)
                continue
            snapshots.extend(result)

        # I pool visti nei giri precedenti ma ancora senza dati di mercato
        # rientrano in coda: e' li' che stanno i lanci appena nati.
        snapshots.extend(snapshot_from_row(row) for row in self.store.list_pending())
        expired = self.store.expire_pending()
        if expired:
            log.debug("%d pool scaduti senza mai partire", expired)

        self.status["last_scan"] = now()
        self.status["scans"] += 1
        if snapshots:
            await self.process(snapshots)

    async def process(self, snapshots: list[PairSnapshot]) -> None:
        """Pipeline completa su un lotto di snapshot."""
        merged = self._merge_by_token(snapshots)
        candidates: list[PairSnapshot] = []

        for snapshot in merged.values():
            existing = self.store.get_candidate(snapshot.token_address)
            if existing and existing.get("status") == "rejected":
                if existing.get("reject_reason") in PERMANENT_REJECTIONS:
                    continue
            candidates.append(snapshot)

        if not candidates:
            return

        # Chi arriva dagli eventi on-chain non ha prezzi: li chiede in blocco.
        needs_market_data = [c.token_address for c in candidates if c.liquidity_usd <= 0]
        if needs_market_data:
            enriched = await self.dexscreener.get_tokens(needs_market_data[:120])
            for address, market in enriched.items():
                if address in merged:
                    merge_snapshots(merged[address], market)

        # I token che nessun aggregatore conosce ancora restano in attesa: dargli
        # un punteggio sulla sola sicurezza sarebbe inventare un dato, e fargli
        # i controlli costosi sarebbe sprecare chiamate su pool che nel 90% dei
        # casi non partiranno mai.
        ready, pending = [], []
        for snapshot in candidates:
            (ready if snapshot.has_market_data else pending).append(snapshot)

        if pending:
            await self._park_pending(pending)

        for snapshot in ready:
            try:
                await self._evaluate(snapshot)
            except Exception as exc:
                log.warning("valutazione fallita per %s: %s", snapshot.token_address, exc)

        if ready:
            log.info("valutati %d token (%d in attesa di dati)", len(ready), len(pending))

    async def _park_pending(self, snapshots: list[PairSnapshot]) -> None:
        """Mette da parte i pool senza dati, completando nome e simbolo.

        Il simbolo si legge dal contratto ERC-20 con una sola richiesta in
        batch: serve a poterli riconoscere nella dashboard mentre aspettano.
        """
        missing_symbol = [s for s in snapshots if not s.symbol][:MAX_METADATA_LOOKUPS]
        if missing_symbol:
            await self.onchain.fill_metadata(missing_symbol)
        for snapshot in snapshots:
            row = snapshot.to_row()
            row.update({"status": "pending", "score": 0})
            self.store.upsert_candidate(row)

    @staticmethod
    def _merge_by_token(snapshots: list[PairSnapshot]) -> dict[str, PairSnapshot]:
        merged: dict[str, PairSnapshot] = {}
        for snapshot in snapshots:
            if not snapshot.token_address:
                continue
            if snapshot.token_address in merged:
                merge_snapshots(merged[snapshot.token_address], snapshot)
            else:
                merged[snapshot.token_address] = snapshot
        return merged

    async def _evaluate(self, snapshot: PairSnapshot, force: bool = False) -> None:
        token = snapshot.token_address
        wallet_hits = self.wallets.convergence(token)

        ok, reason = passes_prefilter(snapshot)
        # Un segnale dai wallet tracciati vale piu' dei filtri di mercato:
        # se hanno comprato, il token viene valutato comunque.
        if not ok and not wallet_hits and not force:
            # Azzera il punteggio: un token scartato dal prefiltro non deve
            # restare in classifica col voto di una valutazione precedente.
            row = snapshot.to_row()
            row.update({"status": "rejected", "reject_reason": reason, "score": 0})
            self.store.upsert_candidate(row)
            return

        report = await self._get_safety(token, snapshot, bypass_cache=bool(wallet_hits))
        score = compute_score(snapshot, report, wallet_hits)

        row = snapshot.to_row()
        row.update(
            {
                "score": score.total,
                "safety_json": report.to_dict(),
                "breakdown_json": score.to_dict(),
                "holders": report.holders or snapshot.holders,
                "wallet_hits": wallet_hits,
            }
        )

        if report.blocking:
            blocking_codes = {f["code"] for f in report.blocking}
            permanent = blocking_codes & PERMANENT_REJECTIONS
            row.update(
                {
                    "status": "rejected",
                    "reject_reason": (sorted(permanent)[0] if permanent
                                      else sorted(blocking_codes)[0]),
                }
            )
            self.store.upsert_candidate(row)
            log.debug("scartato %s: %s", snapshot.symbol or token, report.reason())
            return

        existing = self.store.get_candidate(token)
        already_alerted = bool(existing and existing.get("alerted_at"))
        row["status"] = "alerted" if already_alerted else "watch"
        self.store.upsert_candidate(row)

        should_alert = score.total >= settings.alert_min_score or (
            wallet_hits >= settings.wallet_convergence_threshold
        )
        if not should_alert:
            return
        if self.store.recently_alerted(token, settings.alert_cooldown_minutes * 60):
            return

        sent = await self.notifier.send_candidate(snapshot, report, score, wallet_hits)
        self.store.mark_alerted(token, score.total, snapshot.price_usd, snapshot.market_cap)
        self.store.record_alert(
            token,
            kind="wallet" if wallet_hits else "score",
            score=score.total,
            payload={
                "symbol": snapshot.symbol,
                "liquidity_usd": snapshot.liquidity_usd,
                "market_cap": snapshot.market_cap,
                "safety": report.to_dict(),
                "score": score.to_dict(),
                "delivered": sent,
            },
        )
        log.info(
            "ALERT %s punteggio %.0f (wallet %d, sicurezza %s)",
            snapshot.symbol or token, score.total, wallet_hits, report.verdict,
        )

    async def _get_safety(
        self, token: str, snapshot: PairSnapshot, bypass_cache: bool = False
    ) -> SafetyReport:
        cached = self._safety_cache.get(token)
        if cached and not bypass_cache and (now() - cached.checked_at) < SAFETY_CACHE_SECONDS:
            return cached.report
        report = await self.safety.check(snapshot)
        if report.checked:
            self._safety_cache[token] = _CachedSafety(report=report, checked_at=now())
            # La cache non deve crescere all'infinito su un processo che gira per settimane.
            if len(self._safety_cache) > 3_000:
                oldest = sorted(self._safety_cache.items(), key=lambda kv: kv[1].checked_at)
                for key, _ in oldest[:1_000]:
                    self._safety_cache.pop(key, None)
        return report

    # -- ciclo wallet -------------------------------------------------------

    async def wallet_once(self) -> None:
        events = await self.wallets.poll()
        self.status["last_wallet_poll"] = now()
        if not events:
            return

        by_token: dict[str, list[dict]] = {}
        for event in events:
            by_token.setdefault(event["token_address"], []).append(event)

        market = await self.dexscreener.get_tokens(list(by_token)[:30])

        for token, token_events in by_token.items():
            snapshot = market.get(token)
            if snapshot is None:
                snapshot = PairSnapshot(
                    token_address=token,
                    symbol=token_events[0].get("symbol", ""),
                    source="wallet",
                )
            distinct = self.store.count_distinct_wallet_buyers(token)

            # Alert immediato solo sulla convergenza: un singolo acquisto entra
            # nella pipeline normale e viene notificato se il punteggio regge.
            if distinct >= settings.wallet_convergence_threshold:
                if not self.store.recently_alerted(token, settings.alert_cooldown_minutes * 60):
                    await self.notifier.send_wallet_alert(token_events, snapshot)
                    self.store.record_alert(
                        token, kind="convergenza_wallet", score=0,
                        payload={"wallets": sorted({e["wallet"] for e in token_events})},
                    )
                    self.store.mark_alerted(
                        token, 0, snapshot.price_usd, snapshot.market_cap
                    )
            await self._evaluate(snapshot, force=True)

    # -- ciclo di tracciamento ---------------------------------------------

    async def track_once(self) -> None:
        """Aggiorna il picco dei token segnalati: e' la verifica del sistema.

        Senza questo non c'e' modo di sapere se le regole stanno funzionando.
        Il numero che conta e' il multiplo di picco medio sulle chiamate, non
        quante notifiche sono partite.
        """
        tracked = self.store.active_tokens_for_tracking()
        if not tracked:
            return
        addresses = [row["token_address"] for row in tracked]
        market = await self.dexscreener.get_tokens(addresses)
        for address, snapshot in market.items():
            if snapshot.price_usd > 0:
                self.store.update_peak(address, snapshot.price_usd)
                row = snapshot.to_row()
                row.pop("token_address", None)
                self.store.upsert_candidate({"token_address": address, **row})

    # -- uso una tantum -----------------------------------------------------

    async def rescan_token(self, token_address: str) -> dict:
        """Valuta un singolo token su richiesta (dashboard o CLI)."""
        snapshot = await self.dexscreener.get_token(token_address)
        if snapshot is None:
            existing = self.store.get_candidate(token_address)
            if not existing:
                return {"error": "token non trovato su questa chain"}
            snapshot = snapshot_from_row(existing)
        await self._evaluate(snapshot, force=True)
        row = self.store.get_candidate(snapshot.token_address)
        return row or {"error": "valutazione non riuscita"}
