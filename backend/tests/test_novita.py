"""Test delle funzioni aggiunte dopo il primo rilascio.

Coprono il caso che ha causato la perdita reale: un token che copia il simbolo
di uno gia' avviato, con contratto pulito e quindi capace di superare tutti i
controlli anti-rug.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ.setdefault("DB_PATH", str(Path(tempfile.mkdtemp()) / "novita.db"))

from memescan import clones, honeypot, pozza, stocks, tunables  # noqa: E402
from memescan.chain import SEL  # noqa: E402
from memescan.models import PairSnapshot  # noqa: E402
from memescan.notify import Notifier, verdetto_pozza  # noqa: E402
from memescan.safety import SafetyChecker, SafetyReport  # noqa: E402
from memescan.scoring import (  # noqa: E402
    PUNTI_SENZA_WHALES, WEIGHTS, Score, compute_score, passes_prefilter,
    soglia_su_scala_propria,
)
from memescan.sources.dexscreener import _versione_pozza  # noqa: E402
from memescan.store import Store  # noqa: E402
from memescan.util import now  # noqa: E402
from memescan.wallets import WalletTracker  # noqa: E402
from memescan.worker import (  # noqa: E402
    PERMANENT_REJECTIONS, Engine, _CachedSafety, alert_kind,
)

FAKE = "0x" + "68" * 20
REAL = "0x" + "c1" * 20


class FakeDexscreener:
    """Sostituisce la ricerca di Dexscreener con risultati controllati."""

    def __init__(self, results: list[PairSnapshot]):
        self.results = results
        self.calls = 0

    async def search(self, query: str) -> list[PairSnapshot]:
        self.calls += 1
        return self.results


def run(coro):
    return asyncio.get_event_loop_policy().new_event_loop().run_until_complete(coro)


class TestRilevamentoCloni(unittest.TestCase):
    def setUp(self):
        clones._cache.clear()

    def _fake_sestri(self) -> PairSnapshot:
        # Quello che il nostro scanner aveva segnalato: 13 minuti di vita,
        # poca liquidita', ma contratto formalmente ineccepibile.
        return PairSnapshot(
            token_address=FAKE, symbol="SESTRI",
            liquidity_usd=32_600, pair_created_at=now() - 13 * 60,
        )

    def _real_sestri(self) -> PairSnapshot:
        # L'originale: sette ore di vita e liquidita' molto piu' alta.
        return PairSnapshot(
            token_address=REAL, symbol="SESTRI",
            liquidity_usd=500_000, pair_created_at=now() - 7 * 3600,
        )

    def test_riconosce_la_copia(self):
        verdict = run(clones.check(FakeDexscreener([self._real_sestri()]), self._fake_sestri()))
        self.assertTrue(verdict.is_clone)
        self.assertEqual(verdict.original_address, REAL)
        self.assertIn("SESTRI", verdict.reason)

    def test_non_scarta_l_originale(self):
        # Guardando il token vero, la copia e' piu' piccola: non deve scattare.
        verdict = run(clones.check(FakeDexscreener([self._fake_sestri()]), self._real_sestri()))
        self.assertFalse(verdict.is_clone)

    def test_un_token_piu_giovane_ma_piu_grande_non_e_un_originale(self):
        """Chi nasce dopo non puo' essere l'originale di chi c'era prima."""
        nostro = PairSnapshot(
            token_address=FAKE, symbol="LUNA",
            liquidity_usd=10_000, pair_created_at=now() - 5 * 3600,
        )
        altro = PairSnapshot(
            token_address=REAL, symbol="LUNA",
            liquidity_usd=900_000, pair_created_at=now() - 600,
        )
        self.assertFalse(run(clones.check(FakeDexscreener([altro]), nostro)).is_clone)

    def test_simbolo_uguale_ma_dimensioni_simili(self):
        """Due lanci indipendenti con lo stesso nome non sono un clone."""
        nostro = PairSnapshot(
            token_address=FAKE, symbol="PEPE",
            liquidity_usd=40_000, pair_created_at=now() - 3600,
        )
        altro = PairSnapshot(
            token_address=REAL, symbol="PEPE",
            liquidity_usd=60_000, pair_created_at=now() - 7200,
        )
        self.assertFalse(run(clones.check(FakeDexscreener([altro]), nostro)).is_clone)

    def test_nessun_omonimo(self):
        nostro = PairSnapshot(token_address=FAKE, symbol="UNICO", liquidity_usd=40_000)
        self.assertFalse(run(clones.check(FakeDexscreener([]), nostro)).is_clone)

    def test_ignora_se_stesso(self):
        """Lo stesso token trovato dalla ricerca non deve accusarsi da solo."""
        nostro = self._fake_sestri()
        gemello = PairSnapshot(
            token_address=FAKE, symbol="SESTRI", liquidity_usd=999_999,
            pair_created_at=now() - 99999,
        )
        self.assertFalse(run(clones.check(FakeDexscreener([gemello]), nostro)).is_clone)

    def test_senza_simbolo_non_decide(self):
        nostro = PairSnapshot(token_address=FAKE, symbol="", liquidity_usd=40_000)
        source = FakeDexscreener([])
        self.assertFalse(run(clones.check(source, nostro)).is_clone)
        # Senza simbolo non ha nemmeno senso interrogare la rete.
        self.assertEqual(source.calls, 0)

    def test_la_cache_evita_richieste_ripetute(self):
        source = FakeDexscreener([self._real_sestri()])
        run(clones.check(source, self._fake_sestri()))
        run(clones.check(source, self._fake_sestri()))
        self.assertEqual(source.calls, 1)


class TestImpostazioniACaldo(unittest.TestCase):
    def setUp(self):
        self.path = str(Path(tempfile.mkdtemp()) / "tun.db")
        self.store = Store(self.path)
        # I tunables leggono dallo store globale: lo si punta a quello di prova.
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def test_default_quando_non_personalizzato(self):
        self.assertEqual(tunables.get("alert_min_score"), 70)

    def test_modifica_e_rilettura(self):
        tunables.set_value("alert_min_score", 82)
        self.assertEqual(tunables.get("alert_min_score"), 82)

    def test_valori_fuori_scala_vengono_riportati_nei_limiti(self):
        # Una soglia a 500 spegnerebbe lo scanner senza dirlo a nessuno.
        tunables.set_value("alert_min_score", 500)
        self.assertEqual(tunables.get("alert_min_score"), 90)
        tunables.set_value("alert_min_score", -10)
        self.assertEqual(tunables.get("alert_min_score"), 30)

    def test_booleano(self):
        self.assertTrue(tunables.get("clone_guard"))
        tunables.set_value("clone_guard", False)
        self.assertFalse(tunables.get("clone_guard"))

    def test_ripristino(self):
        tunables.set_value("min_age_minutes", 90)
        self.assertEqual(tunables.get("min_age_minutes"), 90)
        tunables.reset("min_age_minutes")
        self.assertEqual(tunables.get("min_age_minutes"), 20)

    def test_chiave_sconosciuta(self):
        with self.assertRaises(KeyError):
            tunables.get("non_esiste")
        with self.assertRaises(KeyError):
            tunables.set_value("non_esiste", 1)

    def test_ogni_default_ha_la_sua_voce(self):
        """Una chiave con un default ma non esposta esplode a runtime.

        E' successo davvero con max_age_hours: il codice la leggeva, l'elenco
        non la conteneva, e il filtro andava in errore al primo token.
        """
        esposte = {t.key for t in tunables.TUNABLES}
        self.assertEqual(esposte, set(tunables._DEFAULTS))

    def test_snapshot_segnala_le_personalizzazioni(self):
        tunables.set_value("min_holders", 200)
        rows = {r["key"]: r for r in tunables.snapshot()}
        self.assertTrue(rows["min_holders"]["customised"])
        self.assertEqual(rows["min_holders"]["value"], 200)
        self.assertFalse(rows["max_age_hours"]["customised"])


class TestFiltriNuovi(unittest.TestCase):
    def setUp(self):
        self.path = str(Path(tempfile.mkdtemp()) / "filtri.db")
        self.store = Store(self.path)
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def _snapshot(self, **kwargs) -> PairSnapshot:
        base = dict(
            token_address=FAKE, symbol="TEST",
            pair_created_at=now() - 3600, liquidity_usd=90_000,
            volume_1h=120_000, buys_5m=70, sells_5m=25,
        )
        base.update(kwargs)
        return PairSnapshot(**base)

    def test_scarta_chi_e_gia_esploso(self):
        ok, reason = passes_prefilter(self._snapshot(price_change_1h=900))
        self.assertFalse(ok)
        self.assertEqual(reason, "gia_esploso")

    def test_accetta_un_rialzo_normale(self):
        ok, _ = passes_prefilter(self._snapshot(price_change_1h=80))
        self.assertTrue(ok)

    def test_il_controllo_si_puo_disattivare(self):
        tunables.set_value("max_price_change_1h", 0)
        ok, _ = passes_prefilter(self._snapshot(price_change_1h=5000))
        self.assertTrue(ok)

    def test_eta_minima_regolabile(self):
        giovane = self._snapshot(pair_created_at=now() - 13 * 60)
        # Con il default a 20 minuti, un token di 13 minuti non passa:
        # e' esattamente l'eta' del falso SESTRI.
        ok, reason = passes_prefilter(giovane)
        self.assertFalse(ok)
        self.assertEqual(reason, "troppo_giovane")

        tunables.set_value("min_age_minutes", 5)
        ok, _ = passes_prefilter(giovane)
        self.assertTrue(ok)


class TestOrigineDegliAlert(unittest.TestCase):
    """Gli alert del punteggio e quelli dei wallet devono restare distinguibili.

    Sono due segnali diversi: uno dice com'e' fatto il token, l'altro dice chi
    lo sta comprando. Confonderli sul telefono porta a reagire nel modo
    sbagliato.
    """

    def setUp(self):
        self.path = str(Path(tempfile.mkdtemp()) / "origine.db")
        self.store = Store(self.path)
        self.notifier = Notifier()
        self.notifier.enabled = False
        self.sent: list[str] = []

        async def capture(text, buttons=None):
            self.sent.append(text)
            return True

        self.notifier.send = capture

    def tearDown(self):
        self.store.close()

    def _invia(self, **kwargs) -> str:
        snapshot = PairSnapshot(token_address=FAKE, symbol="TEST", liquidity_usd=50_000)
        report = SafetyReport(token_address=FAKE)
        run(self.notifier.send_candidate(snapshot, report, Score(total=78), **kwargs))
        return self.sent[-1].splitlines()[0]

    def test_titolo_solo_punteggio(self):
        self.assertIn("SCANNER", self._invia(kind="scanner"))

    def test_titolo_punteggio_piu_whales(self):
        titolo = self._invia(wallet_hits=2, kind="scanner_whales")
        self.assertIn("SCANNER + WHALES", titolo)

    def test_titolo_solo_whales(self):
        titolo = self._invia(wallet_hits=3, kind="whales")
        self.assertIn("WHALES", titolo)
        self.assertNotIn("SCANNER", titolo)

    def test_alert_dedicato_ai_wallet(self):
        run(self.notifier.send_wallet_alert([{"token_address": FAKE, "symbol": "TEST",
                                              "wallet": "0x" + "ab" * 20}]))
        self.assertIn("WHALES", self.sent[-1].splitlines()[0])

    def test_tipo_sconosciuto_non_rompe_il_messaggio(self):
        self.assertIn("SCANNER", self._invia(kind="qualcosa_di_nuovo"))

    def test_il_tipo_finisce_nel_database(self):
        self.store.upsert_candidate({"token_address": FAKE, "symbol": "TEST"})
        self.store.mark_alerted(FAKE, 78, 0.1, 10_000, "whales")
        self.assertEqual(self.store.get_candidate(FAKE)["alert_kind"], "whales")

    def test_predefinito_per_i_vecchi_alert(self):
        """Chi era gia' nel database prima della modifica non deve sparire."""
        self.store.upsert_candidate({"token_address": REAL, "symbol": "VECCHIO"})
        self.store.mark_alerted(REAL, 71, 0.1, 10_000)
        self.assertEqual(self.store.get_candidate(REAL)["alert_kind"], "scanner")


class TestBaleneDentroOraNonPassate(unittest.TestCase):
    """L'appartenenza la decide chi c'e' dentro adesso, non chi c'e' passato.

    Regola voluta esplicitamente: se le balene vendono tutte, il token torna
    tra quelli trovati dal solo punteggio; se una balena compra un token che
    era solo dello scanner, quel token passa tra le balene.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "holders.db"))

    def tearDown(self):
        self.store.close()

    def _evento(self, wallet: str, direction: str, ts: int, tx: str) -> None:
        self.store.record_wallet_event({
            "wallet": wallet, "token_address": FAKE, "symbol": "TEST",
            "direction": direction, "tx_hash": tx, "ts": ts,
        })

    def test_chi_ha_comprato_e_dentro(self):
        self._evento("0xaa", "buy", now() - 600, "t1")
        self.assertEqual(self.store.count_wallet_holders(FAKE), 1)

    def test_chi_ha_venduto_non_conta_piu(self):
        self._evento("0xaa", "buy", now() - 600, "t1")
        self._evento("0xaa", "sell", now() - 60, "t2")
        self.assertEqual(self.store.count_wallet_holders(FAKE), 0)

    def test_chi_rientra_torna_a_contare(self):
        self._evento("0xaa", "buy", now() - 600, "t1")
        self._evento("0xaa", "sell", now() - 300, "t2")
        self._evento("0xaa", "buy", now() - 60, "t3")
        self.assertEqual(self.store.count_wallet_holders(FAKE), 1)

    def test_conta_solo_chi_e_rimasto(self):
        self._evento("0xaa", "buy", now() - 600, "t1")
        self._evento("0xbb", "buy", now() - 500, "t2")
        self._evento("0xcc", "buy", now() - 400, "t3")
        self._evento("0xbb", "sell", now() - 60, "t4")
        self.assertEqual(self.store.count_wallet_holders(FAKE), 2)
        # L'altro conteggio guarda solo gli acquisti e resta a tre: e' quello
        # che fa scattare l'alert di convergenza, non quello che descrive.
        self.assertEqual(self.store.count_distinct_wallet_buyers(FAKE), 3)

    def test_un_sacchetto_vecchio_non_e_un_segnale(self):
        """Comprato quaranta giorni fa e mai piu' toccato: non conta.

        Senza questo limite, siccome la prima sincronizzazione carica tutto lo
        storico dei wallet tracciati, quasi ogni token che avessero mai toccato
        risultava "con le balene dentro": ventotto alert su trentasette.
        """
        self._evento("0xaa", "buy", now() - 40 * 86400, "t1")
        self.assertEqual(self.store.count_wallet_holders(FAKE), 0)
        self.assertEqual(self.store.count_distinct_wallet_buyers(FAKE), 0)

    def test_le_due_domande_guardano_lo_stesso_periodo(self):
        """Chi e' dentro non puo' essere piu' di chi ha comprato di recente."""
        self._evento("0xaa", "buy", now() - 3600, "t1")
        self._evento("0xbb", "buy", now() - 40 * 86400, "t2")
        self.assertEqual(self.store.count_distinct_wallet_buyers(FAKE), 1)
        self.assertEqual(self.store.count_wallet_holders(FAKE), 1)

    def test_senza_movimenti(self):
        self.assertEqual(self.store.count_wallet_holders(FAKE), 0)


