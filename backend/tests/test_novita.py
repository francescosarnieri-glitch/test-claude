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
from memescan.scoring import passes_prefilter  # noqa: E402
from memescan.store import Store  # noqa: E402
from memescan.util import now  # noqa: E402

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
