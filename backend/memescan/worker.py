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

from . import backup, clones, tunables
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
from .util import adesso_in_italia, get_logger, now, safe_float
from .wallets import WalletTracker

log = get_logger("memescan.worker")

# Un controllo di sicurezza costa parecchie chiamate: non si rifa' prima di
# questo intervallo sullo stesso token, salvo che arrivi un segnale wallet.
SAFETY_CACHE_SECONDS = 1_800

# Motivi di scarto definitivi: il token non viene piu' rivalutato.
PERMANENT_REJECTIONS = {
    "honeypot_probabile", "mint_aperto", "blacklist", "no_code", "segnalato_scam",
    "mai_partito", "clone_sospetto",
}

# Quante letture di metadati ERC-20 fare per giro sui pool ancora senza dati.
MAX_METADATA_LOOKUPS = 40

# Sotto questa soglia la ricerca automatica dei wallet riparte da sola: la
# componente wallet vale 25 punti, con la lista vuota il punteggio massimo
# raggiungibile scende a 75 e le soglie alte diventano irraggiungibili.
MIN_TRACKED_WALLETS = 10

# La ricerca legge i primi acquirenti di decine di token: si rifa' di rado.
WALLET_DISCOVERY_INTERVAL = 6 * 3600

# Quanto aspettare dopo un giro che non ha trovato nessuna whale nuova.
WALLET_DISCOVERY_RETRY = 48 * 3600

# Il backup si fa una volta al giorno a un'ora precisa, quindi il ciclo deve
# svegliarsi piu' spesso dell'ora: quasi sempre guarda l'orologio e torna a
# dormire. Dieci minuti bastano e non pesano.
BACKUP_CHECK_INTERVAL = 600


