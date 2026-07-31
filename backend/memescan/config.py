"""Configurazione centralizzata, letta da variabili d'ambiente / file .env."""

from __future__ import annotations

import os
from dataclasses import dataclass, field, fields
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent

# Il .env accanto al package ha la precedenza, ma non sovrascrive variabili
# gia' presenti nell'ambiente (utile per docker-compose e CI).
load_dotenv(BASE_DIR / ".env", override=False)


def _str(key: str, default: str = "") -> str:
    return os.environ.get(key, default).strip()


def _int(key: str, default: int) -> int:
    raw = _str(key)
    try:
        return int(raw) if raw else default
    except ValueError:
        return default


def _float(key: str, default: float) -> float:
    raw = _str(key)
    try:
        return float(raw) if raw else default
    except ValueError:
        return default


def _csv(key: str) -> list[str]:
    raw = _str(key)
    return [item.strip() for item in raw.split(",") if item.strip()]


@dataclass(slots=True)
class Filters:
    """Soglie di prefiltro. Un pair deve superarle tutte per essere valutato."""

    min_liquidity_usd: float = field(default_factory=lambda: _float("MIN_LIQUIDITY_USD", 8_000))
    max_liquidity_usd: float = field(default_factory=lambda: _float("MAX_LIQUIDITY_USD", 2_000_000))
    min_volume_5m_usd: float = field(default_factory=lambda: _float("MIN_VOLUME_5M_USD", 3_000))
    min_volume_1h_usd: float = field(default_factory=lambda: _float("MIN_VOLUME_1H_USD", 15_000))
    min_txns_5m: int = field(default_factory=lambda: _int("MIN_TXNS_5M", 25))
    min_holders: int = field(default_factory=lambda: _int("MIN_HOLDERS", 60))
    min_age_minutes: int = field(default_factory=lambda: _int("MIN_AGE_MINUTES", 20))
    max_age_hours: int = field(default_factory=lambda: _int("MAX_AGE_HOURS", 72))
    max_vol_liq_ratio: float = field(default_factory=lambda: _float("MAX_VOL_LIQ_RATIO", 40))
    max_top10_holder_pct: float = field(default_factory=lambda: _float("MAX_TOP10_HOLDER_PCT", 35))
    max_deployer_pct: float = field(default_factory=lambda: _float("MAX_DEPLOYER_PCT", 5))


