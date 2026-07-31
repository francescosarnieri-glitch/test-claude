"""Test della logica che non dipende dalla rete.

Sono i punti dove un errore silenzioso costa caro: decodifica degli eventi di
creazione pool (sbagliare offset significa seguire l'indirizzo sbagliato),
riconoscimento degli honeypot, e la contabilita' dello storico alert.
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

os.environ.setdefault("DB_PATH", str(Path(tempfile.mkdtemp()) / "test.db"))

from memescan.chain import (  # noqa: E402
    TOPIC_PAIR_CREATED,
    TOPIC_POOL_CREATED,
    TOPIC_TRANSFER,
    bytecode_flags,
    selector,
)
from memescan.models import PairSnapshot, merge_snapshots  # noqa: E402
from memescan.safety import SafetyChecker, SafetyReport  # noqa: E402
from memescan.scoring import compute_score, passes_prefilter  # noqa: E402
from memescan.sources.dexscreener import DexscreenerSource  # noqa: E402
from memescan.sources.onchain import OnchainSource  # noqa: E402
from memescan.store import Store  # noqa: E402
from memescan.util import human_usd, safe_float  # noqa: E402

TOKEN = "0x" + "ab" * 20
QUOTE = "0x" + "cd" * 20
POOL = "0x" + "ef" * 20


def _topic(address: str) -> str:
    return "0x" + "00" * 12 + address[2:]


class TestChainPrimitives(unittest.TestCase):
    def test_known_event_topics(self):
        # Valori canonici: se cambiano, la lettura dei log e' rotta.
        self.assertEqual(
            TOPIC_TRANSFER,
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
        )
        self.assertEqual(
            TOPIC_PAIR_CREATED,
            "0x0d3648bd0f6ba80134a33ba9275ac585d9d315f0ad8355cddefde31afa28d0e9",
        )
        self.assertEqual(
            TOPIC_POOL_CREATED,
            "0x783cca1c0412dd0d695e784568c96da2e9c22ff989357a2e8b1d9b2b4e6b7118",
        )

    def test_known_selectors(self):
        self.assertEqual(selector("transfer(address,uint256)"), "0xa9059cbb")
        self.assertEqual(selector("balanceOf(address)"), "0x70a08231")
        self.assertEqual(selector("totalSupply()"), "0x18160ddd")

    def test_bytecode_flags_detects_mint(self):
        mint_selector = selector("mint(address,uint256)")[2:]
        code = "0x6080604052" + mint_selector + "00" * 10
        self.assertIn("mint", bytecode_flags(code))

    def test_bytecode_flags_empty_contract(self):
        self.assertEqual(bytecode_flags("0x"), [])
        self.assertEqual(bytecode_flags(""), [])


class TestOnchainDecoding(unittest.TestCase):
    def setUp(self):
        self.source = OnchainSource()
        self.source.quote_tokens = {QUOTE}

    def test_decode_v2_pair_created(self):
        # PairCreated(token0, token1, pair, uint): i dati contengono pair + indice.
        data = "0x" + "00" * 12 + POOL[2:] + "00" * 31 + "01"
        entry = {
            "topics": [TOPIC_PAIR_CREATED, _topic(TOKEN), _topic(QUOTE)],
            "data": data,
            "blockNumber": "0x64",
        }
        token0, token1, pool = self.source._decode_creation(entry)
        self.assertEqual(token0, TOKEN)
        self.assertEqual(token1, QUOTE)
        self.assertEqual(pool, POOL)

    def test_decode_v3_pool_created(self):
        # PoolCreated(token0, token1, fee, tickSpacing, pool): fee e' indicizzata,
        # nei dati restano tickSpacing e pool.
        data = "0x" + "00" * 31 + "3c" + "00" * 12 + POOL[2:]
        entry = {
            "topics": [TOPIC_POOL_CREATED, _topic(TOKEN), _topic(QUOTE), "0x" + "00" * 31 + "0a"],
            "data": data,
            "blockNumber": "0x64",
        }
        token0, token1, pool = self.source._decode_creation(entry)
        self.assertEqual(token0, TOKEN)
        self.assertEqual(pool, POOL)

    def test_decode_rejects_truncated_data(self):
        entry = {"topics": [TOPIC_PAIR_CREATED, _topic(TOKEN), _topic(QUOTE)], "data": "0x00"}
        self.assertIsNone(self.source._decode_creation(entry))

    def test_pick_base_token(self):
        # Il lato "nuovo" e' quello che non e' un token quote noto.
        self.assertEqual(self.source._pick_base_token(TOKEN, QUOTE), TOKEN)
        self.assertEqual(self.source._pick_base_token(QUOTE, TOKEN), TOKEN)
        # Due token sconosciuti: impossibile decidere, si lascia perdere.
        self.assertIsNone(self.source._pick_base_token(TOKEN, "0x" + "11" * 20))
        # Due quote noti: non e' un lancio.
        self.source.quote_tokens.add(TOKEN)
        self.assertIsNone(self.source._pick_base_token(TOKEN, QUOTE))


class TestDexscreenerParsing(unittest.TestCase):
    def setUp(self):
        self.source = DexscreenerSource()

    def test_parses_full_pair(self):
        snapshot = self.source._to_snapshot(
            {
                "chainId": "robinhood",
                "dexId": "uniswap",
                "pairAddress": POOL,
                "baseToken": {"address": TOKEN, "symbol": "CAT", "name": "Cash Cat"},
                "quoteToken": {"symbol": "WETH"},
                "priceUsd": "0.1048",
                "pairCreatedAt": 1_750_000_000_000,
                "txns": {"m5": {"buys": 40, "sells": 12}},
                "volume": {"m5": 5000, "h1": 42000, "h24": 300000},
                "priceChange": {"m5": 4.2, "h1": 88.0},
                "liquidity": {"usd": 120000},
                "fdv": 2_400_000,
                "info": {"socials": [{"type": "twitter", "url": "https://x.com/x"}]},
            }
        )
        self.assertEqual(snapshot.symbol, "CAT")
        self.assertEqual(snapshot.token_address, TOKEN)
        # pairCreatedAt arriva in millisecondi e va convertito in secondi.
        self.assertEqual(snapshot.pair_created_at, 1_750_000_000)
        self.assertAlmostEqual(snapshot.liquidity_usd, 120000)
        self.assertEqual(snapshot.txns_5m, 52)
        self.assertAlmostEqual(snapshot.buy_ratio_5m, 40 / 52)
        self.assertEqual(snapshot.socials["twitter"], "https://x.com/x")

    def test_rejects_pair_without_base_token(self):
        self.assertIsNone(self.source._to_snapshot({"baseToken": {}}))

    def test_best_pair_picks_deepest_liquidity(self):
        pairs = [
            {"chainId": "robinhood", "liquidity": {"usd": 1000}, "pairAddress": "a"},
            {"chainId": "robinhood", "liquidity": {"usd": 90000}, "pairAddress": "b"},
            {"chainId": "solana", "liquidity": {"usd": 999999}, "pairAddress": "c"},
        ]
        self.assertEqual(self.source._best_pair(pairs)["pairAddress"], "b")


class TestSafety(unittest.TestCase):
    def setUp(self):
        self.checker = SafetyChecker(blockscout=None)

    def _report(self) -> SafetyReport:
        return SafetyReport(token_address=TOKEN, checked=True)

    def test_honeypot_signature_blocks(self):
        report = self._report()
        snapshot = PairSnapshot(token_address=TOKEN, buys_5m=60, sells_5m=0)
        self.checker._check_honeypot_signature(report, snapshot)
        self.assertTrue(report.blocking)
        self.assertEqual(report.blocking[0]["code"], "honeypot_probabile")
        self.assertEqual(report.verdict, "pericoloso")
        self.assertFalse(report.passed)

    def test_healthy_market_has_no_flags(self):
        report = self._report()
        snapshot = PairSnapshot(
            token_address=TOKEN, buys_5m=40, sells_5m=25,
            liquidity_usd=100_000, market_cap=1_000_000, volume_1h=200_000,
        )
        self.checker._check_honeypot_signature(report, snapshot)
        self.assertEqual(report.flags, [])
        self.assertEqual(report.verdict, "pulito")
        self.assertTrue(report.passed)

    def test_thin_liquidity_warns(self):
        report = self._report()
        snapshot = PairSnapshot(
            token_address=TOKEN, buys_5m=30, sells_5m=20,
            liquidity_usd=5_000, market_cap=5_000_000,
        )
        self.checker._check_honeypot_signature(report, snapshot)
        codes = {f["code"] for f in report.warnings}
        self.assertIn("liquidita_sottile", codes)

    def test_penalty_is_capped(self):
        report = self._report()
        for i in range(10):
            report.add("warn", f"c{i}", "problema")
        self.assertEqual(report.penalty(), 30.0)

    def test_unchecked_report_never_passes(self):
        report = SafetyReport(token_address=TOKEN)
        self.assertFalse(report.passed)
        self.assertEqual(report.verdict, "sconosciuto")


class TestScoring(unittest.TestCase):
    def _good_snapshot(self) -> PairSnapshot:
        import time

        return PairSnapshot(
            token_address=TOKEN,
            symbol="CAT",
            pair_created_at=int(time.time()) - 3600,
            liquidity_usd=90_000,
            market_cap=800_000,
            volume_1h=120_000,
            volume_5m=18_000,
            price_change_1h=60,
            buys_5m=70,
            sells_5m=25,
        )

    def _good_safety(self) -> SafetyReport:
        return SafetyReport(
            token_address=TOKEN, checked=True, holders=600, top10_pct=18,
            lp_burned_pct=100, ownership_renounced=True, verified=True,
        )

    def test_prefilter_accepts_healthy_pair(self):
        ok, reason = passes_prefilter(self._good_snapshot())
        self.assertTrue(ok, reason)

    def test_prefilter_rejects_low_liquidity(self):
        snapshot = self._good_snapshot()
        snapshot.liquidity_usd = 500
        ok, reason = passes_prefilter(snapshot)
        self.assertFalse(ok)
        self.assertEqual(reason, "liquidita_insufficiente")

    def test_prefilter_rejects_too_young(self):
        import time

        snapshot = self._good_snapshot()
        snapshot.pair_created_at = int(time.time()) - 10
        ok, reason = passes_prefilter(snapshot)
        self.assertFalse(ok)
        self.assertEqual(reason, "troppo_giovane")

    def test_good_token_scores_well(self):
        score = compute_score(self._good_snapshot(), self._good_safety())
        self.assertGreater(score.total, 50)
        self.assertLessEqual(score.total, 100)

    def test_wallet_signal_raises_score(self):
        snapshot, safety = self._good_snapshot(), self._good_safety()
        base = compute_score(snapshot, safety, wallet_hits=0).total
        with_wallets = compute_score(snapshot, safety, wallet_hits=3).total
        self.assertGreater(with_wallets, base)
        # Tre wallet distinti saturano la componente: e' il segnale piu' pesante.
        self.assertAlmostEqual(with_wallets - base, 25.0, places=1)

    def test_warnings_reduce_score(self):
        snapshot, safety = self._good_snapshot(), self._good_safety()
        clean = compute_score(snapshot, safety).total
        safety.add("warn", "fee_mutabile", "tasse modificabili")
        self.assertLess(compute_score(snapshot, safety).total, clean)

    def test_score_never_leaves_bounds(self):
        empty = compute_score(PairSnapshot(token_address=TOKEN), SafetyReport(token_address=TOKEN))
        self.assertGreaterEqual(empty.total, 0)
        self.assertLessEqual(empty.total, 100)

    def test_overextended_price_is_penalised(self):
        snapshot = self._good_snapshot()
        snapshot.price_change_1h = 800
        blown = compute_score(snapshot, self._good_safety())
        snapshot.price_change_1h = 60
        healthy = compute_score(snapshot, self._good_safety())
        self.assertLess(blown.total, healthy.total)


class TestStore(unittest.TestCase):
    def setUp(self):
        self.path = str(Path(tempfile.mkdtemp()) / "store.db")
        self.store = Store(self.path)

    def tearDown(self):
        self.store.close()

    def test_upsert_and_read(self):
        self.store.upsert_candidate(
            {"token_address": TOKEN.upper(), "symbol": "CAT", "score": 71.5, "liquidity_usd": 1000}
        )
        row = self.store.get_candidate(TOKEN)
        self.assertIsNotNone(row)
        self.assertEqual(row["symbol"], "CAT")
        # L'indirizzo viene sempre normalizzato in minuscolo.
        self.assertEqual(row["token_address"], TOKEN)

    def test_first_seen_is_immutable(self):
        self.store.upsert_candidate({"token_address": TOKEN, "first_seen": 1000})
        self.store.upsert_candidate({"token_address": TOKEN, "first_seen": 9999, "score": 10})
        self.assertEqual(self.store.get_candidate(TOKEN)["first_seen"], 1000)

    def test_alert_snapshot_is_not_overwritten(self):
        self.store.upsert_candidate({"token_address": TOKEN, "symbol": "CAT"})
        self.store.mark_alerted(TOKEN, score=80, price=0.5, mcap=100_000)
        self.store.upsert_candidate({"token_address": TOKEN, "price_usd": 9.9})
        row = self.store.get_candidate(TOKEN)
        self.assertEqual(row["price_at_alert"], 0.5)
        self.assertEqual(row["status"], "alerted")

    def test_peak_multiple_tracking(self):
        self.store.upsert_candidate({"token_address": TOKEN})
        self.store.mark_alerted(TOKEN, score=80, price=1.0, mcap=1000)
        self.store.update_peak(TOKEN, 3.5)
        self.store.update_peak(TOKEN, 2.0)  # un ribasso non abbassa il picco
        row = self.store.get_candidate(TOKEN)
        self.assertAlmostEqual(row["peak_price"], 3.5)
        self.assertAlmostEqual(row["peak_multiple"], 3.5)

    def test_cooldown(self):
        self.store.upsert_candidate({"token_address": TOKEN})
        self.assertFalse(self.store.recently_alerted(TOKEN, 3600))
        self.store.mark_alerted(TOKEN, 80, 1.0, 1000)
        self.assertTrue(self.store.recently_alerted(TOKEN, 3600))

    def test_wallet_event_dedup(self):
        event = {
            "wallet": "0x" + "11" * 20, "token_address": TOKEN, "symbol": "CAT",
            "direction": "buy", "tx_hash": "0xdead", "block_number": 5,
        }
        self.assertTrue(self.store.record_wallet_event(event))
        # Lo stesso trasferimento letto due volte non deve generare due alert.
        self.assertFalse(self.store.record_wallet_event(event))

    def test_convergence_counts_distinct_wallets(self):
        for i in range(3):
            self.store.record_wallet_event(
                {
                    "wallet": f"0x{i:040x}", "token_address": TOKEN, "direction": "buy",
                    "tx_hash": f"0x{i:064x}",
                }
            )
        # Stesso wallet, seconda transazione: conta comunque uno solo.
        self.store.record_wallet_event(
            {"wallet": "0x" + "0" * 40, "token_address": TOKEN, "direction": "buy",
             "tx_hash": "0xaaaa"}
        )
        self.assertEqual(self.store.count_distinct_wallet_buyers(TOKEN), 3)

    def test_pending_backlog_is_returned_then_expires(self):
        import time

        self.store.upsert_candidate({"token_address": TOKEN, "status": "pending"})
        self.assertEqual(len(self.store.list_pending()), 1)
        # Nulla scade finche' e' dentro la finestra.
        self.assertEqual(self.store.expire_pending(max_age_seconds=3600), 0)
        # Forzando la finestra a zero il pool viene archiviato e sparisce dalla coda.
        time.sleep(1.1)
        self.assertEqual(self.store.expire_pending(max_age_seconds=1), 1)
        self.assertEqual(len(self.store.list_pending()), 0)
        self.assertEqual(self.store.get_candidate(TOKEN)["reject_reason"], "mai_partito")

    def test_stats_reports_hit_rate(self):
        for i, multiple in enumerate([1.0, 2.5, 6.0, 12.0]):
            address = f"0x{i:040x}"
            self.store.upsert_candidate({"token_address": address})
            self.store.mark_alerted(address, 80, 1.0, 1000)
            self.store.update_peak(address, multiple)
        stats = self.store.stats()
        self.assertEqual(stats["alerted"], 4)
        self.assertEqual(stats["hits_2x"], 3)
        self.assertEqual(stats["hits_5x"], 2)
        self.assertEqual(stats["hits_10x"], 1)


class TestModels(unittest.TestCase):
    def test_merge_fills_only_missing_fields(self):
        base = PairSnapshot(token_address=TOKEN, symbol="CAT", liquidity_usd=0)
        extra = PairSnapshot(token_address=TOKEN, symbol="ALTRO", liquidity_usd=5000, holders=300)
        merged = merge_snapshots(base, extra)
        self.assertEqual(merged.symbol, "CAT")       # gia' presente: non si tocca
        self.assertEqual(merged.liquidity_usd, 5000)  # mancante: si riempie
        self.assertEqual(merged.holders, 300)

    def test_addresses_are_lowercased(self):
        snapshot = PairSnapshot(token_address=TOKEN.upper(), pair_address=POOL.upper())
        self.assertEqual(snapshot.token_address, TOKEN)
        self.assertEqual(snapshot.pair_address, POOL)

    def test_has_market_data(self):
        # Un pool appena creato non e' ancora indicizzato da nessuno.
        self.assertFalse(PairSnapshot(token_address=TOKEN).has_market_data)
        self.assertTrue(PairSnapshot(token_address=TOKEN, liquidity_usd=1).has_market_data)
        self.assertTrue(PairSnapshot(token_address=TOKEN, price_usd=0.001).has_market_data)
        self.assertTrue(PairSnapshot(token_address=TOKEN, volume_1h=50).has_market_data)

    def test_ratios_handle_zero(self):
        snapshot = PairSnapshot(token_address=TOKEN)
        self.assertEqual(snapshot.buy_ratio_5m, 0.0)
        self.assertEqual(snapshot.vol_liq_ratio, 0.0)
        self.assertEqual(snapshot.age_seconds, 0)


class TestUtil(unittest.TestCase):
    def test_human_usd(self):
        self.assertEqual(human_usd(1500), "$1.5K")
        self.assertEqual(human_usd(2_400_000), "$2.4M")
        self.assertEqual(human_usd(0), "$0")

    def test_safe_float_survives_api_junk(self):
        self.assertEqual(safe_float(None), 0.0)
        self.assertEqual(safe_float({}), 0.0)
        self.assertEqual(safe_float("N/A"), 0.0)
        self.assertEqual(safe_float("1.5"), 1.5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