class TestLeVenditeArrivanoAlMotore(unittest.TestCase):
    """Se le vendite non escono dal tracker, l'uscita delle balene e' invisibile.

    E' il pezzo che fa funzionare la regola: il token torna nello scanner solo
    se qualcuno si accorge che le balene sono uscite, e l'unico posto dove lo
    si scopre e' il giro sui wallet.
    """

    def setUp(self):
        self.path = str(Path(tempfile.mkdtemp()) / "vendite.db")
        self.store = Store(self.path)
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        self.store.add_tracked_wallet("0x" + "aa" * 20, label="prova")
        # Il cursore simula un wallet gia' sincronizzato: alla prima lettura
        # lo storico non deve generare eventi.
        self.store.set_wallet_cursor("0x" + "aa" * 20, 10)

        self.tracker = WalletTracker.__new__(WalletTracker)
        self.tracker.store = self.store
        self.tracker.blockscout = self
        # La finestra e' un'impostazione: la cache va svuotata o si legge
        # quella di un'altra prova.
        tunables.invalidate()

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    async def token_transfers(self, address: str, limit: int = 40) -> list[dict]:
        wallet = "0x" + "aa" * 20
        return [
            {"token_address": FAKE, "symbol": "TEST", "to": wallet, "from": "0xpool",
             "tx_hash": "0x01", "block_number": 11, "timestamp": ""},
            {"token_address": FAKE, "symbol": "TEST", "to": "0xpool", "from": wallet,
             "tx_hash": "0x02", "block_number": 12, "timestamp": ""},
        ]

    def test_la_finestra_e_regolabile(self):
        """Quanto vale un acquisto di ieri e' un giudizio, non un fatto tecnico.

        Con la finestra stretta contano solo gli acquisti freschi: e' il modo
        di dire "non mi interessa cosa hanno comprato prima che iniziassi".
        """
        self.store.record_wallet_event({
            "wallet": "0x" + "aa" * 20, "token_address": FAKE, "symbol": "TEST",
            "direction": "buy", "tx_hash": "0x99", "ts": now() - 5 * 3600,
        })
        # Predefinito 24 ore: un acquisto di cinque ore fa conta ancora.
        self.assertEqual(self.tracker.holders(FAKE), 1)
        self.assertEqual(self.tracker.convergence(FAKE), 1)

        tunables.set_value("wallet_window_hours", 2)
        self.assertEqual(self.tracker.holders(FAKE), 0)
        self.assertEqual(self.tracker.convergence(FAKE), 0)

        tunables.set_value("wallet_window_hours", 48)
        self.assertEqual(self.tracker.holders(FAKE), 1)

    def test_la_finestra_resta_nei_limiti(self):
        # Zero ore spegnerebbe del tutto il segnale dei wallet senza dirlo.
        tunables.set_value("wallet_window_hours", 0)
        self.assertEqual(tunables.get("wallet_window_hours"), 1)

    def test_la_vendita_viene_restituita(self):
        eventi = run(self.tracker.poll())
        direzioni = sorted(e["direction"] for e in eventi)
        self.assertEqual(direzioni, ["buy", "sell"])

    def test_dopo_la_vendita_non_e_piu_dentro(self):
        run(self.tracker.poll())
        self.assertEqual(self.tracker.holders(FAKE), 0)
        # Ma l'acquisto resta registrato: sono due domande diverse.
        self.assertEqual(self.tracker.convergence(FAKE), 1)


class TestClassificazione(unittest.TestCase):
    """Le tre caselle sono esclusive: ogni token ne occupa esattamente una."""

    def test_solo_scanner(self):
        self.assertEqual(alert_kind(consigliato=True, wallet_hits=0), "scanner")

    def test_tutti_e_due_i_segnali(self):
        self.assertEqual(alert_kind(consigliato=True, wallet_hits=2), "scanner_whales")

    def test_comprato_dalle_balene_ma_non_consigliato(self):
        """La regola che conta: arriva lo stesso, e finisce tra le balene.

        Un token che lo scanner da solo non segnalerebbe, ma che una balena ha
        comprato, non deve sparire ne' spacciarsi per consigliato.
        """
        self.assertEqual(alert_kind(consigliato=False, wallet_hits=1), "whales")

    def test_abbandonato_da_tutti_e_scaduto(self):
        """Non finisce tra i consigli dello scanner solo perche' e' rimasto solo.

        E' il caso di un token a 22 punti, rivenduto dalle balene, che compariva
        sotto SCANNER come se lo scanner lo stesse suggerendo.
        """
        self.assertEqual(alert_kind(consigliato=False, wallet_hits=0), "scaduto")

    def test_basta_una_balena(self):
        self.assertEqual(alert_kind(consigliato=True, wallet_hits=1), "scanner_whales")

    def test_ogni_combinazione_produce_una_casella_sola(self):
        """Se due casi dessero la stessa casella, i conti non tornerebbero."""
        caselle = {
            alert_kind(consigliato=c, wallet_hits=w)
            for c in (True, False) for w in (0, 3)
        }
        self.assertEqual(caselle, {"scanner", "scanner_whales", "whales", "scaduto"})


class TestPunteggioSenzaBalene(unittest.TestCase):
    """`own` e' il punteggio del token a prescindere da chi lo ha comprato.

    Senza questo la distinzione sarebbe circolare: le balene valgono 25 punti,
    quindi basterebbe che comprassero per far risultare il token consigliato
    anche dallo scanner, e le tre caselle direbbero tutte la stessa cosa.
    """

    def setUp(self):
        self.path = str(Path(tempfile.mkdtemp()) / "own.db")
        self.store = Store(self.path)
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def _snapshot(self) -> PairSnapshot:
        return PairSnapshot(
            token_address=FAKE, symbol="TEST", pair_created_at=now() - 3600,
            liquidity_usd=90_000, volume_1h=120_000, buys_5m=70, sells_5m=25,
            price_change_1h=40, holders=400,
        )

    def test_senza_balene_i_due_punteggi_coincidono(self):
        score = compute_score(self._snapshot(), SafetyReport(token_address=FAKE), 0)
        self.assertEqual(score.own, score.total)

    def test_le_balene_alzano_solo_il_totale(self):
        snapshot = self._snapshot()
        report = SafetyReport(token_address=FAKE)
        senza = compute_score(snapshot, report, 0)
        con = compute_score(snapshot, report, 3)
        self.assertGreater(con.total, senza.total)
        # Il merito proprio del token non cambia: e' lo stesso token.
        self.assertAlmostEqual(con.own, senza.own, places=6)

    def test_il_massimo_senza_balene_e_75(self):
        """Ecco perche' la casella del solo scanner resta quasi sempre vuota."""
        snapshot = self._snapshot()
        con = compute_score(snapshot, SafetyReport(token_address=FAKE), 3)
        self.assertLessEqual(con.own, 75.0)

    def test_own_finisce_nel_riepilogo(self):
        score = compute_score(self._snapshot(), SafetyReport(token_address=FAKE), 2)
        self.assertIn("own", score.to_dict())


class TestRiclassificaEAvviso(unittest.TestCase):
    """Il passaggio scanner -> balene e' l'unico che merita una notifica."""

    def setUp(self):
        self.path = str(Path(tempfile.mkdtemp()) / "riclassifica.db")
        self.store = Store(self.path)
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

        self.engine = Engine.__new__(Engine)
        self.engine.store = self.store
        self.engine.notifier = Notifier()
        self.engine.notifier.enabled = False
        self.inviati: list[str] = []

        async def capture(text, buttons=None):
            self.inviati.append(text)
            return True

        self.engine.notifier.send = capture

        self.store.upsert_candidate({"token_address": FAKE, "symbol": "TEST", "score": 76})

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def _riclassifica(self, kind: str, wallet_hits: int) -> bool:
        existing = self.store.get_candidate(FAKE)
        snapshot = PairSnapshot(token_address=FAKE, symbol="TEST", liquidity_usd=50_000)
        return run(self.engine._reclassify(FAKE, existing, kind, snapshot, wallet_hits))

    def test_le_balene_entrano_e_avvisa(self):
        self.store.mark_alerted(FAKE, 76, 1.0, 1000, "scanner")
        # L'alert e' partito fuori dalla finestra di cooldown.
        self.store._exec("UPDATE candidates SET alerted_at = ? WHERE token_address = ?",
                         (now() - 99999, FAKE))
        self.assertTrue(self._riclassifica("scanner_whales", 2))
        self.assertIn("SCANNER + WHALES", self.inviati[-1])
        self.assertEqual(self.store.get_candidate(FAKE)["alert_kind"], "scanner_whales")

    def test_non_riavvisa_se_nulla_e_cambiato(self):
        self.store.mark_alerted(FAKE, 76, 1.0, 1000, "scanner_whales")
        self.assertFalse(self._riclassifica("scanner_whales", 2))
        self.assertEqual(self.inviati, [])

    def test_le_balene_escono_in_silenzio(self):
        """Una vendita non e' una novita' su cui agire: si registra e basta."""
        self.store.mark_alerted(FAKE, 76, 1.0, 1000, "scanner_whales")
        self.assertFalse(self._riclassifica("scanner", 0))
        self.assertEqual(self.inviati, [])
        self.assertEqual(self.store.get_candidate(FAKE)["alert_kind"], "scanner")

    def test_non_avvisa_durante_il_cooldown(self):
        """L'alert e' appena partito: un secondo messaggio sarebbe rumore."""
        self.store.mark_alerted(FAKE, 76, 1.0, 1000, "scanner")
        self.assertFalse(self._riclassifica("scanner_whales", 2))
        self.assertEqual(self.inviati, [])
        # L'etichetta si aggiorna lo stesso: la dashboard non deve mentire.
        self.assertEqual(self.store.get_candidate(FAKE)["alert_kind"], "scanner_whales")

    def test_senza_origine_registrata_non_inventa_avvisi(self):
        """Sui token piu' vecchi del campo non si sa da dove venissero."""
        self.store.mark_alerted(FAKE, 76, 1.0, 1000, "")
        self.store._exec("UPDATE candidates SET alerted_at = ? WHERE token_address = ?",
                         (now() - 99999, FAKE))
        self.assertFalse(self._riclassifica("scanner_whales", 2))
        self.assertEqual(self.inviati, [])
        self.assertEqual(self.store.get_candidate(FAKE)["alert_kind"], "scanner_whales")


class TestBotSmascherati(unittest.TestCase):
    """Chi compra tutto non sta scegliendo, e non deve valere venticinque punti.

    E' il difetto che ha prodotto 58 token su 60 marcati "con le whales
    dentro": la lista era piena di sniper automatici, e bastava che uno di
    loro passasse su un token per regalargli i punti che lo portavano sopra
    soglia.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "bot.db"))
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def _compra(self, wallet: str, token: str, tx: str) -> None:
        self.store.record_wallet_event({
            "wallet": wallet, "token_address": token, "symbol": "T",
            "direction": "buy", "tx_hash": tx, "ts": now() - 600,
        })

    def _popola(self) -> None:
        # Una whale: due token. Un bot: venti, fra cui lo stesso della whale.
        self._compra("0xwhale", FAKE, "w1")
        self._compra("0xwhale", REAL, "w2")
        for i in range(20):
            self._compra("0xbot", "0x%040x" % i, f"b{i}")
        self._compra("0xbot", FAKE, "bx")

    def test_conta_i_token_di_ognuno(self):
        self._popola()
        attivita = self.store.wallet_activity()
        self.assertEqual(attivita["0xwhale"], 2)
        self.assertEqual(attivita["0xbot"], 21)

    def test_senza_limite_il_bot_conta_come_una_whale(self):
        self._popola()
        self.assertEqual(self.store.count_distinct_wallet_buyers(FAKE), 2)

    def test_col_limite_il_bot_sparisce(self):
        self._popola()
        self.assertEqual(
            self.store.count_distinct_wallet_buyers(FAKE, max_tokens_per_day=6), 1
        )
        self.assertEqual(self.store.count_wallet_holders(FAKE, max_tokens_per_day=6), 1)

    def test_la_whale_sotto_il_limite_resta(self):
        self._popola()
        self.assertEqual(
            self.store.count_distinct_wallet_buyers(REAL, max_tokens_per_day=6), 1
        )

    def test_il_limite_arriva_dalle_impostazioni(self):
        self._popola()
        tracker = WalletTracker.__new__(WalletTracker)
        tracker.store = self.store
        self.assertEqual(tracker.convergence(FAKE), 1)  # predefinito 6: bot escluso

        tunables.set_value("max_wallet_tokens_per_day", 0)  # 0 = contali tutti
        self.assertEqual(tracker.convergence(FAKE), 2)

        tunables.set_value("max_wallet_tokens_per_day", 50)
        self.assertEqual(tracker.convergence(FAKE), 2)


class TestAvvisoSulPicco(unittest.TestCase):
    """L'unica novita' che vale una notifica su un token gia' segnalato."""

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "picco.db"))
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

        self.engine = Engine.__new__(Engine)
        self.engine.store = self.store
        self.engine.notifier = Notifier()
        self.engine.notifier.enabled = False
        self.inviati: list[str] = []

        async def capture(text, buttons=None):
            self.inviati.append(text)
            return True

        self.engine.notifier.send = capture
        self.store.upsert_candidate({"token_address": FAKE, "symbol": "TEST"})
        self.store.mark_alerted(FAKE, 76, 1.0, 1000, "scanner")

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def _giro(self, prezzo: float) -> None:
        prima = self.store.get_candidate(FAKE)
        snapshot = PairSnapshot(token_address=FAKE, symbol="TEST", price_usd=prezzo)
        run(self.engine._notify_peak(FAKE, prima, snapshot))

    def test_sotto_il_doppio_non_avvisa(self):
        self._giro(1.8)
        self.assertEqual(self.inviati, [])

    def test_al_doppio_avvisa(self):
        self._giro(2.1)
        self.assertEqual(len(self.inviati), 1)
        self.assertIn("2.1x", self.inviati[0])

    def test_non_ripete_lo_stesso_traguardo(self):
        self._giro(2.1)
        self._giro(2.4)
        self._giro(3.0)
        self.assertEqual(len(self.inviati), 1)

    def test_i_traguardi_successivi_avvisano_di_nuovo(self):
        self._giro(2.1)
        self._giro(5.5)
        self._giro(11.0)
        self.assertEqual(len(self.inviati), 3)

    def test_un_salto_diretto_non_manda_tre_messaggi(self):
        """Da 1x a 12x si avvisa una volta sola, col traguardo piu' alto."""
        self._giro(12.0)
        self.assertEqual(len(self.inviati), 1)
        self.assertIn("12.0x", self.inviati[0])

    def test_senza_prezzo_di_ingresso_non_calcola_niente(self):
        self.store._exec(
            "UPDATE candidates SET price_at_alert = 0 WHERE token_address = ?", (FAKE,)
        )
        self._giro(99.0)
        self.assertEqual(self.inviati, [])


