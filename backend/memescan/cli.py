"""Interfaccia da riga di comando.

    python -m memescan.cli doctor              verifica la configurazione
    python -m memescan.cli serve               avvia backend + dashboard
    python -m memescan.cli scan-once           una singola scansione, poi esce
    python -m memescan.cli check 0xTOKEN       analizza un token specifico
    python -m memescan.cli discover-wallets    trova wallet da tracciare
    python -m memescan.cli add-wallet 0xADDR   aggiunge un wallet a mano
    python -m memescan.cli test-telegram       manda un messaggio di prova
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys

from .chain import probe_chain
from .config import settings
from .notify import Notifier
from .store import get_store
from .util import get_logger, human_usd, setup_logging
from .worker import Engine

log = get_logger("memescan.cli")


async def cmd_doctor() -> int:
    """Controlla, uno per uno, che ogni pezzo risponda."""
    print("\n=== memescan · diagnostica ===\n")
    problems = 0

    missing = settings.missing_required()
    if missing:
        print(f"  ⚠️  variabili mancanti nel .env: {', '.join(missing)}")
        problems += 1
    else:
        print("  ✅ configurazione di base completa")

    chain = await probe_chain()
    if chain["ok"]:
        print(f"  ✅ RPC ok · chain id {chain['chain_id']} · blocco {chain['block_number']:,}")
    else:
        print(f"  ❌ RPC non raggiungibile o chain sbagliata (letto: {chain['chain_id']})")
        problems += 1

    engine = Engine()
    try:
        pairs = await engine.dexscreener.search(f"WETH {settings.dexscreener_chain}")
        if pairs:
            best = max(pairs, key=lambda p: p.liquidity_usd)
            print(f"  ✅ Dexscreener ok · {len(pairs)} pair, il piu' liquido "
                  f"${best.symbol} {human_usd(best.liquidity_usd)}")
        else:
            print("  ⚠️  Dexscreener non ha restituito pair per questa chain "
                  f"(slug configurato: '{settings.dexscreener_chain}')")
            problems += 1

        network = await engine.geckoterminal.ensure_network()
        if network:
            print(f"  ✅ GeckoTerminal ok · rete '{network}'")
        else:
            print("  ⚠️  rete non trovata su GeckoTerminal (sorgente secondaria disattivata)")

        health = await engine.blockscout.ping()
        if health:
            print(f"  ✅ Blockscout ok · blocco medio "
                  f"{health['average_block_time_ms'] / 1000:.2f}s")
        else:
            print("  ❌ Blockscout non raggiungibile: holder e distribuzione non verificabili")
            problems += 1

        wallets = get_store().list_tracked_wallets()
        print(f"  {'✅' if wallets else '⚠️ '} whales tracciate: {len(wallets)}")

        if settings.telegram_enabled:
            sent = await engine.notifier.send("🧪 <b>memescan</b> · test di diagnostica riuscito")
            print(f"  {'✅' if sent else '❌'} Telegram "
                  f"{'raggiungibile' if sent else 'non raggiungibile (token o chat id errati)'}")
            if not sent:
                problems += 1
        else:
            print("  ⚠️  Telegram non configurato: nessun alert sul telefono")
            problems += 1
    finally:
        await engine.stop()

    print(f"\n{'Tutto a posto.' if not problems else f'{problems} punti da sistemare.'}\n")
    return 0 if problems == 0 else 1


async def cmd_scan_once() -> int:
    engine = Engine()
    try:
        await engine.onchain.bootstrap(engine.dexscreener)
        await engine.scan_once()
        rows = get_store().list_candidates(limit=20)
        print(f"\n{len(rows)} candidati in classifica:\n")
        for row in rows:
            print(
                f"  {row['score']:5.1f}  ${row['symbol'] or '???':<12} "
                f"liq {human_usd(row['liquidity_usd']):>8}  "
                f"mcap {human_usd(row['market_cap']):>8}  {row['status']}"
            )
    finally:
        await engine.stop()
    return 0


async def cmd_check(token: str) -> int:
    engine = Engine()
    try:
        result = await engine.rescan_token(token.lower())
        if "error" in result:
            print(f"  ❌ {result['error']}")
            return 1
        for key in ("safety_json", "breakdown_json"):
            if isinstance(result.get(key), str):
                result[key] = json.loads(result[key] or "{}")
        print(json.dumps(result, indent=2, ensure_ascii=False))
    finally:
        await engine.stop()
    return 0


async def cmd_discover_wallets(days: int, top: int, min_winners: int) -> int:
    engine = Engine()
    try:
        results = await engine.wallets.discover_top_traders(
            engine.dexscreener, engine.geckoterminal, min_winners=min_winners, top=top
        )
        if not results:
            print("\n  Nessun wallet trovato. Serve uno storico di token vincenti:")
            print("  lascia girare `serve` per qualche giorno e riprova.\n")
            return 1
        print(f"\n{len(results)} wallet trovati e aggiunti al tracking:\n")
        for entry in results:
            print(f"  {entry['address']}  · early su {entry['winners']} token vincenti")
        print()
    finally:
        await engine.stop()
    return 0


async def cmd_add_wallet(address: str, label: str) -> int:
    address = address.strip().lower()
    if not address.startswith("0x") or len(address) != 42:
        print("  ❌ indirizzo EVM non valido")
        return 1
    get_store().add_tracked_wallet(address, label=label)
    print(f"  ✅ aggiunto {address}")
    return 0


async def cmd_test_telegram() -> int:
    notifier = Notifier()
    try:
        if not notifier.enabled:
            print("  ❌ TELEGRAM_BOT_TOKEN o TELEGRAM_CHAT_ID mancanti nel .env")
            return 1
        ok = await notifier.send(
            "✅ <b>memescan</b>\nIl bot e' collegato correttamente.\n"
            "Gli alert arriveranno qui."
        )
        print("  ✅ messaggio inviato" if ok else "  ❌ invio fallito: controlla token e chat id")
        return 0 if ok else 1
    finally:
        await notifier.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="memescan", description="Scanner di meme coin con controlli anti-rug."
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("doctor", help="verifica configurazione e connettivita'")
    sub.add_parser("serve", help="avvia backend, dashboard e alert")
    sub.add_parser("scan-once", help="esegue una sola scansione e stampa i risultati")
    sub.add_parser("test-telegram", help="invia un messaggio di prova")

    p_check = sub.add_parser("check", help="analizza un singolo token")
    p_check.add_argument("token", help="indirizzo del contratto")

    p_discover = sub.add_parser("discover-wallets", help="trova wallet profittevoli da seguire")
    p_discover.add_argument("--days", type=int, default=7, help="finestra storica (giorni)")
    p_discover.add_argument("--top", type=int, default=30, help="quanti wallet tenere")
    p_discover.add_argument(
        "--min-winners", type=int, default=2,
        help="in quanti token vincenti deve comparire un wallet",
    )

    p_wallet = sub.add_parser("add-wallet", help="aggiunge un wallet al tracking")
    p_wallet.add_argument("address")
    p_wallet.add_argument("--label", default="aggiunto a mano")

    args = parser.parse_args(argv)
    setup_logging()

    if args.command == "serve":
        from .api import run

        run()
        return 0

    handlers = {
        "doctor": lambda: cmd_doctor(),
        "scan-once": lambda: cmd_scan_once(),
        "check": lambda: cmd_check(args.token),
        "discover-wallets": lambda: cmd_discover_wallets(
            args.days, args.top, args.min_winners
        ),
        "add-wallet": lambda: cmd_add_wallet(args.address, args.label),
        "test-telegram": lambda: cmd_test_telegram(),
    }
    return asyncio.run(handlers[args.command]())


if __name__ == "__main__":
    sys.exit(main())
