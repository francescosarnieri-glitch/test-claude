"""Controlli anti-rug su un token.

Nessuno di questi controlli garantisce che un token sia sicuro: servono a
scartare in automatico le trappole evidenti, che sono la stragrande maggioranza.
La regola e' che un motivo di scarto "hard" (honeypot, mint aperto, liquidita'
sbloccata) fa cadere il candidato senza discussione, mentre i motivi "soft"
tolgono punteggio ma lasciano decidere allo score complessivo.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field

from . import tunables
from .chain import (
    BURN_ADDRESSES,
    SEL,
    ZERO_ADDRESS,
    bytecode_flags,
    get_rpc,
)
from .config import settings
from .models import PairSnapshot
from .util import get_logger, safe_float

log = get_logger("memescan.safety")

# Livelli: danger = scarto immediato, warn = penalita', info = solo contesto.
DANGER = "danger"
WARN = "warn"
INFO = "info"


@dataclass(slots=True)
class SafetyReport:
    token_address: str
    flags: list[dict] = field(default_factory=list)
    holders: int = 0
    top10_pct: float = 0.0
    deployer_pct: float = 0.0
    lp_burned_pct: float = 0.0
    owner: str = ""
    ownership_renounced: bool = False
    verified: bool = False
    dangerous_functions: list[str] = field(default_factory=list)
    checked: bool = False

    def add(self, level: str, code: str, message: str) -> None:
        self.flags.append({"level": level, "code": code, "message": message})

    @property
    def blocking(self) -> list[dict]:
        return [f for f in self.flags if f["level"] == DANGER]

    @property
    def warnings(self) -> list[dict]:
        return [f for f in self.flags if f["level"] == WARN]

    @property
    def passed(self) -> bool:
        return self.checked and not self.blocking

    @property
    def verdict(self) -> str:
        if not self.checked:
            return "sconosciuto"
        if self.blocking:
            return "pericoloso"
        if len(self.warnings) >= 2:
            return "rischioso"
        if self.warnings:
            return "accettabile"
        return "pulito"

    def penalty(self) -> float:
        """Punti da sottrarre allo score: 12 per warning, saturati a 30."""
        return min(30.0, 12.0 * len(self.warnings))

    def reason(self) -> str:
        if self.blocking:
            return "; ".join(f["message"] for f in self.blocking)
        return ""

    def to_dict(self) -> dict:
        return {
            "verdict": self.verdict,
            "flags": self.flags,
            "holders": self.holders,
            "top10_pct": round(self.top10_pct, 1),
            "deployer_pct": round(self.deployer_pct, 1),
            "lp_burned_pct": round(self.lp_burned_pct, 1),
            "ownership_renounced": self.ownership_renounced,
            "verified": self.verified,
            "dangerous_functions": self.dangerous_functions,
        }


class SafetyChecker:
    def __init__(self, blockscout) -> None:
        self.rpc = get_rpc()
        self.blockscout = blockscout
        self.filters = settings.filters

    async def check(self, snapshot: PairSnapshot) -> SafetyReport:
        report = SafetyReport(token_address=snapshot.token_address)
        token = snapshot.token_address

        try:
            code, owner, token_meta, address_info = await asyncio.gather(
                self.rpc.get_code(token),
                self.rpc.read_owner(token),
                self.rpc.erc20_metadata(token),
                self.blockscout.address_info(token),
            )
        except Exception as exc:  # pragma: no cover - dipende dalla rete
            log.warning("controlli di sicurezza falliti per %s: %s", token, exc)
            return report

        report.checked = True

        # 1. Il contratto esiste davvero.
        if not code or code == "0x":
            report.add(DANGER, "no_code", "Nessun bytecode all'indirizzo del token")
            return report

        # 2. Funzioni pericolose nel bytecode.
        report.dangerous_functions = bytecode_flags(code)

        # 3. Ownership.
        report.owner = owner
        report.ownership_renounced = (not owner) or owner in BURN_ADDRESSES or owner == ZERO_ADDRESS

        if "mint" in report.dangerous_functions and not report.ownership_renounced:
            report.add(
                DANGER, "mint_aperto",
                "Il proprietario puo' ancora coniare nuovi token e diluire tutti",
            )
        if "blacklist" in report.dangerous_functions and not report.ownership_renounced:
            report.add(
                DANGER, "blacklist",
                "Il contratto puo' inserire indirizzi in blacklist e impedirti di vendere",
            )
        if "fee_mutabile" in report.dangerous_functions and not report.ownership_renounced:
            report.add(
                WARN, "fee_mutabile",
                "Le tasse di acquisto/vendita sono modificabili dal proprietario",
            )
        if "pausable" in report.dangerous_functions and not report.ownership_renounced:
            report.add(WARN, "pausable", "Gli scambi possono essere messi in pausa")
        if "trading_switch" in report.dangerous_functions and not report.ownership_renounced:
            report.add(WARN, "trading_switch", "Gli scambi possono essere disattivati")
        if not report.ownership_renounced and not report.dangerous_functions:
            report.add(INFO, "owner_attivo", "Ownership non rinunciata (nessuna funzione critica)")

        # 4. Verifica del sorgente e segnalazioni dell'explorer.
        report.verified = bool(address_info.get("is_verified"))
        if not report.verified:
            report.add(WARN, "non_verificato", "Sorgente del contratto non verificato")
        if address_info.get("is_scam"):
            report.add(DANGER, "segnalato_scam", "L'explorer ha marcato questo contratto come scam")
        if address_info.get("proxy_type"):
            report.add(
                WARN, "proxy",
                "Contratto proxy: la logica puo' essere sostituita dopo il lancio",
            )

        deployer = (address_info.get("creator") or "").lower()

        # 5. Distribuzione: holder totali e concentrazione.
        await self._check_distribution(report, snapshot, token_meta, deployer)

        # 6. Liquidita' bloccata o bruciata.
        await self._check_liquidity_lock(report, snapshot)

        # 7. Segnale di honeypot dal comportamento del mercato.
        self._check_honeypot_signature(report, snapshot)

        return report

    async def _check_distribution(
        self, report: SafetyReport, snapshot: PairSnapshot, token_meta: dict, deployer: str
    ) -> None:
        info = await self.blockscout.token_info(snapshot.token_address)
        report.holders = info.get("holders") or snapshot.holders or 0

        if report.holders and report.holders < tunables.get("min_holders"):
            report.add(
                WARN, "pochi_holder",
                f"Solo {report.holders} holder (minimo impostato "
                f"{tunables.get('min_holders')})",
            )

        holders = await self.blockscout.holders(snapshot.token_address, limit=25)
        total_supply = safe_float(token_meta.get("total_supply_raw"))
        if not holders or total_supply <= 0:
            report.add(INFO, "distribuzione_ignota", "Distribuzione degli holder non disponibile")
            return

        # Il pool di liquidita', gli indirizzi di burn e gli altri contratti
        # (locker, bridge, staking) non sono "qualcuno che puo' venderti
        # addosso": vanno esclusi, altrimenti ogni token sano risulta concentrato
        # solo perche' la sua liquidita' e' dentro il pool.
        excluded = set(BURN_ADDRESSES)
        if snapshot.pair_address:
            excluded.add(snapshot.pair_address.lower())

        relevant = [
            h for h in holders
            if h["address"] not in excluded and not h.get("is_contract")
        ]
        top10 = sum(h["value"] for h in relevant[:10])
        report.top10_pct = (top10 / total_supply) * 100 if total_supply else 0

        if report.top10_pct > tunables.get("max_top10_holder_pct"):
            level = DANGER if report.top10_pct > 60 else WARN
            report.add(
                level, "concentrazione",
                f"I primi 10 wallet hanno il {report.top10_pct:.0f}% della supply",
            )

        if deployer:
            deployer_balance = next(
                (h["value"] for h in holders if h["address"] == deployer), 0.0
            )
            report.deployer_pct = (deployer_balance / total_supply) * 100 if total_supply else 0
            if report.deployer_pct > self.filters.max_deployer_pct:
                level = DANGER if report.deployer_pct > 15 else WARN
                report.add(
                    level, "deployer_carico",
                    f"Il deployer detiene ancora il {report.deployer_pct:.0f}% della supply",
                )

    async def _check_liquidity_lock(self, report: SafetyReport, snapshot: PairSnapshot) -> None:
        """Verifica quanta parte dei token LP e' stata bruciata.

        Vale per i pool in stile Uniswap V2, dove la posizione di liquidita' e'
        un ERC-20: se i token LP sono all'indirizzo di burn, quella liquidita'
        non puo' piu' essere ritirata. Sui pool V3 la posizione e' un NFT e
        questo controllo non si applica: in quel caso si resta sul dato di
        liquidita' assoluta e sugli altri segnali.
        """
        if not snapshot.pair_address:
            return

        # Su Uniswap V4 il "pair" e' un id a 32 byte dentro un unico contratto
        # PoolManager, non un indirizzo: non esiste un LP token da interrogare.
        if len(snapshot.pair_address) != 42:
            report.add(
                INFO, "pool_v4",
                "Pool in stile V4: il blocco della liquidita' non e' verificabile dal LP token",
            )
            return

        results = await self.rpc.batch(
            [
                ("eth_call", [{"to": snapshot.pair_address, "data": SEL["totalSupply"]}, "latest"]),
                ("eth_call", [{"to": snapshot.pair_address, "data": SEL["liquidity"]}, "latest"]),
            ]
        )
        lp_total_raw = results[0]
        is_v3_style = results[1] is not None and results[1] != "0x"

        if not lp_total_raw or lp_total_raw == "0x":
            if is_v3_style:
                report.add(
                    INFO, "pool_v3",
                    "Pool in stile V3: il blocco della liquidita' non e' verificabile dal LP token",
                )
            return

        lp_total = int(lp_total_raw, 16)
        if lp_total <= 0:
            return

        burned = 0
        for burn_address in (ZERO_ADDRESS, "0x000000000000000000000000000000000000dead"):
            burned += await self.rpc.balance_of(snapshot.pair_address, burn_address)

        report.lp_burned_pct = (burned / lp_total) * 100

        if report.lp_burned_pct < 50:
            level = DANGER if report.lp_burned_pct < 5 else WARN
            report.add(
                level, "lp_sbloccata",
                f"Solo il {report.lp_burned_pct:.0f}% dei token LP e' bruciato: "
                "chi la detiene puo' ritirare la liquidita'",
            )

    def _check_honeypot_signature(self, report: SafetyReport, snapshot: PairSnapshot) -> None:
        """Riconosce l'impronta di un honeypot dai dati di mercato.

        Un honeypot lascia comprare ma non vendere: il risultato e' una lunga
        serie di acquisti con zero o quasi zero vendite. E' un controllo che non
        costa nulla e prende molte trappole che l'analisi del bytecode manca,
        perche' il blocco puo' essere nascosto in un proxy o in un hook.
        """
        buys, sells = snapshot.buys_5m, snapshot.sells_5m
        if buys >= 25 and sells == 0:
            report.add(
                DANGER, "honeypot_probabile",
                f"{buys} acquisti e nessuna vendita: quasi certamente non si puo' uscire",
            )
        elif buys >= 40 and sells <= 2:
            report.add(
                WARN, "vendite_quasi_assenti",
                f"{buys} acquisti contro {sells} vendite: possibile tassa di vendita proibitiva",
            )

        if snapshot.liquidity_usd > 0 and snapshot.market_cap > 0:
            ratio = snapshot.market_cap / snapshot.liquidity_usd
            if ratio > 150:
                report.add(
                    WARN, "liquidita_sottile",
                    f"Capitalizzazione {ratio:.0f}x la liquidita': basta poco per farlo crollare",
                )

        if snapshot.vol_liq_ratio > self.filters.max_vol_liq_ratio:
            report.add(
                WARN, "possibile_wash_trading",
                f"Volume pari a {snapshot.vol_liq_ratio:.0f}x la liquidita' in un'ora",
            )