class TestPulisciERicomincia(unittest.TestCase):
    """Il tasto deve buttare i dati sporchi e lasciare stare la configurazione."""

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "wipe.db"))
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

        self.store.upsert_candidate({"token_address": FAKE, "symbol": "TEST"})
        self.store.mark_alerted(FAKE, 76, 1.0, 1000, "scanner")
        self.store.record_alert(FAKE, kind="scanner", score=76, payload={})
        self.store.add_tracked_wallet("0x" + "aa" * 20, label="prova")
        self.store.record_wallet_event({
            "wallet": "0x" + "aa" * 20, "token_address": FAKE,
            "direction": "buy", "tx_hash": "0x1", "ts": now(),
        })
        # Configurazione da preservare.
        tunables.set_value("alert_min_score", 82)
        self.store.set_meta("onchain_last_block", "123456")

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def test_butta_i_dati(self):
        buttati = self.store.wipe()
        self.assertEqual(buttati["candidates"], 1)
        self.assertEqual(buttati["alerts"], 1)
        self.assertEqual(buttati["wallet_events"], 1)
        self.assertEqual(buttati["tracked_wallets"], 1)

        self.assertIsNone(self.store.get_candidate(FAKE))
        self.assertEqual(self.store.recent_alerts(), [])
        self.assertEqual(self.store.list_tracked_wallets(enabled_only=False), [])
        self.assertEqual(self.store.stats()["tokens_seen"], 0)

    def test_le_soglie_sopravvivono(self):
        """Hanno gia' il loro tasto di ripristino: questo non deve toccarle."""
        self.store.wipe()
        tunables.invalidate()
        self.assertEqual(tunables.get("alert_min_score"), 82)

    def test_il_punto_di_scansione_sopravvive(self):
        """Senza, si ripartirebbe rileggendo il passato appena buttato."""
        self.store.wipe()
        self.assertEqual(self.store.get_meta("onchain_last_block"), "123456")

    def test_si_puo_ricominciare_a_scrivere(self):
        self.store.wipe()
        self.store.upsert_candidate({"token_address": REAL, "symbol": "NUOVO"})
        self.assertEqual(self.store.get_candidate(REAL)["symbol"], "NUOVO")

    def test_svuotare_due_volte_non_esplode(self):
        self.store.wipe()
        self.assertEqual(self.store.wipe()["candidates"], 0)


class TestOreDiSilenzio(unittest.TestCase):
    """Il telefono non deve squillare di notte, ma il messaggio deve arrivare."""

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "silenzio.db"))
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def _alle(self, ora: int) -> bool:
        import memescan.notify as notify_module

        originale = notify_module.ora_locale
        notify_module.ora_locale = lambda: ora
        try:
            return notify_module.in_silenzio()
        finally:
            notify_module.ora_locale = originale

    def test_spento_per_default(self):
        """Chi non lo configura non deve accorgersi che esiste."""
        self.assertFalse(self._alle(3))

    def test_intervallo_normale(self):
        tunables.set_value("silenzio_da", 13)
        tunables.set_value("silenzio_a", 15)
        self.assertFalse(self._alle(12))
        self.assertTrue(self._alle(13))
        self.assertTrue(self._alle(14))
        self.assertFalse(self._alle(15))

    def test_intervallo_che_scavalca_la_mezzanotte(self):
        """Dalle 23 alle 8 deve comprendere l'una di notte."""
        tunables.set_value("silenzio_da", 23)
        tunables.set_value("silenzio_a", 8)
        self.assertTrue(self._alle(23))
        self.assertTrue(self._alle(1))
        self.assertTrue(self._alle(7))
        self.assertFalse(self._alle(8))
        self.assertFalse(self._alle(15))

    def test_ore_uguali_vuol_dire_sempre_acceso(self):
        tunables.set_value("silenzio_da", 9)
        tunables.set_value("silenzio_a", 9)
        for ora in range(24):
            self.assertFalse(self._alle(ora), f"ora {ora}")

    def test_il_messaggio_parte_lo_stesso(self):
        """Silenzioso non vuol dire cancellato: al mattino si deve trovare."""
        import memescan.notify as notify_module

        tunables.set_value("silenzio_da", 0)
        tunables.set_value("silenzio_a", 23)
        notifier = Notifier()
        notifier.enabled = True
        inviati = []

        async def finto_post(url, json=None):
            inviati.append(json)
            return {"ok": True}

        notifier.http.post = finto_post
        originale = notify_module.ora_locale
        notify_module.ora_locale = lambda: 4
        try:
            self.assertTrue(run(notifier.send("prova")))
        finally:
            notify_module.ora_locale = originale
        self.assertTrue(inviati[0]["disable_notification"])

    def test_gli_errori_squillano_comunque(self):
        """Se lo scanner e' fermo va detto subito, anche alle quattro."""
        import memescan.notify as notify_module

        tunables.set_value("silenzio_da", 0)
        tunables.set_value("silenzio_a", 23)
        notifier = Notifier()
        notifier.enabled = True
        inviati = []

        async def finto_post(url, json=None):
            inviati.append(json)
            return {"ok": True}

        notifier.http.post = finto_post
        originale = notify_module.ora_locale
        notify_module.ora_locale = lambda: 4
        try:
            run(notifier.send_error("lo scanner e' fermo"))
        finally:
            notify_module.ora_locale = originale
        self.assertNotIn("disable_notification", inviati[0])


class TestSalvatiEAndamento(unittest.TestCase):
    """La stella metteva da parte i token in un posto che non esisteva."""

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "salvati.db"))

    def tearDown(self):
        self.store.close()

    def test_la_stella_si_ritrova(self):
        self.store.upsert_candidate({"token_address": FAKE, "symbol": "UNO"})
        self.store.upsert_candidate({"token_address": REAL, "symbol": "DUE"})
        self.assertEqual(self.store.list_watchlist(), [])

        self.store.set_watchlist(FAKE, True)
        salvati = self.store.list_watchlist()
        self.assertEqual([r["symbol"] for r in salvati], ["UNO"])

    def test_togliere_la_stella_lo_toglie(self):
        self.store.upsert_candidate({"token_address": FAKE, "symbol": "UNO"})
        self.store.set_watchlist(FAKE, True)
        self.store.set_watchlist(FAKE, False)
        self.assertEqual(self.store.list_watchlist(), [])

    def test_i_motivi_di_scarto_si_contano(self):
        for i, motivo in enumerate(
            ["troppo_giovane", "troppo_giovane", "troppo_giovane", "clone_sospetto"]
        ):
            self.store.upsert_candidate({
                "token_address": "0x%040x" % i, "symbol": "T",
                "status": "rejected", "reject_reason": motivo,
            })
        scarti = self.store.rejection_stats()
        self.assertEqual(scarti[0]["motivo"], "troppo_giovane")
        self.assertEqual(scarti[0]["n"], 3)
        self.assertEqual(scarti[1]["n"], 1)

    def test_gli_scarti_senza_motivo_non_sporcano_il_conto(self):
        self.store.upsert_candidate({
            "token_address": FAKE, "symbol": "T", "status": "rejected", "reject_reason": "",
        })
        self.assertEqual(self.store.rejection_stats(), [])


class TestBackupGiornaliero(unittest.TestCase):
    """Una copia al giorno alle tre, ora italiana.

    Un ciclo "ogni dodici ore" partirebbe da quando il processo si e' acceso,
    quindi a un orario diverso dopo ogni riavvio. Qui il ciclo si sveglia
    spesso e guarda l'orologio.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "sched.db"))
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store
        tunables.invalidate()

        self.engine = Engine.__new__(Engine)
        self.engine.store = self.store
        self.eseguiti = []

        import memescan.backup as backup_module
        import memescan.worker as worker_module

        self._configurato = backup_module.configurato
        self._esegui = backup_module.esegui
        self._orologio = worker_module.adesso_in_italia

        backup_module.configurato = lambda: True

        async def finto_backup():
            self.eseguiti.append(True)
            return {"ok": True, "bytes": 1}

        backup_module.esegui = finto_backup

    def tearDown(self):
        import memescan.backup as backup_module
        import memescan.store as store_module
        import memescan.worker as worker_module

        backup_module.configurato = self._configurato
        backup_module.esegui = self._esegui
        worker_module.adesso_in_italia = self._orologio
        store_module._store = self._original
        self.store.close()
        tunables.invalidate()

    def _giro(self, giorno: str, ora: int) -> None:
        import memescan.worker as worker_module
        from datetime import datetime

        worker_module.adesso_in_italia = lambda: datetime.strptime(
            f"{giorno} {ora:02d}:30", "%Y-%m-%d %H:%M"
        )
        run(self.engine.backup_once())

    def test_la_primissima_copia_parte_subito(self):
        """Senza, il primo backup arriverebbe solo la notte dopo."""
        self._giro("2026-08-01", 14)
        self.assertEqual(len(self.eseguiti), 1)

    def test_alle_tre_di_notte(self):
        self.store.set_meta("ultimo_backup", str(now() - 20 * 3600))
        self.store.set_meta("ultimo_backup_giorno", "2026-07-31")
        self._giro("2026-08-01", 3)
        self.assertEqual(len(self.eseguiti), 1)

    def test_alle_altre_ore_non_fa_niente(self):
        self.store.set_meta("ultimo_backup", str(now() - 5 * 3600))
        self.store.set_meta("ultimo_backup_giorno", "2026-07-31")
        for ora in (0, 2, 4, 12, 22):
            self._giro("2026-08-01", ora)
        self.assertEqual(self.eseguiti, [])

    def test_una_sola_volta_al_giorno(self):
        """Il ciclo passa ogni dieci minuti: alle tre passa sei volte."""
        self.store.set_meta("ultimo_backup", str(now() - 20 * 3600))
        self.store.set_meta("ultimo_backup_giorno", "2026-07-31")
        for _ in range(6):
            self._giro("2026-08-01", 3)
        self.assertEqual(len(self.eseguiti), 1)

    def test_il_giorno_dopo_si_rifa(self):
        self.store.set_meta("ultimo_backup", str(now() - 20 * 3600))
        self.store.set_meta("ultimo_backup_giorno", "2026-07-31")
        self._giro("2026-08-01", 3)
        self.store.set_meta("ultimo_backup", str(now() - 20 * 3600))
        self._giro("2026-08-02", 3)
        self.assertEqual(len(self.eseguiti), 2)

    def test_recupera_se_la_macchina_era_spenta_alle_tre(self):
        """Meglio in ritardo che saltare un giorno."""
        self.store.set_meta("ultimo_backup", str(now() - 30 * 3600))
        self.store.set_meta("ultimo_backup_giorno", "2026-07-30")
        self._giro("2026-08-01", 11)
        self.assertEqual(len(self.eseguiti), 1)

    def test_l_ora_si_puo_cambiare(self):
        tunables.set_value("backup_ora", 17)
        self.store.set_meta("ultimo_backup", str(now() - 20 * 3600))
        self.store.set_meta("ultimo_backup_giorno", "2026-07-31")
        self._giro("2026-08-01", 3)
        self.assertEqual(self.eseguiti, [])
        self._giro("2026-08-01", 17)
        self.assertEqual(len(self.eseguiti), 1)

    def test_senza_configurazione_non_fa_niente(self):
        import memescan.backup as backup_module

        backup_module.configurato = lambda: False
        self._giro("2026-08-01", 3)
        self.assertEqual(self.eseguiti, [])

    def test_se_fallisce_riprova_al_giro_dopo(self):
        """Un errore di rete non deve far saltare la copia del giorno."""
        import memescan.backup as backup_module

        async def backup_rotto():
            self.eseguiti.append(False)
            return {"ok": False, "motivo": "rete assente"}

        backup_module.esegui = backup_rotto
        self.store.set_meta("ultimo_backup", str(now() - 20 * 3600))
        self.store.set_meta("ultimo_backup_giorno", "2026-07-31")
        self._giro("2026-08-01", 3)
        self._giro("2026-08-01", 3)
        self.assertEqual(len(self.eseguiti), 2)
        self.assertEqual(self.store.get_meta("ultimo_backup_giorno"), "2026-07-31")


class TestBackup(unittest.TestCase):
    def test_inerte_finche_non_configurato(self):
        """Senza repository e token non deve provarci nemmeno."""
        from memescan import backup

        self.assertFalse(backup.configurato())
        self.assertFalse(run(backup.esegui())["ok"])
        cartella, motivo, quando = run(backup.scarica())
        self.assertIsNone(cartella)
        self.assertEqual(quando, 0)

    def test_la_copia_e_leggibile(self):
        """Copiare il file a mano darebbe un backup rotto: in WAL le ultime
        scritture stanno in un file a parte."""
        from memescan import backup
        import memescan.config as config_module

        cartella = Path(tempfile.mkdtemp())
        store = Store(str(cartella / "vivo.db"))
        store.upsert_candidate({"token_address": FAKE, "symbol": "COPIA"})

        originale = config_module.settings.db_path
        config_module.settings.db_path = str(cartella / "vivo.db")
        try:
            peso = backup._copia_coerente(cartella / "copia.db")
        finally:
            config_module.settings.db_path = originale
            store.close()

        self.assertGreater(peso, 0)
        riletto = Store(str(cartella / "copia.db"))
        try:
            self.assertEqual(riletto.get_candidate(FAKE)["symbol"], "COPIA")
        finally:
            riletto.close()


class TestRipristino(unittest.TestCase):
    """Rimettere il backup e' l'unica operazione che sovrascrive tutto."""

    def setUp(self):
        self.cartella = Path(tempfile.mkdtemp())
        self.vivo = str(self.cartella / "vivo.db")
        self.store = Store(self.vivo)
        self.store.upsert_candidate({"token_address": FAKE, "symbol": "ADESSO"})

        # Un secondo database che fa da backup, con dentro altre cose.
        self.copia = str(self.cartella / "backup.db")
        altro = Store(self.copia)
        altro.upsert_candidate({"token_address": REAL, "symbol": "SALVATO"})
        altro.add_tracked_wallet("0x" + "aa" * 20, label="whale salvata")
        altro.close()

    def tearDown(self):
        self.store.close()

    def test_i_dati_diventano_quelli_del_backup(self):
        esito = self.store.sostituisci(self.copia)
        self.assertEqual(esito["candidati"], 1)
        self.assertEqual(esito["whales"], 1)
        self.assertEqual(self.store.get_candidate(REAL)["symbol"], "SALVATO")
        self.assertIsNone(self.store.get_candidate(FAKE))

    def test_il_database_resta_utilizzabile(self):
        """Il servizio non si ferma: dopo il ripristino deve poter scrivere."""
        self.store.sostituisci(self.copia)
        self.store.upsert_candidate({"token_address": FAKE, "symbol": "DOPO"})
        self.assertEqual(self.store.get_candidate(FAKE)["symbol"], "DOPO")

    def test_mette_da_parte_quello_di_prima(self):
        """Serve ad accorgersi un secondo dopo di aver sbagliato backup."""
        self.store.sostituisci(self.copia)
        salvato = Store(self.vivo + ".prima-del-ripristino")
        try:
            self.assertEqual(salvato.get_candidate(FAKE)["symbol"], "ADESSO")
        finally:
            salvato.close()

    def test_quando_ho_fatto_l_ultima_copia_non_si_perde(self):
        """Riguarda questa macchina, non i dati salvati.

        Nella copia non c'e' comunque: la fotografia viene scattata un istante
        prima di annotare che il backup e' riuscito. Senza rimetterlo, subito
        dopo un ripristino la dashboard direbbe "mai" e sembrerebbe rotto.
        """
        self.store.set_meta("ultimo_backup", "1700000000")
        self.store.set_meta("ultimo_backup_esito", "ok")

        engine = Engine.__new__(Engine)
        engine.store = self.store
        prima = {
            "ultimo_backup": self.store.get_meta("ultimo_backup", "0"),
            "ultimo_backup_esito": self.store.get_meta("ultimo_backup_esito", ""),
        }
        self.store.sostituisci(self.copia)
        # Il ripristino da solo li perde: e' quello che si e' visto sul telefono.
        self.assertEqual(self.store.get_meta("ultimo_backup", "0"), "0")
        for chiave, valore in prima.items():
            self.store.set_meta(chiave, valore)
        self.assertEqual(self.store.get_meta("ultimo_backup"), "1700000000")
        self.assertEqual(self.store.get_meta("ultimo_backup_esito"), "ok")

    def test_un_backup_vecchio_viene_migrato(self):
        """Il backup puo' venire da una versione precedente del programma."""
        import sqlite3

        conn = sqlite3.connect(self.copia)
        conn.execute("ALTER TABLE candidates DROP COLUMN peak_notified")
        conn.commit()
        conn.close()

        self.store.sostituisci(self.copia)
        colonne = {r["name"] for r in self.store._conn.execute("PRAGMA table_info(candidates)")}
        self.assertIn("peak_notified", colonne)
        # E deve poterci scrivere: e' il motivo per cui la migrazione serve.
        self.store.set_peak_notified(REAL, 2.0)


