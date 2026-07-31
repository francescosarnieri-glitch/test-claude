"""Prefiltro e punteggio dei candidati.

Il punteggio non prevede il prezzo: mette in fila i candidati secondo quanto
somigliano a un lancio sano che sta prendendo trazione, invece che a una
trappola o a un token morto. La colonna che pesa di piu' e' il segnale dei
wallet tracciati, perche' e' l'unica che riflette il comportamento di chi ha
gia' dimostrato di saper scegliere.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from . import tunables
from .config import settings
from .models import PairSnapshot
from .safety import SafetyReport

# Peso massimo di ogni componente. La somma fa 100.
WEIGHTS = {
    "momentum": 28.0,
    "liquidita": 18.0,
    "distribuzione": 17.0,
    "sicurezza": 12.0,
    "wallet": 25.0,
}


@dataclass(slots=True)
class Score:
    total: float = 0.0
    # Il punteggio senza i punti delle balene: quanto vale il token per come e'
    # fatto, a prescindere da chi lo ha comprato. Serve a rispondere alla
    # domanda "lo scanner me lo avrebbe consigliato lo stesso?", che il totale
    # da solo non permette di distinguere.
    own: float = 0.0
    components: dict[str, float] = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "total": round(self.total, 1),
            "own": round(self.own, 1),
            "components": {k: round(v, 1) for k, v in self.components.items()},
            "notes": self.notes,
        }


def _clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def passes_prefilter(snapshot: PairSnapshot) -> tuple[bool, str]:
    """Filtro grossolano prima dei controlli costosi.

    Gira su ogni pool nuovo, quindi deve usare solo dati gia' in memoria: serve
    a non sprecare chiamate RPC su token che non hanno alcuna possibilita'.
    """
    age = snapshot.age_seconds

    if age and age < tunables.get("min_age_minutes") * 60:
        # Troppo presto: nei primissimi minuti i dati sono ancora inaffidabili,
        # c'e' solo l'attivita' dei bot sniper, ed e' la finestra in cui i cloni
        # provano ad agganciarsi a un nome che sta gia' andando.
        return False, "troppo_giovane"
    if age and age > tunables.get("max_age_hours") * 3600:
        return False, "troppo_vecchio"
    if snapshot.liquidity_usd and snapshot.liquidity_usd < tunables.get("min_liquidity_usd"):
        return False, "liquidita_insufficiente"
    if snapshot.liquidity_usd > settings.filters.max_liquidity_usd:
        return False, "gia_troppo_grande"
    if snapshot.volume_1h and snapshot.volume_1h < tunables.get("min_volume_1h_usd"):
        return False, "volume_basso"
    if snapshot.txns_5m and snapshot.txns_5m < settings.filters.min_txns_5m:
        return False, "poche_transazioni"

    # Chi e' gia' salito troppo non e' un'occasione: e' il massimo di qualcun
    # altro. Restava solo una penalita' sul punteggio, che non fermava niente.
    max_pump = tunables.get("max_price_change_1h")
    if max_pump and snapshot.price_change_1h > max_pump:
        return False, "gia_esploso"

    return True, ""


def _momentum_score(snapshot: PairSnapshot) -> tuple[float, list[str]]:
    """Sta accelerando adesso, o e' gia' passato?"""
    notes: list[str] = []
    parts: list[float] = []

    # Accelerazione: volume degli ultimi 5 minuti proiettato su un'ora, contro
    # il volume dell'ora appena passata. Sopra 1 vuol dire che sta scaldando.
    if snapshot.volume_1h > 0:
        acceleration = (snapshot.volume_5m * 12) / snapshot.volume_1h
        parts.append(_clamp(acceleration / 2.5))
        if acceleration > 2:
            notes.append(f"volume in accelerazione ({acceleration:.1f}x sull'ora)")
    else:
        parts.append(0.3 if snapshot.volume_5m > 0 else 0.0)

    # Pressione in acquisto sulle ultime transazioni.
    ratio = snapshot.buy_ratio_5m
    if snapshot.txns_5m >= 10:
        parts.append(_clamp((ratio - 0.45) / 0.30))
        if ratio > 0.65:
            notes.append(f"{ratio * 100:.0f}% delle transazioni sono acquisti")
    else:
        parts.append(0.2)

    # Prezzo in salita ma non gia' esploso: oltre il +300% in un'ora si compra
    # il massimo di qualcun altro.
    change_1h = snapshot.price_change_1h
    if change_1h <= 0:
        parts.append(0.15)
    elif change_1h <= 150:
        parts.append(_clamp(change_1h / 150))
    else:
        parts.append(_clamp(1.0 - (change_1h - 150) / 400, 0.1, 1.0))
        notes.append(f"gia' +{change_1h:.0f}% in un'ora: rischio di entrare tardi")

    return sum(parts) / len(parts), notes


def _liquidity_score(snapshot: PairSnapshot) -> tuple[float, list[str]]:
    """Liquidita' sufficiente per entrare e uscire, non tanta da essere gia' finita."""
    notes: list[str] = []
    liquidity = snapshot.liquidity_usd
    if liquidity <= 0:
        return 0.0, notes

    # Zona ideale: 25k-250k. Sotto si scivola sullo slippage, sopra il grosso
    # del movimento e' probabilmente gia' avvenuto.
    if liquidity < 25_000:
        base = _clamp(liquidity / 25_000) * 0.7
    elif liquidity <= 250_000:
        base = 1.0
    else:
        base = _clamp(1.0 - (liquidity - 250_000) / 1_500_000, 0.25, 1.0)

    ratio = snapshot.vol_liq_ratio
    if ratio > settings.filters.max_vol_liq_ratio:
        base *= 0.5
        notes.append("rapporto volume/liquidita' anomalo")
    elif 1.0 <= ratio <= 12.0:
        base = min(1.0, base * 1.15)

    return base, notes


def _distribution_score(safety: SafetyReport) -> tuple[float, list[str]]:
    notes: list[str] = []
    parts: list[float] = []

    if safety.holders:
        # 500 holder e' gia' una base solida per un token giovane.
        parts.append(_clamp(safety.holders / 500))
        if safety.holders > 800:
            notes.append(f"{safety.holders} holder")
    if safety.top10_pct:
        parts.append(_clamp(1.0 - safety.top10_pct / 50))
    if safety.lp_burned_pct:
        parts.append(_clamp(safety.lp_burned_pct / 100))
        if safety.lp_burned_pct >= 95:
            notes.append("liquidita' bruciata")

    if not parts:
        return 0.35, notes  # dato mancante: ne' premio ne' castigo
    return sum(parts) / len(parts), notes


def _safety_score(safety: SafetyReport) -> tuple[float, list[str]]:
    notes: list[str] = []
    value = 0.0
    if safety.ownership_renounced:
        value += 0.45
        notes.append("ownership rinunciata")
    if safety.verified:
        value += 0.3
        notes.append("contratto verificato")
    if not safety.dangerous_functions:
        value += 0.25
    return _clamp(value), notes


def _wallet_score(wallet_hits: int) -> tuple[float, list[str]]:
    """Quanti wallet tracciati diversi hanno comprato.

    Curva volutamente ripida: il primo wallet conta molto, e a tre wallet
    distinti la componente e' gia' al massimo. La convergenza di piu' operatori
    indipendenti e' il segnale piu' affidabile che abbiamo.
    """
    if wallet_hits <= 0:
        return 0.0, []
    mapping = {1: 0.55, 2: 0.85}
    value = mapping.get(wallet_hits, 1.0)
    plural = "wallet tracciati" if wallet_hits > 1 else "wallet tracciato"
    return value, [f"{wallet_hits} {plural} in acquisto"]


def compute_score(
    snapshot: PairSnapshot, safety: SafetyReport, wallet_hits: int = 0
) -> Score:
    score = Score()
    notes: list[str] = []

    for key, (value, component_notes) in {
        "momentum": _momentum_score(snapshot),
        "liquidita": _liquidity_score(snapshot),
        "distribuzione": _distribution_score(safety),
        "sicurezza": _safety_score(safety),
        "wallet": _wallet_score(wallet_hits),
    }.items():
        score.components[key] = value * WEIGHTS[key]
        notes.extend(component_notes)

    total = sum(score.components.values())

    penalty = safety.penalty()
    if penalty:
        score.components["penalita_sicurezza"] = -penalty
        total -= penalty
        notes.extend(f["message"] for f in safety.warnings)

    score.total = max(0.0, min(100.0, total))
    score.own = max(0.0, min(100.0, total - score.components.get("wallet", 0.0)))
    score.notes = notes
    return score