@dataclass(slots=True)
class Settings:
    # Telegram
    telegram_bot_token: str = field(default_factory=lambda: _str("TELEGRAM_BOT_TOKEN"))
    telegram_chat_id: str = field(default_factory=lambda: _str("TELEGRAM_CHAT_ID"))

    # Chain
    rpc_url: str = field(
        default_factory=lambda: _str("RPC_URL", "https://rpc.mainnet.chain.robinhood.com")
    )
    rpc_url_fallback: str = field(default_factory=lambda: _str("RPC_URL_FALLBACK"))
    chain_id: int = field(default_factory=lambda: _int("CHAIN_ID", 4663))
    blockscout_url: str = field(
        default_factory=lambda: _str("BLOCKSCOUT_URL", "https://robinhoodchain.blockscout.com")
    )
    blockscout_api_key: str = field(default_factory=lambda: _str("BLOCKSCOUT_API_KEY"))

    # Sorgenti dati
    dexscreener_chain: str = field(default_factory=lambda: _str("DEXSCREENER_CHAIN", "robinhood"))
    geckoterminal_network: str = field(default_factory=lambda: _str("GECKOTERMINAL_NETWORK"))

    # Alert
    alert_min_score: float = field(default_factory=lambda: _float("ALERT_MIN_SCORE", 70))
    alert_cooldown_minutes: int = field(default_factory=lambda: _int("ALERT_COOLDOWN_MINUTES", 180))
    wallet_convergence_threshold: int = field(
        default_factory=lambda: _int("WALLET_CONVERGENCE_THRESHOLD", 2)
    )

    # Wallet tracker
    tracked_wallets: list[str] = field(default_factory=lambda: _csv("TRACKED_WALLETS"))
    wallet_poll_seconds: int = field(default_factory=lambda: _int("WALLET_POLL_SECONDS", 45))

    # Loop
    scan_interval_seconds: int = field(default_factory=lambda: _int("SCAN_INTERVAL_SECONDS", 20))
    enrich_interval_seconds: int = field(default_factory=lambda: _int("ENRICH_INTERVAL_SECONDS", 60))
    # A ~0,1s per blocco, 20.000 blocchi sono circa mezz'ora di catena.
    onchain_backfill_blocks: int = field(
        default_factory=lambda: _int("ONCHAIN_BACKFILL_BLOCKS", 20_000)
    )

    # Social (opzionale)
    x_bearer_token: str = field(default_factory=lambda: _str("X_BEARER_TOKEN"))
    lunarcrush_api_key: str = field(default_factory=lambda: _str("LUNARCRUSH_API_KEY"))

    # Server
    host: str = field(default_factory=lambda: _str("HOST", "0.0.0.0"))
    port: int = field(default_factory=lambda: _int("PORT", 8080))
    api_token: str = field(default_factory=lambda: _str("API_TOKEN"))
    db_path: str = field(default_factory=lambda: _str("DB_PATH", "data/memescan.db"))
    # Backup del database su un repository GitHub privato, diverso da quello
    # del codice: la macchina si aggiorna tirando giu' il codice, e un token
    # che scrivesse anche li' le farebbe eseguire qualunque cosa.
    backup_repo: str = field(default_factory=lambda: _str("BACKUP_REPO"))
    backup_token: str = field(default_factory=lambda: _str("BACKUP_TOKEN"))
    backup_ore: int = field(default_factory=lambda: _int("BACKUP_ORE", 12))
    log_level: str = field(default_factory=lambda: _str("LOG_LEVEL", "INFO"))

    filters: Filters = field(default_factory=Filters)

    def __post_init__(self) -> None:
        self.tracked_wallets = [w.lower() for w in self.tracked_wallets]
        db = Path(self.db_path)
        if not db.is_absolute():
            db = BASE_DIR / db
        self.db_path = str(db)
        db.parent.mkdir(parents=True, exist_ok=True)

    @property
    def telegram_enabled(self) -> bool:
        return bool(self.telegram_bot_token and self.telegram_chat_id)

    @property
    def rpc_urls(self) -> list[str]:
        return [u for u in (self.rpc_url, self.rpc_url_fallback) if u]

    def missing_required(self) -> list[str]:
        """Elenco leggibile di cio' che manca per un funzionamento completo."""
        missing = []
        if not self.telegram_bot_token:
            missing.append("TELEGRAM_BOT_TOKEN")
        if not self.telegram_chat_id:
            missing.append("TELEGRAM_CHAT_ID")
        if not self.rpc_url:
            missing.append("RPC_URL")
        return missing

    def as_dict(self) -> dict:
        """Versione serializzabile, con i segreti oscurati."""
        secret = {"telegram_bot_token", "x_bearer_token", "lunarcrush_api_key", "api_token", "backup_token",
                  "blockscout_api_key", "telegram_chat_id"}
        out: dict = {}
        for f in fields(self):
            value = getattr(self, f.name)
            if f.name == "filters":
                out[f.name] = {ff.name: getattr(value, ff.name) for ff in fields(value)}
            elif f.name in secret:
                out[f.name] = "***" if value else ""
            elif f.name == "rpc_url" or f.name == "rpc_url_fallback":
                # Nasconde la api key nel path dell'endpoint
                out[f.name] = _mask_url(value)
            else:
                out[f.name] = value
        return out


def _mask_url(url: str) -> str:
    if not url:
        return ""
    parts = url.rstrip("/").split("/")
    if len(parts) > 3 and len(parts[-1]) > 12:
        parts[-1] = "***"
        return "/".join(parts)
    return url


settings = Settings()