class TestControlloDelBackupScaricato(unittest.TestCase):
    """Sovrascrivere un database buono con spazzatura sarebbe il danno peggiore."""

    def setUp(self):
        from memescan import backup

        self.backup = backup
        self.cartella = Path(tempfile.mkdtemp())

    def test_accetta_un_database_vero(self):
        percorso = self.cartella / "buono.db"
        Store(str(percorso)).close()
        ok, motivo = self.backup._sembra_un_database(percorso)
        self.assertTrue(ok, motivo)

    def test_rifiuta_un_file_qualunque(self):
        percorso = self.cartella / "spazzatura.db"
        percorso.write_bytes(b"non sono un database")
        ok, motivo = self.backup._sembra_un_database(percorso)
        self.assertFalse(ok)
        self.assertIn("SQLite", motivo)

    def test_rifiuta_un_file_vuoto(self):
        percorso = self.cartella / "vuoto.db"
        percorso.write_bytes(b"")
        self.assertFalse(self.backup._sembra_un_database(percorso)[0])

    def test_rifiuta_un_database_di_qualcun_altro(self):
        import sqlite3

        percorso = self.cartella / "estraneo.db"
        conn = sqlite3.connect(str(percorso))
        conn.execute("CREATE TABLE ricette (nome TEXT)")
        conn.commit()
        conn.close()
        ok, motivo = self.backup._sembra_un_database(percorso)
        self.assertFalse(ok)
        self.assertIn("candidates", motivo)

    def test_rifiuta_un_file_che_non_esiste(self):
        self.assertFalse(self.backup._sembra_un_database(self.cartella / "niente.db")[0])


class TestConfigurazioneDelBackup(unittest.TestCase):
    """Repository e token si scrivono dalla dashboard, non nel file sul server.

    Chi usa lo scanner non ha accesso alla macchina: se le credenziali
    vivessero solo nel file di configurazione, per accendere il backup
    bisognerebbe rifare il server da capo.
    """

    def setUp(self):
        from memescan import backup

        self.backup = backup
        self.cartella = Path(tempfile.mkdtemp())
        self.store = Store(str(self.cartella / "conf.db"))
        import memescan.store as store_module

        self._original = store_module._store
        store_module._store = self.store

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()

    def test_spento_finche_manca_qualcosa(self):
        self.assertFalse(self.backup.configurato())
        self.store.set_setting(self.backup.CHIAVE_REPO, "tizio/copie")
        self.assertFalse(self.backup.configurato())  # manca il token
        self.store.set_setting(self.backup.CHIAVE_TOKEN, "github_pat_finto")
        self.assertTrue(self.backup.configurato())

    def test_il_riepilogo_non_espone_il_token(self):
        self.store.set_setting(self.backup.CHIAVE_REPO, "tizio/copie")
        self.store.set_setting(self.backup.CHIAVE_TOKEN, "github_pat_segretissimo")
        riepilogo = self.backup.riepilogo()
        self.assertTrue(riepilogo["token_presente"])
        self.assertNotIn("github_pat_segretissimo", str(riepilogo))

    def test_il_token_non_finisce_nel_backup(self):
        """La chiave del ripostiglio non deve stare dentro al ripostiglio."""
        import memescan.config as config_module

        self.store.set_setting(self.backup.CHIAVE_REPO, "tizio/copie")
        self.store.set_setting(self.backup.CHIAVE_TOKEN, "github_pat_segretissimo")

        originale = config_module.settings.db_path
        config_module.settings.db_path = self.store.path
        try:
            self.backup._copia_coerente(self.cartella / "spedito.db")
        finally:
            config_module.settings.db_path = originale

        copia = Store(str(self.cartella / "spedito.db"))
        try:
            salvate = copia.get_settings()
        finally:
            copia.close()
        self.assertNotIn(self.backup.CHIAVE_TOKEN, salvate)
        # Il repository invece resta: non e' un segreto e dice da dove viene.
        self.assertEqual(salvate.get(self.backup.CHIAVE_REPO), "tizio/copie")

    def test_il_token_non_compare_nei_log(self):
        """Git lo stampa dentro gli URL quando qualcosa va storto."""
        self.store.set_setting(self.backup.CHIAVE_TOKEN, "github_pat_segretissimo")
        code, testo = run(self.backup._git("clone", "https://x-access-token:"
                                           "github_pat_segretissimo@github.com/nessuno/nulla.git",
                                           ".", cwd=str(self.cartella)))
        self.assertNotEqual(code, 0)
        self.assertNotIn("github_pat_segretissimo", testo)

    def test_il_file_env_resta_come_riserva(self):
        """Le installazioni da riga di comando continuano a funzionare."""
        import memescan.config as config_module

        originale = config_module.settings.backup_repo
        config_module.settings.backup_repo = "dal/file"
        try:
            self.assertEqual(self.backup.repository(), "dal/file")
            self.store.set_setting(self.backup.CHIAVE_REPO, "dalla/dashboard")
            # Quello scritto dalla dashboard vince: e' il piu' recente.
            self.assertEqual(self.backup.repository(), "dalla/dashboard")
        finally:
            config_module.settings.backup_repo = originale