def alert_kind(consigliato: bool, wallet_hits: int) -> str:
    """In quale delle tre caselle finisce il token. Sono esclusive.

    - scanner_whales: lo scanner lo consiglierebbe da solo E le balene lo hanno
      comprato. E' il caso raro in cui due giudizi indipendenti coincidono.
    - whales: lo hanno comprato le balene ma da solo non reggerebbe la soglia.
      Arriva lo stesso, perche' il fatto che l'abbia preso una balena e' una
      notizia anche quando i numeri del token non impressionano.
    - scanner: lo scanner lo consiglia da solo, senza balene dentro.
    - scaduto: non lo consiglia nessuno dei due. Ci finisce il token che le
      balene hanno rivenduto e che da solo non regge la soglia: il motivo per
      cui era stato segnalato non esiste piu', e non ha senso mostrarlo tra i
      consigli dello scanner solo perche' non ha piu' balene dentro.

    `consigliato` va calcolato sul punteggio senza i punti delle balene
    (Score.own). Usare il totale renderebbe la distinzione circolare: le balene
    valgono venticinque punti, quindi basterebbe che comprassero per far dire
    allo scanner che lo consiglia.
    """
    if wallet_hits and consigliato:
        return "scanner_whales"
    if wallet_hits:
        return "whales"
    if consigliato:
        return "scanner"
    return "scaduto"


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
        self._discovering = False
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
                "Nessuna whale tracciata: usa il tasto «Cerca le whales» nella dashboard"
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
            asyncio.create_task(
                self._loop(
                    "wallet-discovery", self.discover_wallets_once, WALLET_DISCOVERY_INTERVAL
                )
            ),
            asyncio.create_task(
                self._loop("backup", self.backup_once, BACKUP_CHECK_INTERVAL)
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
        # Conta le balene ancora dentro, non quelle che sono passate: se hanno
        # comprato e poi rivenduto, il loro giudizio sul token e' cambiato e
        # non deve continuare a valere venticinque punti.
        wallet_hits = self.wallets.holders(token)

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

        # Il controllo sui cloni viene prima di quelli di sicurezza: una copia
        # ha spesso un contratto impeccabile, quindi i controlli anti-rug la
        # promuoverebbero, e sarebbero comunque chiamate sprecate.
        if tunables.get("clone_guard"):
            verdict = await clones.check(self.dexscreener, snapshot)
            if verdict.is_clone:
                row = snapshot.to_row()
                row.update(
                    {
                        "status": "rejected",
                        "reject_reason": "clone_sospetto",
                        "score": 0,
                        "safety_json": {"verdict": "clone", "clone": verdict.to_dict()},
                    }
                )
                self.store.upsert_candidate(row)
                log.info("scartato come clone: $%s %s", snapshot.symbol, token[:10])
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

        soglia = tunables.get("alert_min_score")
        # Cosa fa scattare la notifica: invariato, sul punteggio pieno. Un token
        # che arriva a settanta grazie alle balene deve continuare ad arrivare.
        by_score = score.total >= soglia
        by_wallets = wallet_hits >= tunables.get("wallet_convergence_threshold")
        # In quale casella finisce: sul punteggio senza le balene, altrimenti
        # basterebbe che comprassero per farlo risultare consigliato dallo
        # scanner, e le tre caselle direbbero tutte la stessa cosa.
        kind = alert_kind(score.own >= soglia, wallet_hits)

        # Un token gia' segnalato resta in elenco per sempre, ma le balene nel
        # frattempo entrano ed escono: la sua etichetta va rifatta ogni volta.
        #
        # E qui finisce: un token gia' segnalato non ri-notifica per il solo
        # fatto di essere ancora sopra soglia. Prima ripartiva a ogni scadenza
        # del cooldown, e con sessanta token in elenco voleva dire una notifica
        # ogni pochi minuti, tutte cose gia' viste. Le uniche novita' vere sono
        # le balene che entrano (qui sotto) e il prezzo che raddoppia (nel ciclo
        # di tracciamento).
        if already_alerted:
            await self._reclassify(token, existing, kind, snapshot, wallet_hits)
            return

        if not (by_score or by_wallets):
            return

        if self.store.recently_alerted(token, tunables.get("alert_cooldown_minutes") * 60):
            return

        sent = await self.notifier.send_candidate(snapshot, report, score, wallet_hits, kind)
        self.store.mark_alerted(
            token, score.total, snapshot.price_usd, snapshot.market_cap, kind
        )
        self.store.record_alert(
            token,
            kind=kind,
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
            "ALERT [%s] %s punteggio %.0f (wallet %d, sicurezza %s)",
            kind, snapshot.symbol or token, score.total, wallet_hits, report.verdict,
        )

    async def _reclassify(
        self, token: str, existing: dict, kind: str, snapshot: PairSnapshot, wallet_hits: int
    ) -> bool:
        """Aggiorna l'etichetta di un alert gia' mandato. Ritorna True se avvisa.

        Il passaggio che vale una notifica e' uno solo: un token trovato dal
        punteggio in cui poi entra una balena. E' il momento in cui due segnali
        indipendenti si trovano d'accordo, ed e' l'unica cosa che il vecchio
        codice buttava via. L'uscita delle balene invece si registra in
        silenzio: non e' una novita' su cui agire, e svegliare il telefono ogni
        volta che qualcuno vende renderebbe inutili tutti gli altri avvisi.
        """
        prima = existing.get("alert_kind") or ""
        if prima == kind:
            return False

        self.store.set_alert_kind(token, kind)
        log.info("riclassificato %s: %s -> %s", snapshot.symbol or token[:10], prima or "?", kind)

        # Solo dallo scanner puro verso le balene, e solo se l'origine era
        # davvero registrata: sui token piu' vecchi del campo alert_kind non si
        # sa da dove venissero, e un avviso inventato vale meno di nessun avviso.
        entrate = prima == "scanner" and kind == "scanner_whales"
        if not entrate:
            return False
        if self.store.recently_alerted(token, tunables.get("alert_cooldown_minutes") * 60):
            return False

        sent = await self.notifier.send_whales_joined(snapshot, wallet_hits, existing)
        self.store.record_alert(
            token,
            kind="whales_joined",
            score=float(existing.get("score") or 0),
            payload={"symbol": snapshot.symbol, "wallets": wallet_hits, "delivered": sent},
        )
        return True

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
            # Via il tracker e non lo store, cosi' usa la finestra impostata
            # dalla dashboard invece del valore predefinito.
            distinct = self.wallets.convergence(token)
            # Le vendite arrivano fin qui perche' servono a riclassificare il
            # token piu' sotto, ma un alert lo fanno scattare solo gli acquisti.
            acquisti = [e for e in token_events if e["direction"] == "buy"]

            # Alert immediato solo sulla convergenza: un singolo acquisto entra
            # nella pipeline normale e viene notificato se il punteggio regge.
            if acquisti and distinct >= tunables.get("wallet_convergence_threshold"):
                if not self.store.recently_alerted(
                    token, tunables.get("alert_cooldown_minutes") * 60
                ):
                    await self.notifier.send_wallet_alert(acquisti, snapshot)
                    self.store.record_alert(
                        token, kind="whales", score=0,
                        payload={"wallets": sorted({e["wallet"] for e in acquisti})},
                    )
                    self.store.mark_alerted(
                        token, 0, snapshot.price_usd, snapshot.market_cap, "whales"
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
        precedenti = {row["token_address"]: row for row in tracked}
        market = await self.dexscreener.get_tokens(addresses)
        for address, snapshot in market.items():
            if snapshot.price_usd > 0:
                self.store.update_peak(address, snapshot.price_usd)
                row = snapshot.to_row()
                row.pop("token_address", None)
                self.store.upsert_candidate({"token_address": address, **row})
                await self._notify_peak(address, precedenti.get(address, {}), snapshot)

    async def _notify_peak(self, token: str, prima: dict, snapshot: PairSnapshot) -> None:
        """Avvisa quando un token segnalato raddoppia, e poi a 5x e 10x.

        E' l'altra novita' che vale una notifica: non "e' ancora sopra soglia",
        ma "quello che ti avevo detto sta andando". Senza questo il ciclo di
        tracciamento aggiornava i picchi in silenzio e l'unico modo di
        accorgersene era aprire la dashboard.
        """
        entrata = safe_float(prima.get("price_at_alert"))
        if not entrata:
            return
        multiplo = snapshot.price_usd / entrata
        gia_detto = safe_float(prima.get("peak_notified"))

        traguardo = 0.0
        for soglia in (10.0, 5.0, 2.0):
            if multiplo >= soglia > gia_detto:
                traguardo = soglia
                break
        if not traguardo:
            return

        self.store.set_peak_notified(token, traguardo)
        await self.notifier.send_peak(snapshot, traguardo, multiplo, prima)
        log.info("%s ha fatto %.1fx dall'alert", snapshot.symbol or token[:10], multiplo)

    # -- scoperta dei wallet ------------------------------------------------

    async def discover_wallets_once(self) -> None:
        """Cerca whales da tracciare, ma solo se ne servono e se ha senso.

        La ricerca e' costosa (legge i primi acquirenti di decine di token) e
        non ha senso rifarla quando la lista e' gia' popolata.

        Il secondo controllo e' arrivato dopo: con i criteri stretti (quattro
        vincenti e niente bot) e' del tutto normale trovarne cinque o sei e
        non arrivare mai a dieci. Senza freno la ricerca sarebbe ripartita
        ogni sei ore per sempre, rileggendo ogni volta gli stessi token per
        arrivare alla stessa conclusione. Se un giro non trova niente di
        nuovo, si aspetta molto di piu' prima di riprovare: i token vincenti
        da cui si pescano le whales cambiano nel giro di giorni, non di ore.
        """
        if self._discovering:
            return
        if len(self.store.list_tracked_wallets()) >= MIN_TRACKED_WALLETS:
            return

        ultimo_vuoto = safe_float(self.store.get_meta("wallet_discovery_a_vuoto", "0"))
        if ultimo_vuoto and now() - ultimo_vuoto < WALLET_DISCOVERY_RETRY:
            attesa = (WALLET_DISCOVERY_RETRY - (now() - ultimo_vuoto)) / 3600
            log.debug("ricerca whales rimandata: l'ultima a vuoto, riprovo tra %.0fh", attesa)
            return

        esito = await self.discover_wallets(notify=False)
        if esito.get("found"):
            self.store.set_meta("wallet_discovery_a_vuoto", "0")
        else:
            self.store.set_meta("wallet_discovery_a_vuoto", str(now()))
            log.info(
                "nessuna whale nuova: ne ho %d, riprovo tra %d ore",
                len(self.store.list_tracked_wallets()), WALLET_DISCOVERY_RETRY // 3600,
            )

    async def discover_wallets(self, notify: bool = True) -> dict:
        """Esegue la ricerca dei wallet profittevoli e ne salva i risultati."""
        if self._discovering:
            return {"running": True, "found": 0}

        self._discovering = True
        try:
            found = await self.wallets.discover_top_traders(
                self.dexscreener, self.geckoterminal
            )
            if notify:
                if found:
                    await self.notifier.send(
                        f"🐋 <b>{len(found)} whales aggiunte al tracking</b>\n\n"
                        "Erano presto su piu' token poi esplosi. Da adesso ricevi un "
                        "alert quando comprano qualcosa di nuovo."
                    )
                else:
                    await self.notifier.send(
                        "🔎 <b>Nessuna whale trovata</b>\n\n"
                        "Serve piu' storico di token vincenti. Lascia girare lo "
                        "scanner qualche giorno e riprova."
                    )
            return {"running": False, "found": len(found)}
        finally:
            self._discovering = False

    async def backup_once(self) -> None:
        """Una copia al giorno, all'ora scelta, ora italiana.

        Il ciclo si sveglia spesso e quasi sempre non fa niente: e' l'unico
        modo di rispettare un orario preciso su un servizio che puo' riavviarsi
        in qualunque momento. Un ciclo "ogni dodici ore" partirebbe da quando
        il processo si e' acceso, quindi a un orario diverso ogni volta.

        Il segnaposto e' la data italiana dell'ultima copia riuscita: se c'e'
        gia' quella di oggi non si rifa', e se la macchina era spenta alle tre
        si recupera appena torna su invece di saltare il giorno.
        """
        if not backup.configurato():
            return

        adesso = adesso_in_italia()
        oggi = adesso.strftime("%Y-%m-%d")
        if self.store.get_meta("ultimo_backup_giorno") == oggi:
            return

        ora_scelta = tunables.get("backup_ora")
        ultimo = safe_float(self.store.get_meta("ultimo_backup", "0"))
        in_ritardo = bool(ultimo) and (now() - ultimo) > 25 * 3600
        if adesso.hour != ora_scelta and not in_ritardo and ultimo:
            return

        esito = await backup.esegui()
        if esito.get("ok"):
            self.store.set_meta("ultimo_backup_giorno", oggi)
        self.store.set_meta("ultimo_backup", str(now()))
        self.store.set_meta(
            "ultimo_backup_esito", "ok" if esito.get("ok") else esito.get("motivo", "errore")[:120]
        )
        if not esito.get("ok"):
            log.warning("backup non riuscito: %s", esito.get("motivo"))

    async def wipe(self) -> dict:
        """Riparte da zero: svuota il database e dimentica cio' che ha in mano.

        Le cache in memoria vanno svuotate insieme al database, altrimenti un
        token appena cancellato verrebbe ricostruito con il verdetto di
        sicurezza di prima e non sarebbe una ripartenza pulita.
        """
        buttati = self.store.wipe()
        self._safety_cache.clear()
        clones._cache.clear()
        self.wallets.sync_from_config()
        return {
            "ok": True,
            "candidati": buttati.get("candidates", 0),
            "alert": buttati.get("alerts", 0),
            "movimenti": buttati.get("wallet_events", 0),
            "wallet": buttati.get("tracked_wallets", 0),
        }

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
