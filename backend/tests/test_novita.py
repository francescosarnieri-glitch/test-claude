"""Test delle funzioni aggiunte dopo il primo rilascio.

Coprono il caso che ha causato la perdita reale: un token che copia il simbolo
di uno gia' avviato, con contratto pulito e quindi capace di superare tutti i
controlli anti-rug.
"""

from __future__ import annotations

import asyncio
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ.setdefault("DB_PATH", str(Path(tempfile.mkdtemp()) / "novita.db"))

from memescan import clones, tunables  # noqa: E402
from memescan.models import PairSnapshot  # noqa: E402
from memescan.notify import Notifier  # noqa: E402
from memescan.safety import SafetyReport  # noqa: E402
from memescan.scoring import Score, compute_score, passes_prefilter  # noqa: E402
from memescan.store import Store  # noqa: E402
from memescan.util import now  # noqa: E402
from memescan.wallets import WalletTracker  # noqa: E402
from memescan.worker import Engine, alert_kind  # noqa: E402

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

    def test_un_acquisto_vecchio_conta_ancora(self):
        """Chi ha comprato e non ha piu' mosso niente e' dentro, non scade."""
        self._evento("0xaa", "buy", now() - 40 * 86400, "t1")
        self.assertEqual(self.store.count_wallet_holders(FAKE), 1)
        self.assertEqual(self.store.count_distinct_wallet_buyers(FAKE), 0)

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

    def tearDown(self):
        import memescan.store as store_module

        store_module._store = self._original
        self.store.close()

    async def token_transfers(self, address: str, limit: int = 40) -> list[dict]:
        wallet = "0x" + "aa" * 20
        return [
            {"token_address": FAKE, "symbol": "TEST", "to": wallet, "from": "0xpool",
             "tx_hash": "0x01", "block_number": 11, "timestamp": ""},
            {"token_address": FAKE, "symbol": "TEST", "to": "0xpool", "from": wallet,
             "tx_hash": "0x02", "block_number": 12, "timestamp": ""},
        ]

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

    def test_balene_uscite_torna_allo_scanner(self):
        """Un token abbandonato dalle balene non resta nel loro elenco."""
        self.assertEqual(alert_kind(consigliato=False, wallet_hits=0), "scanner")

    def test_basta_una_balena(self):
        self.assertEqual(alert_kind(consigliato=True, wallet_hits=1), "scanner_whales")

    def test_ogni_combinazione_produce_una_casella_sola(self):
        """Se due casi dessero la stessa casella, i conti non tornerebbero."""
        caselle = {
            alert_kind(consigliato=c, wallet_hits=w)
            for c in (True, False) for w in (0, 3)
        }
        self.assertEqual(caselle, {"scanner", "scanner_whales", "whales"})


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