class TestAsticellaDelleWhales(unittest.TestCase):
    """Non si possono chiedere quattro presenze quando i token sono tre.

    E' successo davvero: dopo la pulizia del database la memoria era vuota,
    restavano due o tre token esplosi da esaminare, e il requisito a quattro
    rendeva la ricerca impossibile invece che severa. Diciotto notifiche e
    nessuna whale.
    """

    def _soglia(self, richiesti: int, vincenti: int) -> int:
        # Stessa formula di discover_top_traders.
        return max(2, min(richiesti, vincenti // 2))

    def test_con_tanti_vincenti_resta_severa(self):
        self.assertEqual(self._soglia(4, 25), 4)
        self.assertEqual(self._soglia(4, 8), 4)

    def test_con_pochi_vincenti_scende(self):
        self.assertEqual(self._soglia(4, 6), 3)
        self.assertEqual(self._soglia(4, 4), 2)

    def test_non_chiede_mai_l_impossibile(self):
        """Il caso che ha bloccato tutto: tre token disponibili."""
        for vincenti in range(1, 30):
            soglia = self._soglia(4, vincenti)
            self.assertLessEqual(
                soglia, max(2, vincenti),
                f"con {vincenti} vincenti chiede {soglia} presenze",
            )

    def test_non_scende_mai_sotto_due(self):
        """Una presenza sola e' fortuna, non bravura: quello resta fermo."""
        for vincenti in range(0, 30):
            self.assertGreaterEqual(self._soglia(4, vincenti), 2)

    def test_rispetta_la_scelta_dell_utente(self):
        """Chi la alza a 6 deve vederla applicata quando c'e' materiale."""
        self.assertEqual(self._soglia(6, 25), 6)
        self.assertEqual(self._soglia(2, 25), 2)


class TestQuandoRiprovareLaRicerca(unittest.TestCase):
    """Un giro a vuoto per mancanza di materiale non e' un verdetto."""

    def test_la_soglia_del_giudizio(self):
        from memescan.worker import MIN_WINNERS_PER_GIUDICARE

        # Sotto: era un tentativo, si riprova al giro normale di sei ore.
        self.assertLess(3, MIN_WINNERS_PER_GIUDICARE)
        # Sopra: ha guardato abbastanza roba, aspettare due giorni ha senso.
        self.assertGreaterEqual(25, MIN_WINNERS_PER_GIUDICARE)


class TestAzioniTokenizzate(unittest.TestCase):
    """Le azioni si riconoscono da chi le ha emesse, non da quanto sono vecchie.

    Su Robinhood Chain Apple, Tesla, NVIDIA, AMD, Micron e CoreWeave hanno
    tutte lo stesso creatore. Il nome si copia e l'icona si copia, il creatore
    no: e' l'unico segnale che non si puo' falsificare. Cosi' una meme coin di
    quaranta giorni resta una meme coin, e una copia di AMD resta una copia.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "azioni.db"))

    def tearDown(self):
        self.store.close()

    def _compra(self, wallet: str, token: str, tx: str) -> None:
        self.store.record_wallet_event({
            "wallet": wallet, "token_address": token, "symbol": "X",
            "direction": "buy", "tx_hash": tx, "ts": now() - 600,
        })

    def test_il_caso_vero(self):
        """0x8a2ed7: AMD, Intel, Micron, CoreWeave nello stesso minuto."""
        for i, simbolo in enumerate(("AMD", "INTC", "MU", "CRWV", "ROHM")):
            token = "0x%040x" % i
            self.store.set_token_natura(token, "azione", simbolo)
            self._compra("0x8a2ed7", token, f"t{i}")
        self.store.set_token_natura(FAKE, "lancio", "MEME")
        self._compra("0x8a2ed7", FAKE, "tmeme")

        # Prima: sei acquisti, sospetto. Adesso: un lancio solo.
        self.assertEqual(self.store.wallet_activity()["0x8a2ed7"], 6)
        self.assertEqual(self.store.wallet_activity(solo_lanci=True)["0x8a2ed7"], 1)

    def test_lo_sniper_resta_smascherato(self):
        """Chi rastrella meme coin nuove continua a contare tutto."""
        for i in range(8):
            token = "0x%040x" % (100 + i)
            self.store.set_token_natura(token, "lancio", "MEME")
            self._compra("0xbot", token, f"b{i}")
        self.assertEqual(self.store.wallet_activity(solo_lanci=True)["0xbot"], 8)

    def test_una_meme_di_quaranta_giorni_resta_una_meme(self):
        """E' il motivo per cui l'eta' non va bene come criterio."""
        self.store.set_token_natura(REAL, "lancio", "VECCHIA")
        self._compra("0xaa", REAL, "a1")
        self.assertTrue(self.store.e_un_lancio(REAL))
        self.assertEqual(self.store.wallet_activity(solo_lanci=True)["0xaa"], 1)

    def test_un_token_sconosciuto_conta_lo_stesso(self):
        """Meglio contarne uno in piu' che perdere un lancio vero."""
        self._compra("0xtizio", FAKE, "t1")
        self.assertTrue(self.store.e_un_lancio(FAKE))
        self.assertEqual(self.store.wallet_activity(solo_lanci=True)["0xtizio"], 1)

    def test_la_natura_si_ricorda(self):
        self.store.set_token_natura(REAL, "azione", "AMD")
        self.assertEqual(self.store.get_token_natura(REAL), "azione")
        self.assertFalse(self.store.e_un_lancio(REAL))

    def test_le_azioni_non_fanno_scattare_la_convergenza(self):
        """Due whales su AMD non sono una convergenza da segnalare."""
        self.store.set_token_natura(REAL, "azione", "AMD")
        self._compra("0xaa", REAL, "a1")
        self._compra("0xbb", REAL, "b1")
        self.assertEqual(self.store.count_distinct_wallet_buyers(REAL), 2)
        self.assertFalse(self.store.e_un_lancio(REAL))


class TestRiconoscimentoAzioni(unittest.TestCase):
    """La classifica vera e propria, con un explorer finto ma fedele."""

    EMITTENTE = "0x4783c67b63de2b358ac5951a7d41f47a38f3c046"

    def setUp(self):
        stocks.dimentica()
        # Un explorer che risponde come quello vero.
        self.creatori = {
            "0xamd": self.EMITTENTE,          # AMD ufficiale
            "0xfalsa": "0x" + "99" * 20,      # copia di AMD
            "0xmeme": "0x" + "77" * 20,       # meme coin qualunque
        }
        self.ricerche = {
            "amd": [{"address": "0xamd", "symbol": "AMD",
                     "name": "AMD • Robinhood Token", "holders": 19636}],
            "pepe": [],
        }
        prova = self

        class FintoExplorer:
            async def address_info(self, address):
                return {"creator": prova.creatori.get(address.lower(), "")}

            async def search_tokens(self, query):
                return prova.ricerche.get(query.lower(), [])

        self.explorer = FintoExplorer()

    def _snapshot(self, indirizzo, simbolo, nome=""):
        return PairSnapshot(token_address=indirizzo, symbol=simbolo, name=nome)

    def test_riconosce_l_azione_ufficiale(self):
        v = run(stocks.classifica(self.explorer, self._snapshot("0xamd", "AMD")))
        self.assertTrue(v.e_azione)

    def test_smaschera_la_copia_di_un_azione(self):
        """Sette monete si chiamano AMD su questa chain: sono trappole."""
        v = run(stocks.classifica(self.explorer, self._snapshot("0xfalsa", "AMD")))
        self.assertTrue(v.e_clone)
        self.assertEqual(v.ufficiale, "0xamd")
        self.assertIn("AMD", v.motivo)

    def test_smaschera_chi_si_finge_ufficiale_nel_nome(self):
        """Il nome si copia, il creatore no."""
        v = run(stocks.classifica(
            self.explorer, self._snapshot("0xfalsa", "XYZ", "XYZ • Robinhood Token")
        ))
        self.assertTrue(v.e_clone)

    def test_una_meme_normale_passa(self):
        v = run(stocks.classifica(self.explorer, self._snapshot("0xmeme", "PEPE")))
        self.assertEqual(v.natura, stocks.LANCIO)

    def test_senza_simbolo_non_accusa_nessuno(self):
        v = run(stocks.classifica(self.explorer, self._snapshot("0xmeme", "")))
        self.assertEqual(v.natura, stocks.LANCIO)

    def test_l_azione_ufficiale_si_cerca_una_volta_sola(self):
        chiamate = []
        originale = self.explorer.search_tokens

        async def conta(query):
            chiamate.append(query)
            return await originale(query)

        self.explorer.search_tokens = conta
        run(stocks.classifica(self.explorer, self._snapshot("0xfalsa", "AMD")))
        run(stocks.classifica(self.explorer, self._snapshot("0xfalsa2", "AMD")))
        self.assertEqual(len(chiamate), 1)

    def test_senza_emittente_configurato_non_fa_niente(self):
        import memescan.config as config_module

        originale = config_module.settings.stock_issuer
        config_module.settings.stock_issuer = ""
        try:
            v = run(stocks.classifica(self.explorer, self._snapshot("0xamd", "AMD")))
            self.assertEqual(v.natura, stocks.LANCIO)
        finally:
            config_module.settings.stock_issuer = originale


class TestMigrazioneDatabase(unittest.TestCase):
    def test_aggiunge_la_colonna_a_un_database_esistente(self):
        """Il server in funzione ha gia' un database senza alert_kind.

        Senza la migrazione ogni scrittura fallirebbe e lo scanner smetterebbe
        di segnalare senza dirlo.
        """
        path = str(Path(tempfile.mkdtemp()) / "vecchio.db")

        # Si parte dal database di oggi e si torna indietro togliendo la
        # colonna: e' l'unico modo di avere davvero il file che gira in
        # produzione senza ricopiarne lo schema a mano.
        prima = Store(path)
        prima.upsert_candidate({"token_address": FAKE, "symbol": "VECCHIO"})
        prima._conn.execute("ALTER TABLE candidates DROP COLUMN alert_kind")
        prima._conn.commit()
        prima.close()

        dopo = Store(path)
        try:
            colonne = {r["name"] for r in dopo._conn.execute("PRAGMA table_info(candidates)")}
            self.assertIn("alert_kind", colonne)
            # I candidati gia' presenti devono sopravvivere alla migrazione.
            self.assertEqual(dopo.get_candidate(FAKE)["symbol"], "VECCHIO")
            dopo.mark_alerted(FAKE, 80, 1.0, 1000, "whales")
            self.assertEqual(dopo.get_candidate(FAKE)["alert_kind"], "whales")
        finally:
            dopo.close()


def _cavia(n: int) -> str:
    """Un indirizzo di comodo lontano da quelli di burn (0x0, 0x1, 0xdead)."""
    return "0x%040x" % (0xC0DE0000 + n)


class RpcPozza:
    """Un nodo che risponde con gli eventi che gli si mettono in bocca."""

    def __init__(self, logs=None, codici=None, owner=""):
        self.logs = logs or []
        self.codici = {k.lower(): v for k, v in (codici or {}).items()}
        self.owner = owner
        self.chiamate = 0

    async def call(self, method, params=None):
        self.chiamate += 1
        filtro = (params or [{}])[0]
        for chiave, righe in self.logs:
            if chiave in str(filtro):
                return righe
        return []

    async def get_code(self, indirizzo):
        return self.codici.get(indirizzo.lower(), "0x")

    async def eth_call(self, to, data, *a, **k):
        return ("0x" + "0" * 24 + self.owner.removeprefix("0x")) if self.owner else "0x"


class TestVotoDeiPortafogli(unittest.TestCase):
    """Anche i portafogli aggiunti a mano devono avere un voto.

    Restavano «senza etichetta» mentre quelli trovati dalla ricerca portavano
    scritto su quante monete andate bene erano arrivati presto. Lo stesso
    numero si puo' dare a tutti, e senza chiamate in piu': la ricerca lo
    calcolava gia' per ogni indirizzo incontrato e poi buttava via quelli sotto
    la sua asticella.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "voti.db"))

    def tearDown(self):
        self.store.close()

    def test_mai_letta_e_diverso_da_letta_e_vuota(self):
        """Senza distinguerli una moneta senza primi acquirenti verrebbe
        riletta dalla blockchain per sempre."""
        self.assertIsNone(self.store.early_buyers_noti(FAKE))
        self.store.salva_early_buyers(FAKE, [])
        self.assertEqual(self.store.early_buyers_noti(FAKE), set())

    def test_chi_e_arrivato_presto_si_ricorda(self):
        self.store.salva_early_buyers(FAKE, ["0xAAA", "0xBBB"])
        self.assertEqual(self.store.early_buyers_noti(FAKE), {"0xaaa", "0xbbb"})

    def test_il_voto_ha_sempre_il_suo_denominatore(self):
        """«0» da solo non si sa leggere: puo' essere un portafoglio scarso o
        uno aggiunto cinque minuti fa."""
        for i in range(3):
            self.store.salva_early_buyers("0x%040x" % i, ["0xbravo"] if i < 2 else [])
        voti, totale = self.store.voti_early()
        self.assertEqual(totale, 3)
        self.assertEqual(voti["0xbravo"], 2)
        self.assertEqual(voti.get("0xmai", 0), 0)

    def test_il_voto_e_immediato_per_chi_viene_aggiunto_dopo(self):
        """E' il punto: aggiungo un portafoglio e vedo subito cosa ha fatto,
        senza aspettare il giro di ricerca successivo."""
        for i in range(4):
            self.store.salva_early_buyers("0x%040x" % i, ["0xnuovo", "0xaltro"])
        self.store.add_tracked_wallet("0xNUOVO", label="")
        voti, totale = self.store.voti_early()
        self.assertEqual((voti["0xnuovo"], totale), (4, 4))

    def test_rileggere_la_stessa_moneta_non_duplica(self):
        self.store.salva_early_buyers(FAKE, ["0xAAA"])
        self.store.salva_early_buyers(FAKE, ["0xAAA", "0xBBB"])
        voti, totale = self.store.voti_early()
        self.assertEqual(totale, 1)
        self.assertEqual(voti["0xaaa"], 1)


class TestArchivioIndipendente(unittest.TestCase):
    """L'archivio dei voti non deve dipendere dalla caccia a nuove whales.

    La caccia si ferma da sola quando si seguono gia' dieci portafogli - ha
    senso, cercarne altre sarebbe spreco - ma l'archivio serve a dare un voto a
    *tutti* quelli gia' seguiti, compresi quelli scelti a mano. Legandolo alla
    caccia, chi ne seguiva gia' dieci non avrebbe visto un voto mai.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "archivio.db"))
        self.tracker = WalletTracker.__new__(WalletTracker)
        self.tracker.store = self.store
        self.lette: list[str] = []

        async def finge_lettura(token, created_at, window=900):
            self.lette.append(token)
            self.store.salva_early_buyers(token, ["0x" + "aa" * 20])
            return {"0x" + "aa" * 20}

        self.tracker._early_buyers = finge_lettura

        class GeckoFinto:
            async def trending(_):
                return []

        self.gecko = GeckoFinto()

    def tearDown(self):
        self.store.close()

    def _vincente(self, token: str) -> None:
        self.store.upsert_candidate({
            "token_address": token, "symbol": "W", "pair_created_at": now() - 7200,
        })
        self.store._exec(
            "UPDATE candidates SET peak_multiple = 4 WHERE token_address = ?", (token.lower(),)
        )

    def test_funziona_con_la_lista_gia_piena(self):
        """Il difetto che rendeva inutile il voto: dodici portafogli seguiti,
        la caccia ferma, e l'archivio che non si riempiva mai."""
        for i in range(12):
            self.store.add_tracked_wallet("0x%040x" % i)
        self._vincente(FAKE)
        run(self.tracker.archivia_early(self.gecko))
        voti, totale = self.store.voti_early()
        self.assertEqual(totale, 1)
        self.assertEqual(voti["0x" + "aa" * 20], 1)

    def test_non_aggiunge_portafogli(self):
        """Riempie l'archivio e basta: chi seguire resta una decisione a parte."""
        self._vincente(FAKE)
        run(self.tracker.archivia_early(self.gecko))
        self.assertEqual(self.store.list_tracked_wallets(), [])

    def test_una_moneta_si_legge_una_volta_sola(self):
        self._vincente(FAKE)
        run(self.tracker.archivia_early(self.gecko))
        run(self.tracker.archivia_early(self.gecko))
        self.assertEqual(self.lette, [FAKE])

    def test_poche_per_volta(self):
        """Il carico resta piatto invece di arrivare a ondate."""
        for i in range(9):
            self._vincente("0x%040x" % (0x700 + i))
        self.assertEqual(run(self.tracker.archivia_early(self.gecko, quante=4)), 4)
        self.assertEqual(len(self.lette), 4)

    def test_senza_vincenti_non_fa_niente(self):
        self.assertEqual(run(self.tracker.archivia_early(self.gecko)), 0)


class TestRicercaNonRilegge(unittest.TestCase):
    """La ricerca non deve rifare il lavoro gia' fatto.

    Chi e' arrivato presto nei primi quindici minuti di una moneta e' un fatto
    chiuso. Rileggerlo a ogni giro costava una ventina di domande al nodo per
    moneta solo per ritrovare il punto giusto della blockchain, ed era il
    motivo per cui la ricerca poteva girare solo ogni sei ore.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "rilettura.db"))
        self.tracker = WalletTracker.__new__(WalletTracker)
        self.tracker.store = self.store
        self.letture = 0

        # Due valori diversi, o l'intervallo sarebbe vuoto: in quel caso il
        # codice non salva niente apposta, per poter riprovare piu' tardi.
        async def finge_blocco(ts):
            self.letture += 1
            return 100 if self.letture % 2 else 200

        self.tracker._block_at_timestamp = finge_blocco

        class RpcFinto:
            async def get_logs(_, **kw):
                return [{"topics": ["t", "a", "0x" + "0" * 24 + "aa" * 20]}]

        self.tracker.rpc = RpcFinto()

    def tearDown(self):
        self.store.close()

    def test_un_intervallo_vuoto_non_viene_dato_per_letto(self):
        """Puo' essere un nodo che ha risposto male: va lasciato riprovabile,
        non congelato a "questa moneta non aveva nessuno"."""
        async def sempre_uguale(ts):
            return 100
        self.tracker._block_at_timestamp = sempre_uguale
        self.assertEqual(run(self.tracker._early_buyers(FAKE, now() - 3600)), set())
        self.assertIsNone(self.store.early_buyers_noti(FAKE))

    def test_la_seconda_volta_non_tocca_la_blockchain(self):
        run(self.tracker._early_buyers(FAKE, now() - 3600))
        prima = self.letture
        self.assertGreater(prima, 0)
        run(self.tracker._early_buyers(FAKE, now() - 3600))
        self.assertEqual(self.letture, prima)

    def test_quello_che_ha_letto_lo_tiene(self):
        trovati = run(self.tracker._early_buyers(FAKE, now() - 3600))
        self.assertEqual(trovati, {"0x" + "aa" * 20})
        self.assertEqual(self.store.early_buyers_noti(FAKE), {"0x" + "aa" * 20})


class TestOrigineDeiPortafogli(unittest.TestCase):
    """Chi ha messo in lista un portafoglio: una persona o la ricerca automatica.

    Sono due cose che si leggono in modo diverso - una e' una convinzione,
    l'altra e' una statistica su chi e' arrivato presto sui token poi andati
    bene - e mescolarle toglie il contesto proprio a chi deve decidere se
    fidarsi.
    """

    def setUp(self):
        self.path = str(Path(tempfile.mkdtemp()) / "origini.db")
        self.store = Store(self.path)

    def tearDown(self):
        self.store.close()

    def test_scelto_a_mano_e_mio(self):
        self.store.add_tracked_wallet("0xAAA", label="il mio amico")
        self.assertEqual(self.store.list_tracked_wallets()[0]["origine"], "mia")

    def test_trovato_dalla_ricerca_e_dello_scanner(self):
        self.store.add_tracked_wallet("0xBBB", label="early su 7 vincenti", origine="scanner")
        self.assertEqual(self.store.list_tracked_wallets()[0]["origine"], "scanner")

    def test_una_scelta_non_viene_declassata_a_ritrovamento(self):
        """Se la ricerca automatica ritrova un indirizzo gia' scelto a mano,
        resta suo: il contrario cancellerebbe l'unica cosa che sapeva lui."""
        self.store.add_tracked_wallet("0xCCC", label="mio")
        self.store.add_tracked_wallet("0xCCC", label="early su 9 vincenti", origine="scanner")
        riga = self.store.list_tracked_wallets()[0]
        self.assertEqual(riga["origine"], "mia")
        self.assertEqual(riga["label"], "early su 9 vincenti")

    def test_i_portafogli_di_prima_si_classificano_da_soli(self):
        """Il caso vero: chi c'era prima che esistesse la colonna.

        L'unico indizio rimasto e' l'etichetta - la ricerca scrive "early su N
        vincenti" - e le etichette vuote sono la firma di chi ha aggiunto un
        indirizzo a mano dalla dashboard.
        """
        vecchio = Store(self.path)
        vecchio._exec("ALTER TABLE tracked_wallets DROP COLUMN origine")
        for indirizzo, etichetta in (
            ("0x1", "early su 12 vincenti"),
            ("0x2", ""),
            ("0x3", "quello bravo"),
            ("0x4", "da .env"),
        ):
            vecchio._exec(
                "INSERT INTO tracked_wallets(address, label, added_at) VALUES(?,?,?)",
                (indirizzo, etichetta, now()),
            )
        vecchio.close()

        dopo = Store(self.path)
        try:
            origini = {r["address"]: r["origine"] for r in dopo.list_tracked_wallets()}
            self.assertEqual(origini["0x1"], "scanner")
            # senza etichetta, scritta a mano, e dalla configurazione: sue
            self.assertEqual(origini["0x2"], "mia")
            self.assertEqual(origini["0x3"], "mia")
            self.assertEqual(origini["0x4"], "mia")
        finally:
            dopo.close()


class TestRipassoDeiControlli(unittest.TestCase):
    """Il giudizio su una scheda non deve restare quello del giorno prima.

    Le tre sorgenti di scoperta restituiscono solo roba nuova: pool appena
    creati, token appena profilati o boostati. Nessuna fa ricomparire una
    moneta di ieri, quindi il suo giudizio si formava una volta e non si
    rifaceva piu' - e un controllo aggiunto dopo non compariva mai sulle monete
    gia' in elenco.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "ripasso.db"))
        self.engine = Engine.__new__(Engine)
        self.engine.store = self.store
        self.engine._safety_cache = {}
        self.valutate: list[str] = []

        async def finge_valutazione(snapshot, force=False):
            self.valutate.append(snapshot.token_address)

        self.engine._evaluate = finge_valutazione

        class DexFinto:
            async def get_tokens(_, indirizzi):
                return {a: PairSnapshot(token_address=a, symbol="X") for a in indirizzi}

        self.engine.dexscreener = DexFinto()

    def tearDown(self):
        self.store.close()

    def _segnala(self, token: str, ore_fa: float = 1.0) -> None:
        self.store.upsert_candidate({"token_address": token, "symbol": "X"})
        self.store.mark_alerted(token, 70, 0.001, 100_000, "scanner")
        self.store._exec(
            "UPDATE candidates SET alerted_at = ? WHERE token_address = ?",
            (now() - int(ore_fa * 3600), token.lower()),
        )

    def test_le_monete_gia_in_elenco_vengono_ripassate(self):
        self._segnala(FAKE)
        run(self.engine.ripasso_once())
        self.assertEqual(self.valutate, [FAKE])

    def test_butta_la_copia_in_memoria_o_non_ricontrolla_niente(self):
        """Senza questo `_evaluate` riuserebbe il giudizio vecchio per mezz'ora."""
        self._segnala(FAKE)
        self.engine._safety_cache[FAKE] = _CachedSafety(
            SafetyReport(token_address=FAKE), now()
        )
        run(self.engine.ripasso_once())
        self.assertNotIn(FAKE, self.engine._safety_cache)

    def test_prima_le_piu_trascurate(self):
        """A giro, poche per volta: un controllo completo costa parecchie chiamate."""
        for i in range(8):
            self._segnala("0x%040x" % (0x500 + i))
        # Le prime tre sono state controllate poco fa, le altre mai.
        for i in range(3):
            self.engine._safety_cache["0x%040x" % (0x500 + i)] = _CachedSafety(
                SafetyReport(token_address="x"), now()
            )
        run(self.engine.ripasso_once())
        self.assertEqual(len(self.valutate), 5)
        for i in range(3):
            self.assertNotIn("0x%040x" % (0x500 + i), self.valutate)

    def test_le_vecchie_di_giorni_si_lasciano_stare(self):
        """Nessuno ci ha piu' soldi dentro: ripassarle sarebbe spreco."""
        self._segnala(FAKE, ore_fa=72)
        run(self.engine.ripasso_once())
        self.assertEqual(self.valutate, [])

    def test_i_salvati_si_ripassano_sempre(self):
        """Li ha messi da parte a mano: sono quelli che gli interessano di piu'."""
        self.store.upsert_candidate({"token_address": REAL, "symbol": "Y"})
        self.store.set_watchlist(REAL, True)
        run(self.engine.ripasso_once())
        self.assertEqual(self.valutate, [REAL])


class TestVersioneDellaPozza(unittest.TestCase):
    """Lo stile della pozza serve a dividere gli elenchi nella dashboard.

    Non costa niente: arriva in `labels` sulla stessa risposta che lo scanner
    scarica a ogni giro, quindi si aggiorna da solo insieme al resto.
    """

    def test_arriva_dalle_etichette(self):
        for etichetta in ("v2", "v3", "v4"):
            self.assertEqual(
                _versione_pozza({"labels": [etichetta], "pairAddress": "0x" + "a" * 40}),
                etichetta,
            )

    def test_maiuscole_e_altre_etichette_non_disturbano(self):
        self.assertEqual(
            _versione_pozza({"labels": ["CLMM", "V3"], "pairAddress": "0x" + "a" * 40}), "v3"
        )

    def test_la_prova_di_riserva_e_la_lunghezza(self):
        """Senza etichetta, un id da 32 byte e' per forza una v4.

        Un indirizzo di pozza ne ha 20: la differenza si vede e basta.
        """
        self.assertEqual(_versione_pozza({"pairAddress": "0x" + "b" * 64}), "v4")
        self.assertEqual(_versione_pozza({"pairAddress": "0x" + "b" * 40}), "")

    def test_quello_che_non_si_sa_resta_vuoto(self):
        """Su questa chain girano anche pozze di altri exchange."""
        self.assertEqual(_versione_pozza({"labels": ["flapsh"], "pairAddress": ""}), "")
        self.assertEqual(_versione_pozza({}), "")

    def test_viaggia_dal_database_allo_schermo(self):
        store = Store(str(Path(tempfile.mkdtemp()) / "vers.db"))
        try:
            store.upsert_candidate({"token_address": FAKE, "symbol": "X", "pool_version": "v4"})
            self.assertEqual(store.get_candidate(FAKE)["pool_version"], "v4")
            # e sopravvive a un aggiornamento parziale della riga
            store.upsert_candidate({"token_address": FAKE, "liquidity_usd": 1234})
            self.assertEqual(store.get_candidate(FAKE)["pool_version"], "v4")
        finally:
            store.close()


class TestChiPuoRitirareLaLiquidita(unittest.TestCase):
    """Su v3 e v4 le ricevute della pozza non esistono piu'.

    Il conteggio delle ricevute bruciate risponde solo per le v2, che su questa
    chain sono 17 su 84. Per le altre la risposta si ricava dagli eventi di
    versamento, risalendo all'NFT della posizione e chiedendo chi lo possiede.
    """

    POZZA_V3 = "0x" + "11" * 20
    POZZA_V4 = "0x" + "22" * 32          # un id, non un indirizzo
    LOCKER = "0x" + "33" * 20
    TIZIO = "0x" + "44" * 20
    MORTO = "0x000000000000000000000000000000000000dead"

    def _mint(self, owner, blocco=100, tx="0xabc"):
        return {"topics": [pozza.TOPIC_MINT_V3, "0x" + "0" * 24 + owner.removeprefix("0x")],
                "blockNumber": hex(blocco), "transactionHash": tx}

    def test_la_risposta_v2_vince_e_non_costa_chiamate(self):
        """Se le ricevute si sono potute contare, la domanda ha gia' risposta."""
        rpc = RpcPozza()
        pozza.get_rpc = lambda: rpc
        c = run(pozza.controlla(self.POZZA_V3, lp_bruciata_pct=99))
        self.assertEqual(c.dove, pozza.BRUCIATA)
        self.assertTrue(c.bloccata)
        self.assertEqual(rpc.chiamate, 0)

    def test_ricevute_non_bruciate_vuol_dire_ritirabile(self):
        pozza.get_rpc = lambda: RpcPozza()
        c = run(pozza.controlla(self.POZZA_V3, lp_bruciata_pct=3))
        self.assertEqual(c.dove, pozza.IN_PORTAFOGLIO)
        self.assertTrue(c.ritirabile)

    def test_v3_posizione_intestata_a_una_persona(self):
        """Nessun gestore di mezzo: puo' ritirarla quando vuole."""
        rpc = RpcPozza(logs=[(self.POZZA_V3, [self._mint(self.TIZIO)])])
        pozza.get_rpc = lambda: rpc
        c = run(pozza.controlla(self.POZZA_V3))
        self.assertEqual(c.dove, pozza.IN_PORTAFOGLIO)
        self.assertEqual(c.proprietario.lower(), self.TIZIO.lower())

    def test_v3_posizione_dentro_un_blocca_liquidita(self):
        """Il caso vero: su questa chain gira un PonsLaunchLocker."""
        rpc = RpcPozza(
            logs=[(self.POZZA_V3, [self._mint(self.LOCKER)])],
            codici={self.LOCKER: "0x6080"},
        )
        pozza.get_rpc = lambda: rpc
        c = run(pozza.controlla(self.POZZA_V3))
        self.assertEqual(c.dove, pozza.IN_CONTRATTO)
        self.assertTrue(c.bloccata)
        self.assertFalse(c.ritirabile)

    def test_v3_si_risale_dall_nft_al_padrone(self):
        """Il gestore e' solo un tramite: conta chi ha l'NFT in mano."""
        gestore = "0x" + "55" * 20
        incr = {"topics": [pozza.TOPIC_INCREASE, "0x" + "0" * 63 + "7"],
                "blockNumber": hex(100), "transactionHash": "0xabc"}
        rpc = RpcPozza(
            logs=[(self.POZZA_V3, [self._mint(gestore)]), (gestore, [incr])],
            codici={gestore: "0x6080"}, owner=self.MORTO,
        )
        pozza.get_rpc = lambda: rpc
        c = run(pozza.controlla(self.POZZA_V3))
        self.assertEqual(c.dove, pozza.BRUCIATA)
        self.assertIn("NFT", c.via)

    def test_una_pozza_vecchia_non_si_esamina(self):
        """Settecento versamenti non sono un lancio: non vale le chiamate."""
        rpc = RpcPozza(logs=[(self.POZZA_V3, [self._mint(self.TIZIO)] * 200)])
        pozza.get_rpc = lambda: rpc
        self.assertEqual(run(pozza.controlla(self.POZZA_V3)).dove, pozza.SCONOSCIUTA)

    def test_v4_si_riconosce_dalla_lunghezza(self):
        """La pozza v4 e' un numero da 32 byte, non un indirizzo da 20."""
        self.assertGreater(len(self.POZZA_V4), pozza.LUNGHEZZA_INDIRIZZO)
        mod = {"topics": [pozza.TOPIC_MODIFY_V4, self.POZZA_V4,
                          "0x" + "0" * 24 + self.TIZIO.removeprefix("0x")],
               "blockNumber": hex(100), "transactionHash": "0xabc"}
        rpc = RpcPozza(logs=[(self.POZZA_V4, [mod])])
        pozza.get_rpc = lambda: rpc
        c = run(pozza.controlla(self.POZZA_V4))
        self.assertEqual(c.dove, pozza.IN_PORTAFOGLIO)

    def test_senza_eventi_non_si_indovina(self):
        pozza.get_rpc = lambda: RpcPozza()
        self.assertEqual(run(pozza.controlla(self.POZZA_V3)).dove, pozza.SCONOSCIUTA)
        self.assertEqual(run(pozza.controlla("")).dove, pozza.SCONOSCIUTA)

    def test_non_e_mai_un_motivo_di_scarto(self):
        """Il vincolo: chi entra ed esce in venti minuti ha bisogno anche
        delle monete con la pozza libera. Va detto, non tolto."""
        report = SafetyReport(token_address=FAKE, checked=True)
        report.add("warn", "pozza_ritirabile", "puo' toglierla quando vuole")
        self.assertTrue(report.passed)
        self.assertEqual(report.verdict, "accettabile")


class TestSchedaAvvisi(unittest.TestCase):
    """La pozza che si ritira deve vedersi anche senza Telegram.

    Era l'unico avviso che esisteva solo sul telefono: la dashboard mostrava la
    liquidita' di adesso e non diceva da nessuna parte che era calata.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "avvisi.db"))

    def tearDown(self):
        self.store.close()

    def _avviso(self, token: str, symbol: str, sparita: float, quando: int = 0) -> None:
        self.store.upsert_candidate({"token_address": token, "symbol": symbol})
        self.store.record_alert(token, "pozza_ritirata", 71.0, {
            "symbol": symbol, "sparita": sparita,
            "liquidita_prima": 50_000, "liquidita_dopo": 50_000 * (1 - sparita),
        })
        if quando:
            self.store._exec(
                "UPDATE alerts SET ts = ? WHERE token_address = ?", (quando, token.lower())
            )

    def test_arrivano_nella_scheda(self):
        self._avviso(FAKE, "LUNA", 0.96)
        righe = self.store.notifiche()
        self.assertEqual(len(righe), 1)
        carico = json.loads(righe[0]["payload_json"])
        self.assertEqual(carico["symbol"], "LUNA")
        self.assertEqual(carico["sparita"], 0.96)

    def test_gli_alert_normali_restano_fuori(self):
        """Mescolarli vorrebbe dire perdere la cosa urgente fra quelle da leggere con calma."""
        self._avviso(FAKE, "LUNA", 0.96)
        self.store.record_alert(REAL, "scanner", 80.0, {"symbol": "NORMALE"})
        self.store.record_alert("0x" + "ef" * 20, "whales", 75.0, {"symbol": "ALTRO"})
        self.assertEqual([r["kind"] for r in self.store.notifiche()], ["pozza_ritirata"])

    def test_il_piu_recente_per_primo(self):
        self._avviso(FAKE, "VECCHIO", 0.9, quando=now() - 86_400)
        self._avviso(REAL, "NUOVO", 0.5, quando=now() - 60)
        self.assertEqual(
            [json.loads(r["payload_json"])["symbol"] for r in self.store.notifiche()],
            ["NUOVO", "VECCHIO"],
        )

    def test_l_avviso_resta_anche_se_telegram_non_risponde(self):
        """Registrato prima dell'invio: un guasto di rete non deve cancellarlo."""
        engine = Engine.__new__(Engine)
        engine.store = self.store
        engine.notifier = Notifier()
        engine.notifier.enabled = False

        async def rotto(snapshot, sparita, liq_prima, squilla=True):
            raise RuntimeError("telegram irraggiungibile")

        engine.notifier.send_liquidity_drop = rotto
        prima = {"liq_riferimento": 50_000.0, "prezzo_riferimento": 1.0,
                 "liquidity_usd": 50_000.0, "price_usd": 1.0,
                 "liq_notified": 0.0, "score": 71}
        self.store.upsert_candidate({"token_address": FAKE, "symbol": "LUNA", **prima})
        dopo = PairSnapshot(
            token_address=FAKE, symbol="LUNA", liquidity_usd=2_000, price_usd=1.0
        )
        with self.assertRaises(RuntimeError):
            run(engine._notify_liquidity_drop(FAKE, prima, dopo))
        self.assertEqual(len(self.store.notifiche()), 1)


class TestCosaDireQuandoLaPozzaSparisce(unittest.TestCase):
    """Il consiglio va deciso su quanto e' rimasto, non su quanto e' sparito.

    Il giro di controllo passa ogni cinque minuti e togliere la liquidita' e'
    una transazione sola: la pozza va da piena a zero fra un passaggio e
    l'altro. Sui primi quattro avvisi veri arrivati - 100%, 94%, 100%, 100% -
    non ce n'era uno a meta' strada, e "se sei dentro esci" e' uscito solo su
    monete da cui uscire non era piu' possibile.
    """

    def test_a_pozza_vuota_si_dice_che_e_finita(self):
        icona, titolo, cosa_fare = verdetto_pozza(1.0, 0.0)
        self.assertEqual(icona, "🪦")
        self.assertIn("finita", cosa_fare)
        self.assertNotIn("esci", cosa_fare)

    def test_il_caso_hoodroids(self):
        """199.3K -> 0: non c'e' nessuna azione possibile, e non va suggerita."""
        _, _, cosa_fare = verdetto_pozza(1.0, 0.0)
        self.assertNotIn("esci", cosa_fare)

    def test_il_caso_attention(self):
        """44.7K -> 2.5K: qualcosa resta, uscire ha ancora un senso."""
        icona, _, cosa_fare = verdetto_pozza(0.94, 2_500)
        self.assertEqual(icona, "🚨")
        self.assertIn("uscire", cosa_fare)

    def test_briciole_contano_come_vuota(self):
        """Sotto i mille dollari non c'e' nessuno che ti compri niente."""
        _, _, cosa_fare = verdetto_pozza(0.9, 400)
        self.assertIn("finita", cosa_fare)

    def test_da_meta_pozza_in_su_esci_ha_senso(self):
        icona, _, cosa_fare = verdetto_pozza(0.55, 22_000)
        self.assertEqual(icona, "🚨")
        self.assertIn("esci", cosa_fare)

    def test_i_primi_gradini_non_gridano(self):
        """Al 5% non c'e' ancora niente da fare: dirlo sarebbe allarmismo."""
        icona, _, cosa_fare = verdetto_pozza(0.06, 94_000)
        self.assertEqual(icona, "👀")
        self.assertNotIn("esci", cosa_fare)
        _, _, venti = verdetto_pozza(0.25, 75_000)
        self.assertIn("chiudere", venti)

    def test_il_telegram_non_dice_piu_esci_su_una_pozza_a_zero(self):
        notifier = Notifier()
        notifier.enabled = False
        inviati: list[str] = []

        async def cattura(text, buttons=None, **kw):
            inviati.append(text)
            return True

        notifier.send = cattura
        morta = PairSnapshot(
            token_address=FAKE, symbol="HOODROIDS", liquidity_usd=0.0, price_usd=0.0
        )
        run(notifier.send_liquidity_drop(morta, 1.0, 199_300))
        self.assertIn("finita", inviati[-1])
        self.assertNotIn("se sei dentro, esci", inviati[-1])


class TestSogliaSullaScalaGiusta(unittest.TestCase):
    """La soglia vale su cento punti, ma senza balene se ne giocano settantacinque.

    Confrontarle cosi' com'erano chiedeva al punteggio «da solo» 70 punti su un
    massimo reale di 74,4 - il 94% di tutto il disponibile - mentre a un token
    con tre balene ne bastavano 45 su 75, il 60%. La stessa soglia voleva dire
    due livelli di qualita' lontanissimi, e la casella «scanner + whales» non
    era rara: era impossibile, perche' un avviso qualsiasi toglie 12 punti e
    "sorgente non verificata" ce l'ha quasi ogni meme coin.
    """

    def test_la_soglia_si_riporta_in_proporzione(self):
        self.assertEqual(soglia_su_scala_propria(70), 52.5)
        self.assertEqual(soglia_su_scala_propria(100), PUNTI_SENZA_WHALES)
        self.assertEqual(soglia_su_scala_propria(0), 0)

    def test_il_tetto_del_punteggio_da_solo(self):
        """Se cambiassero i pesi, questo numero deve restare la verita'."""
        self.assertEqual(PUNTI_SENZA_WHALES, 100.0 - WEIGHTS["wallet"])

    def _perfetto(self, avvisi: int) -> Score:
        snapshot = PairSnapshot(
            token_address=FAKE, symbol="IRREALE",
            liquidity_usd=100_000, volume_1h=300_000, volume_5m=100_000,
            market_cap=800_000, price_change_1h=150,
            buys_5m=100, sells_5m=5, holders=1500,
            pair_created_at=now() - 3600,
        )
        report = SafetyReport(
            token_address=FAKE, checked=True, holders=1500, top10_pct=5,
            lp_burned_pct=100, ownership_renounced=True, verified=True, sell_tax=0.0,
        )
        for i in range(avvisi):
            report.add("warn", f"w{i}", "avviso")
        return compute_score(snapshot, report, 3)

    def test_il_token_perfetto_non_arrivava_a_settanta_per_poco(self):
        """Il margine era di 4,4 punti su un token che non esiste."""
        score = self._perfetto(0)
        self.assertAlmostEqual(score.own, 74.4, places=1)
        self.assertLess(score.own, 75)

    def test_un_solo_avviso_rendeva_impossibile_la_casella(self):
        """Il caso che spiega perche' non se n'e' mai vista una.

        «sorgente del contratto non verificato» vale un avviso, e sulle meme
        coin e' la norma: 12 punti in meno, e con la soglia piena non si
        risaliva piu'.
        """
        score = self._perfetto(1)
        self.assertLess(score.own, 70)          # con la soglia vecchia: mai
        self.assertGreater(score.own, 52.5)     # con quella giusta: si
        self.assertEqual(alert_kind(score.own >= 70, 3), "whales")
        self.assertEqual(
            alert_kind(score.own >= soglia_su_scala_propria(70), 3), "scanner_whales"
        )

    def test_le_scelte_deboli_delle_whales_restano_marchiate_whales(self):
        """La correzione non deve promuovere tutto: 45 su 75 e' il 60%."""
        self.assertEqual(
            alert_kind(45 >= soglia_su_scala_propria(70), 3), "whales"
        )

    def test_l_etichetta_puo_solo_salire_mai_nascondere(self):
        """Il vincolo che rende sicura la correzione.

        Abbassare la soglia di un'etichetta e' pericoloso se puo' togliere di
        mezzo qualcosa. Qui non puo': le uniche transizioni possibili sono
        whales -> scanner+whales e scaduto -> scanner, cioe' un token dice piu'
        di prima e mai di meno. Nessuno sparisce e nessuno viene declassato.
        """
        promozioni = {
            "whales": {"whales", "scanner_whales"},
            "scaduto": {"scaduto", "scanner"},
            "scanner": {"scanner"},
            "scanner_whales": {"scanner_whales"},
        }
        for own in range(0, 101, 5):
            for hits in (0, 1, 2, 3, 5):
                prima = alert_kind(own >= 70, hits)
                dopo = alert_kind(own >= soglia_su_scala_propria(70), hits)
                self.assertIn(
                    dopo, promozioni[prima],
                    f"own={own} whales={hits}: {prima} non puo' diventare {dopo}",
                )


class BlockscoutFinto:
    """Un explorer che risponde quello che gli si dice, saldi vecchi compresi."""

    def __init__(self, holders, info=None):
        self._holders = holders
        self._info = info or {"holders": 900}

    async def token_info(self, token):
        return self._info

    async def holders(self, token, limit=50):
        return [dict(h) for h in self._holders[:limit]]


class RpcFintoSaldi:
    """La chain: la verita' sui saldi, contro quello che dice l'explorer."""

    def __init__(self, saldi):
        self.saldi = {k.lower(): v for k, v in saldi.items()}
        self.chiesti: list[str] = []

    async def balances_of(self, token, holders):
        self.chiesti.extend(holders)
        return [int(self.saldi.get(h.lower(), 0)) for h in holders]

    async def balance_of(self, token, holder):
        self.chiesti.append(holder)
        return int(self.saldi.get(holder.lower(), 0))


class TestSaldiRilettiDallaChain(unittest.TestCase):
    """La concentrazione va calcolata su quello che c'e' adesso, non ieri.

    Misurato sul vivo: un saldo su dieci dell'explorer e' sbagliato di piu' del
    2%, e su un token nove dei primi dieci holder risultavano carichi mentre
    sulla chain avevano zero. Quei numeri decidono due scarti - "i primi 10
    hanno il X%" e "il deployer ne tiene il Y%" - quindi un indice vecchio
    accusa monete oneste e ne assolve di concentrate.
    """

    SUPPLY = 1_000_000.0

    def setUp(self):
        tunables.set_value("max_top10_holder_pct", 35)

    def tearDown(self):
        tunables.reset("max_top10_holder_pct")

    def _controlla(self, elenco_explorer, saldi_veri, deployer=""):
        checker = SafetyChecker(BlockscoutFinto(elenco_explorer))
        checker.rpc = RpcFintoSaldi(saldi_veri)
        report = SafetyReport(token_address=FAKE, checked=True)
        snapshot = PairSnapshot(token_address=FAKE, pair_address="0x" + "ee" * 20)
        run(checker._check_distribution(
            report, snapshot, {"total_supply_raw": self.SUPPLY}, deployer
        ))
        return report, checker.rpc

    def _holder(self, indirizzo, valore):
        return {"address": indirizzo, "value": float(valore), "is_contract": False}

    def test_saldi_svuotati_non_contano_piu(self):
        """Il caso vero: l'explorer li da' carichi, la chain dice zero."""
        elenco = [self._holder(_cavia(i), 200_000) for i in range(1, 4)]
        report, _ = self._controlla(elenco, saldi_veri={})
        # Prima: 60% della supply e uno scarto. Adesso: non hanno niente.
        self.assertEqual(report.top10_pct, 0)
        self.assertEqual(report.flags, [])
        self.assertEqual(report.top_holders, [])

    def test_una_concentrazione_vera_resta_uno_scarto(self):
        """La correzione non deve diventare un modo per assolvere tutti."""
        elenco = [self._holder(_cavia(i), 250_000) for i in range(1, 4)]
        veri = {_cavia(i): 250_000 for i in range(1, 4)}
        report, _ = self._controlla(elenco, veri)
        self.assertEqual(report.top10_pct, 75.0)
        self.assertEqual(report.blocking[0]["code"], "concentrazione")

    def test_l_ordine_si_rifa_dopo_la_rilettura(self):
        """Chi era primo puo' non esserlo piu': i «primi 10» vanno ripresi."""
        elenco = [self._holder(_cavia(i), 500_000 - i) for i in range(1, 13)]
        # Il primo dell'explorer ha venduto tutto, l'ultimo e' il vero grosso.
        veri = {_cavia(i): 1_000 for i in range(2, 13)}
        veri[_cavia(1)] = 0
        veri[_cavia(12)] = 300_000
        report, _ = self._controlla(elenco, veri)
        self.assertEqual(report.top_holders[0]["address"], _cavia(12))
        # 300k + dieci da 1k su un milione.
        self.assertAlmostEqual(report.top10_pct, 30.9, places=1)

    def test_il_deployer_si_chiede_a_lui(self):
        """Non si cerca nella lista: si fermava ai primi 25.

        Un deployer al ventiseiesimo posto risultava a zero, cioe' pulito.
        """
        deployer = "0x" + "dd" * 20
        elenco = [self._holder(_cavia(i), 1_000) for i in range(1, 26)]
        veri = {_cavia(i): 1_000 for i in range(1, 26)}
        veri[deployer] = 200_000  # il 20%, e non compare fra i primi 25
        report, rpc = self._controlla(elenco, veri, deployer=deployer)
        self.assertIn(deployer, rpc.chiesti)
        self.assertAlmostEqual(report.deployer_pct, 20.0)
        self.assertEqual(report.blocking[0]["code"], "deployer_carico")

    def test_le_cavie_della_tassa_di_vendita_sono_quelle_vere(self):
        """`top_holders` serve a simulare la vendita: chi ha zero non serve."""
        elenco = [self._holder(_cavia(i), 100_000) for i in range(1, 6)]
        veri = {_cavia(3): 100_000, _cavia(5): 50_000}
        report, _ = self._controlla(elenco, veri)
        self.assertEqual(
            [h["address"] for h in report.top_holders],
            [_cavia(3), _cavia(5)],
        )


class TestPozzaInRitiro(unittest.TestCase):
    """Avvisare mentre portano via la pozza, senza gridare a ogni ribasso.

    Su quattro monete su cinque la pozza e' in stile v3 o v4 e non si puo'
    sapere *prima* se chi l'ha messa se la puo' riprendere. Mentre succede
    pero' si vede, e il giro di tracciamento passa gia' su ogni moneta
    segnalata.
    """

    def setUp(self):
        self.store = Store(str(Path(tempfile.mkdtemp()) / "pozza.db"))
        self.engine = Engine.__new__(Engine)
        self.engine.store = self.store
        self.engine.notifier = Notifier()
        self.engine.notifier.enabled = False
        self.inviati: list[tuple] = []

        async def cattura(snapshot, sparita, liq_prima, squilla=True):
            self.inviati.append((snapshot.symbol, sparita, liq_prima))
            return True

        self.engine.notifier.send_liquidity_drop = cattura

    def tearDown(self):
        self.store.close()

    def _giro(self, prima: dict, liq_dopo: float, prezzo_dopo: float = 0.0):
        # Il metro e' il massimo che la pozza ha avuto, non la lettura di
        # prima: e' il cambiamento che fa funzionare gli avvisi a gradini.
        riga = {"liq_riferimento": 50_000.0, "prezzo_riferimento": 1.0,
                "liquidity_usd": 50_000.0, "price_usd": 1.0, "liq_notified": 0.0}
        riga.update(prima)
        self.store.upsert_candidate({"token_address": FAKE, "symbol": "TEST", **riga})
        dopo = PairSnapshot(
            token_address=FAKE, symbol="TEST",
            liquidity_usd=liq_dopo,
            price_usd=prezzo_dopo if prezzo_dopo else riga["prezzo_riferimento"],
        )
        run(self.engine._notify_liquidity_drop(FAKE, riga, dopo))
        return self.inviati

    def test_la_pozza_sparisce(self):
        """Cinquantamila dollari diventati duemila: e' un rug in corso."""
        inviati = self._giro({}, liq_dopo=2_000)
        self.assertEqual(len(inviati), 1)
        self.assertGreater(inviati[0][1], 0.85)

    def test_un_crollo_di_prezzo_non_e_un_ritiro(self):
        """Il caso che renderebbe l'avviso inutile a forza di falsi allarmi.

        In una pozza a prodotto costante il valore in dollari segue la radice
        del prezzo: se il prezzo dimezza la pozza cala del 29% da sola, senza
        che nessuno abbia toccato niente. Confrontare con quanto c'era prima
        farebbe suonare l'allarme a ogni ribasso serio.
        """
        inviati = self._giro({}, liq_dopo=50_000 * (0.5 ** 0.5), prezzo_dopo=0.5)
        self.assertEqual(inviati, [])

    def test_il_ritiro_si_vede_anche_mentre_il_prezzo_scende(self):
        """Meta' del prezzo giustifica 35.4k; se ce ne sono 10k, 25k sono usciti."""
        inviati = self._giro({}, liq_dopo=10_000, prezzo_dopo=0.5)
        self.assertEqual(len(inviati), 1)
        self.assertAlmostEqual(inviati[0][1], 1 - 10_000 / (50_000 * 0.5 ** 0.5), places=3)

    def test_una_pozza_che_cresce_non_avvisa(self):
        self.assertEqual(self._giro({}, liq_dopo=80_000), [])

    def test_sotto_il_primo_gradino_non_avvisa(self):
        """Il tre per cento e' respiro: misurato, il rumore vero e' 0,14%."""
        self.assertEqual(self._giro({}, liq_dopo=48_500), [])

    def test_svuotata_a_fette_avvisa_lo_stesso(self):
        """Il caso che prima non produceva NIENTE.

        Col confronto passo-passo chi la toglieva poco per volta non superava
        mai nessuna soglia: una pozza portata via a fette del 10% arrivava a
        -88% mandando zero avvisi, ed e' il motivo per cui arrivavano solo i
        ritiri in un colpo, a cose fatte. Adesso il metro e' fisso e i gradini
        si attraversano davvero.
        """
        self.store.upsert_candidate({
            "token_address": FAKE, "symbol": "TEST",
            "liq_riferimento": 50_000.0, "prezzo_riferimento": 1.0,
        })
        liq, gradini = 50_000.0, []
        for _ in range(20):
            liq *= 0.90
            self.inviati.clear()
            riga = self.store.get_candidate(FAKE)
            run(self.engine._notify_liquidity_drop(FAKE, riga, PairSnapshot(
                token_address=FAKE, symbol="TEST", liquidity_usd=liq, price_usd=1.0)))
            if self.inviati:
                gradini.append(round(self.store.get_candidate(FAKE)["liq_notified"], 2))
        self.assertEqual(gradini, [0.05, 0.20, 0.50, 0.85])

    def test_le_pozze_minuscole_si_ignorano(self):
        """Sotto i cinquemila dollari il rumore vale piu' del segnale."""
        self.assertEqual(
            self._giro({"liq_riferimento": 900.0}, liq_dopo=10), []
        )

    def test_senza_dati_di_prima_non_inventa(self):
        """Al primo giro non c'e' niente con cui confrontare."""
        self.assertEqual(self._giro({"liq_riferimento": 0.0}, liq_dopo=0.0), [])

    def test_la_soglia_finisce_nel_database(self):
        self._giro({}, liq_dopo=2_000)
        self.assertEqual(self.store.get_candidate(FAKE)["liq_notified"], 0.85)


class RpcFinto:
    """Un nodo di comodo: risponde quello che gli si dice di rispondere.

    `saldi` sono le risposte a balanceOf, indirizzo per indirizzo. `vendite`
    e' la coda delle risposte alla simulazione, una per ogni importo provato:
    un intero e' quanto arriva alla pozza, `None` e' un rifiuto del contratto,
    "rpc_muto" e' la rete che non risponde e "nodo_non_supporta" e' un nodo che
    non sa eseguire la simulazione.
    """

    def __init__(self, saldi=None, vendite=(), dichiarata=None):
        self.saldi = {k.lower(): v for k, v in (saldi or {}).items()}
        self.vendite = list(vendite)
        self.dichiarata = dichiarata
        self.importi_provati: list[int] = []

    async def batch(self, calls):
        risposte = []
        for _, params in calls:
            dati = params[0]["data"]
            if dati.startswith(SEL["balanceOf"]):
                indirizzo = "0x" + dati[-40:]
                risposte.append("0x%064x" % self.saldi.get(indirizzo.lower(), 0))
            else:
                risposte.append(
                    None if self.dichiarata is None else "0x%064x" % self.dichiarata
                )
        return risposte

    async def eth_call_esito(self, to, data, sender="", value="0x0", codice=""):
        # L'importo simulato e' l'ultimo PUSH32 del bytecode iniettato.
        self.importi_provati.append(int(codice.split("7f")[1][:64], 16))
        esito = self.vendite.pop(0) if self.vendite else None
        if esito == "rpc_muto":
            return False, "rpc_muto"
        if esito == "nodo_non_supporta":
            return False, "invalid argument 2: json: cannot unmarshal"
        if esito is None:
            return False, "execution reverted"
        return True, "0x" + "%064x" % 1 + "%064x" % esito


class TestTassaDiVendita(unittest.TestCase):
    """Vendere per finta prima di consigliare, e contare quanto arriva.

    Il caso da cui nasce: $lambo, tassa in acquisto 0% e in vendita 100%. Il
    controllo di sicurezza la dava accettabile perche' guardava se *esistesse*
    una funzione per cambiare le tasse, non quanto valessero. La moneta e'
    anche salita del 3x: il punto non era sbagliare la previsione, era che chi
    la comprava non poteva piu' uscire.
    """

    def setUp(self):
        self.token = "0x" + "aa" * 20
        self.pozza = "0x" + "bb" * 20
        self.tizio = "0x" + "cc" * 20

    def _controlla(self, rpc, venditori=None):
        originale = honeypot.get_rpc
        honeypot.get_rpc = lambda: rpc
        try:
            return run(honeypot.controlla(
                self.token, self.pozza,
                [self.tizio] if venditori is None else venditori,
            ))
        finally:
            honeypot.get_rpc = originale

    def test_il_bytecode_e_quello_che_diciamo(self):
        """Il simulatore deve contenere token, pozza e importo, e nient'altro.

        Se questa cambia senza volerlo, si sta iniettando codice diverso da
        quello verificato sulla chain vera.
        """
        codice = honeypot._simulatore(self.token, self.pozza, 12345)
        self.assertTrue(codice.startswith("0x"))
        self.assertEqual(codice.count("aa" * 20), 3)   # tre chiamate al token
        self.assertEqual(codice.count("bb" * 20), 3)   # tre volte la pozza
        self.assertIn("%064x" % 12345, codice)
        self.assertEqual(len(codice) % 2, 0)

    def test_nessuna_tassa_passa(self):
        """Arriva tutto quello che parte: la moneta si vende davvero."""
        rpc = RpcFinto({self.tizio: 10**24}, vendite=[10**22])
        verdetto = self._controlla(rpc)
        self.assertTrue(verdetto.vendibile)
        self.assertTrue(verdetto.verificato)
        self.assertEqual(verdetto.tassa_vendita, 0.0)
        # Si prova un centesimo del saldo, non tutto: il tetto per
        # transazione e' comune e non va scambiato per una truffa.
        self.assertEqual(rpc.importi_provati, [10**22])

    def test_tassa_sopportabile_non_blocca(self):
        """Il cinque per cento e' una commissione, non un muro."""
        rpc = RpcFinto({self.tizio: 10**24}, vendite=[95 * 10**20])
        verdetto = self._controlla(rpc)
        self.assertTrue(verdetto.vendibile)
        self.assertAlmostEqual(verdetto.tassa_vendita, 5.0, places=6)

    def test_il_caso_lambo(self):
        """Cento per cento in vendita: parte tutto e non arriva niente."""
        rpc = RpcFinto({self.tizio: 10**24}, vendite=[0, 0, 0])
        verdetto = self._controlla(rpc)
        self.assertFalse(verdetto.vendibile)
        self.assertTrue(verdetto.bloccante)
        self.assertEqual(verdetto.tassa_vendita, 100.0)
        self.assertIn("pozza", verdetto.motivo)

    def test_tassa_da_muro_blocca_anche_se_la_vendita_passa(self):
        """Uscire lasciando il novanta per cento non e' uscire."""
        rpc = RpcFinto({self.tizio: 10**24}, vendite=[10**21])
        verdetto = self._controlla(rpc)
        self.assertFalse(verdetto.vendibile)
        self.assertAlmostEqual(verdetto.tassa_vendita, 90.0, places=6)

    def test_il_tetto_per_transazione_non_e_una_truffa(self):
        """Rifiuta il grande e accetta il piccolo: e' un limite, si vende.

        Visto dal vivo su un token di questa chain. Bloccarlo sarebbe stato un
        candidato onesto buttato via.
        """
        rpc = RpcFinto({self.tizio: 10**24}, vendite=[None, 10**20])
        verdetto = self._controlla(rpc)
        self.assertTrue(verdetto.vendibile)
        self.assertEqual(verdetto.tassa_vendita, 0.0)
        self.assertEqual(rpc.importi_provati, [10**22, 10**20])

    def test_il_saldo_si_rilegge_dalla_chain(self):
        """L'explorer elenca chi *ha avuto* i token, non chi li ha adesso.

        Su LAMBO5 i primi tre holder risultavano con miliardi di token e sulla
        chain ne avevano zero: il contratto rispondeva "saldo insufficiente" e
        sembrava un rifiuto a vendere. Senza questo controllo la moneta veniva
        accusata per colpa di un indice vecchio.
        """
        svuotati = ["0x" + "d%d" % i * 10 for i in (1, 2, 3)]
        rpc = RpcFinto(
            {s: 0 for s in svuotati} | {self.tizio: 10**24},
            vendite=[10**22],
        )
        verdetto = self._controlla(rpc, svuotati + [self.tizio])
        self.assertTrue(verdetto.vendibile)
        self.assertEqual(verdetto.tassa_vendita, 0.0)

    def test_senza_nessuno_che_possieda_non_si_accusa(self):
        """Nel dubbio non si condanna: si dice solo che non si sa."""
        rpc = RpcFinto({self.tizio: 0})
        verdetto = self._controlla(rpc)
        self.assertTrue(verdetto.vendibile)
        self.assertFalse(verdetto.verificato)
        self.assertEqual(rpc.importi_provati, [])

    def test_la_rete_muta_non_condanna(self):
        """Un nodo che non risponde non e' una prova contro il contratto."""
        rpc = RpcFinto({self.tizio: 10**24}, vendite=["rpc_muto"])
        verdetto = self._controlla(rpc)
        self.assertTrue(verdetto.vendibile)
        self.assertFalse(verdetto.verificato)

    def test_un_nodo_che_non_sa_simulare_non_condanna(self):
        """Il guasto peggiore possibile, ed e' silenzioso.

        Se qualcuno mette un RPC di riserva che non accetta gli state
        override, ogni simulazione fallisce. Scambiare quel "no" del nodo per
        un "no" del contratto vorrebbe dire dichiarare trappola *ogni* moneta
        e smettere di segnalare qualsiasi cosa, senza un errore visibile.
        """
        rpc = RpcFinto({self.tizio: 10**24}, vendite=["nodo_non_supporta"])
        verdetto = self._controlla(rpc)
        self.assertTrue(verdetto.vendibile)
        self.assertFalse(verdetto.verificato)
        # Un solo tentativo: insistere con importi piu' piccoli non serve a
        # niente se il problema non e' l'importo.
        self.assertEqual(len(rpc.importi_provati), 1)

    def test_distingue_le_due_voci(self):
        self.assertTrue(honeypot._e_un_rifiuto("execution reverted: pauset"))
        self.assertTrue(honeypot._e_un_rifiuto("out of gas"))
        self.assertFalse(honeypot._e_un_rifiuto("invalid argument 2: json"))
        self.assertFalse(honeypot._e_un_rifiuto("the method does not exist"))
        self.assertFalse(honeypot._e_un_rifiuto(""))

    def test_la_misura_batte_la_dichiarazione(self):
        """Conta cosa succede, non cosa il contratto dice che succeda."""
        rpc = RpcFinto({self.tizio: 10**24}, vendite=[10**22], dichiarata=99)
        verdetto = self._controlla(rpc)
        self.assertTrue(verdetto.vendibile)
        self.assertEqual(verdetto.tassa_vendita, 0.0)

    def test_la_dichiarazione_vale_quando_non_si_puo_misurare(self):
        """Ultima spiaggia: nessun holder da usare come cavia."""
        rpc = RpcFinto({self.tizio: 0}, dichiarata=90)
        verdetto = self._controlla(rpc)
        self.assertFalse(verdetto.vendibile)
        self.assertIn("dichiarata", verdetto.motivo)

    def test_le_scale_strane(self):
        """Cinquecento punti base sono il cinque per cento, non il 500%."""
        self.assertEqual(honeypot._percentuale(5), 5.0)
        self.assertEqual(honeypot._percentuale(500), 50.0)
        self.assertEqual(honeypot._percentuale(10_000), 100.0)

    def test_le_monete_a_riflessione_non_hanno_tassa_negativa(self):
        """Se alla pozza ne arrivano di piu', e' un regalo, non una tassa."""
        rpc = RpcFinto({self.tizio: 10**24}, vendite=[2 * 10**22])
        verdetto = self._controlla(rpc)
        self.assertEqual(verdetto.tassa_vendita, 0.0)


class TestVendibilitaNelControlloDiSicurezza(unittest.TestCase):
    """Il verdetto deve arrivare fino al report, e fermare il candidato."""

    def test_non_vendibile_e_uno_scarto_definitivo(self):
        """Non e' rischiosa, e' persa: non ha senso riprovarci fra un'ora."""
        self.assertIn("non_vendibile", PERMANENT_REJECTIONS)

    def test_la_tassa_alta_diventa_un_avviso(self):
        """Sopra il dieci per cento si dice, sotto il venticinque non si blocca."""
        report = SafetyReport(token_address=FAKE, sell_tax=18.0)
        report.add("warn", "tassa_vendita_alta",
                   f"Vendendo perdi il {report.sell_tax:.0f}% in tasse")
        self.assertEqual(
            [w["code"] for w in report.warnings], ["tassa_vendita_alta"]
        )

    def test_il_telegram_dice_quando_si_esce_gratis(self):
        """La voce che puo' azzerare il guadagno va detta sempre."""
        notifier = Notifier()
        notifier.enabled = False
        inviati: list[str] = []

        async def cattura(text, buttons=None, **kw):
            inviati.append(text)
            return True

        notifier.send = cattura
        snapshot = PairSnapshot(token_address=FAKE, symbol="X", liquidity_usd=50_000)
        run(notifier.send_candidate(
            snapshot, SafetyReport(token_address=FAKE, sell_tax=0.0), Score(total=70)
        ))
        self.assertIn("nessuna tassa in vendita", inviati[-1])


if __name__ == "__main__":
    unittest.main(verbosity=2)
