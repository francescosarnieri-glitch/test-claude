"""Modello normalizzato di un pair, comune a tutte le sorgenti."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field

from .util import now, safe_float, safe_int


@dataclass(slots=True)
class PairSnapshot:
    """Fotografia di un pool di liquidita' in un dato istante.

    Dexscreener, GeckoTerminal e gli eventi on-chain hanno formati diversi:
    vengono tutti convertiti qui, cosi' scoring e filtri lavorano su un solo
    formato e aggiungere una sorgente nuova non tocca il resto del codice.
    """

    token_address: str
    pair_address: str = ""
    symbol: str = ""
    name: str = ""
    dex: str = ""
    quote_symbol: str = ""
    quote_address: str = ""
    source: str = ""

    pair_created_at: int = 0  # unix secondi
    price_usd: float = 0.0
    liquidity_usd: float = 0.0
    fdv: float = 0.0
    market_cap: float = 0.0

    volume_5m: float = 0.0
    volume_1h: float = 0.0
    volume_24h: float = 0.0
    price_change_5m: float = 0.0
    price_change_1h: float = 0.0
    price_change_24h: float = 0.0
    buys_5m: int = 0
    sells_5m: int = 0
    buys_1h: int = 0
    sells_1h: int = 0

    holders: int = 0
    image_url: str = ""
    socials: dict = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.token_address = (self.token_address or "").lower()
        self.pair_address = (self.pair_address or "").lower()
        self.quote_address = (self.quote_address or "").lower()

    @property
    def age_seconds(self) -> int:
        if not self.pair_created_at:
            return 0
        return max(0, now() - self.pair_created_at)

    @property
    def txns_5m(self) -> int:
        return self.buys_5m + self.sells_5m

    @property
    def buy_ratio_5m(self) -> float:
        """Quota di acquisti sul totale delle transazioni a 5 minuti."""
        total = self.txns_5m
        return self.buys_5m / total if total else 0.0

    @property
    def has_market_data(self) -> bool:
        """Se nessun aggregatore lo conosce ancora, non c'e' niente da valutare."""
        return self.liquidity_usd > 0 or self.volume_1h > 0 or self.price_usd > 0

    @property
    def vol_liq_ratio(self) -> float:
        """Volume 1h diviso liquidita': sopra ~40 di solito e' wash trading."""
        return self.volume_1h / self.liquidity_usd if self.liquidity_usd > 0 else 0.0

    def to_row(self) -> dict:
        """Campi che finiscono nella tabella `candidates`."""
        return {
            "token_address": self.token_address,
            "pair_address": self.pair_address,
            "symbol": self.symbol,
            "name": self.name,
            "dex": self.dex,
            "pair_created_at": self.pair_created_at,
            "source": self.source,
            "price_usd": self.price_usd,
            "liquidity_usd": self.liquidity_usd,
            "fdv": self.fdv,
            "market_cap": self.market_cap,
            "volume_5m": self.volume_5m,
            "volume_1h": self.volume_1h,
            "volume_24h": self.volume_24h,
            "price_change_5m": self.price_change_5m,
            "price_change_1h": self.price_change_1h,
            "price_change_24h": self.price_change_24h,
            "buys_5m": self.buys_5m,
            "sells_5m": self.sells_5m,
            "holders": self.holders,
        }

    def to_dict(self) -> dict:
        return asdict(self)


def merge_snapshots(base: PairSnapshot, extra: PairSnapshot) -> PairSnapshot:
    """Fonde due snapshot dello stesso token tenendo il dato piu' informativo.

    Le sorgenti si completano a vicenda: gli eventi on-chain arrivano per primi
    ma senza prezzi, Dexscreener ha i prezzi ma indicizza con qualche minuto di
    ritardo. Per ogni campo vince il valore non nullo.
    """
    for key in base.__slots__:
        current = getattr(base, key)
        incoming = getattr(extra, key)
        if not incoming:
            continue
        if not current or (isinstance(current, (int, float)) and current == 0):
            setattr(base, key, incoming)
    return base


def snapshot_from_row(row: dict) -> PairSnapshot:
    return PairSnapshot(
        token_address=row.get("token_address", ""),
        pair_address=row.get("pair_address", "") or "",
        symbol=row.get("symbol", "") or "",
        name=row.get("name", "") or "",
        dex=row.get("dex", "") or "",
        source=row.get("source", "") or "",
        pair_created_at=safe_int(row.get("pair_created_at")),
        price_usd=safe_float(row.get("price_usd")),
        liquidity_usd=safe_float(row.get("liquidity_usd")),
        fdv=safe_float(row.get("fdv")),
        market_cap=safe_float(row.get("market_cap")),
        volume_5m=safe_float(row.get("volume_5m")),
        volume_1h=safe_float(row.get("volume_1h")),
        volume_24h=safe_float(row.get("volume_24h")),
        price_change_5m=safe_float(row.get("price_change_5m")),
        price_change_1h=safe_float(row.get("price_change_1h")),
        price_change_24h=safe_float(row.get("price_change_24h")),
        buys_5m=safe_int(row.get("buys_5m")),
        sells_5m=safe_int(row.get("sells_5m")),
        holders=safe_int(row.get("holders")),
    )
