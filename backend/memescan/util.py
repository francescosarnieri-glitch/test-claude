"""Utility condivise: logging, rate limiting e client HTTP con retry."""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

import httpx

from .config import settings

_LOG_FORMAT = "%(asctime)s %(levelname)-7s %(name)-22s %(message)s"


def setup_logging() -> None:
    logging.basicConfig(
        level=getattr(logging, settings.log_level.upper(), logging.INFO),
        format=_LOG_FORMAT,
        datefmt="%H:%M:%S",
    )
    # httpx logga ogni singola request a INFO: troppo rumoroso per un loop di polling.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


log = get_logger("memescan.util")


def now() -> int:
    return int(time.time())


class RateLimiter:
    """Token bucket semplice: al massimo `rate` chiamate ogni `per` secondi.

    Serve a restare dentro i limiti delle API pubbliche (Dexscreener 300/min,
    GeckoTerminal 30/min) senza farsi bannare l'IP.
    """

    def __init__(self, rate: int, per: float = 60.0) -> None:
        self.rate = rate
        self.per = per
        self._allowance = float(rate)
        self._last = time.monotonic()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        async with self._lock:
            while True:
                current = time.monotonic()
                elapsed = current - self._last
                self._last = current
                self._allowance = min(self.rate, self._allowance + elapsed * (self.rate / self.per))
                if self._allowance >= 1.0:
                    self._allowance -= 1.0
                    return
                # Attende il tempo esatto che manca per maturare un token.
                await asyncio.sleep((1.0 - self._allowance) * (self.per / self.rate))


class HttpClient:
    """Wrapper httpx con retry a backoff esponenziale e rate limiter opzionale."""

    def __init__(
        self,
        base_url: str = "",
        rate_limit: RateLimiter | None = None,
        timeout: float = 15.0,
        headers: dict[str, str] | None = None,
    ) -> None:
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            headers={"User-Agent": "memescan/1.0", **(headers or {})},
            follow_redirects=True,
        )
        self._limiter = rate_limit

    async def close(self) -> None:
        await self._client.aclose()

    async def request(
        self,
        method: str,
        url: str,
        *,
        retries: int = 3,
        expect_json: bool = True,
        **kwargs: Any,
    ) -> Any:
        delay = 1.0
        last_error: Exception | None = None
        for attempt in range(retries + 1):
            if self._limiter:
                await self._limiter.acquire()
            try:
                resp = await self._client.request(method, url, **kwargs)
                if resp.status_code == 429:
                    # Rispetta Retry-After se presente, altrimenti backoff.
                    wait = float(resp.headers.get("Retry-After", delay))
                    log.warning("429 da %s, attendo %.1fs", url, wait)
                    await asyncio.sleep(min(wait, 30.0))
                    delay *= 2
                    continue
                if resp.status_code >= 500:
                    raise httpx.HTTPStatusError(
                        f"server error {resp.status_code}", request=resp.request, response=resp
                    )
                if resp.status_code >= 400:
                    # 4xx diverso da 429 non si risolve riprovando.
                    log.debug("%s %s -> %s", method, url, resp.status_code)
                    return None
                return resp.json() if expect_json else resp.text
            except (httpx.HTTPError, ValueError) as exc:
                last_error = exc
                if attempt >= retries:
                    break
                await asyncio.sleep(delay)
                delay *= 2
        log.debug("richiesta fallita %s %s: %s", method, url, last_error)
        return None

    async def get(self, url: str, **kwargs: Any) -> Any:
        return await self.request("GET", url, **kwargs)

    async def post(self, url: str, **kwargs: Any) -> Any:
        return await self.request("POST", url, **kwargs)


def safe_float(value: Any, default: float = 0.0) -> float:
    """Converte in float valori che le API restituiscono come str, None o dict."""
    if value is None:
        return default
    if isinstance(value, dict):
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def safe_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def human_usd(value: float) -> str:
    """Formatta un importo in dollari in modo compatto (12.4K, 3.1M)."""
    value = safe_float(value)
    for unit, size in (("B", 1e9), ("M", 1e6), ("K", 1e3)):
        if abs(value) >= size:
            return f"${value / size:.1f}{unit}"
    return f"${value:.0f}"


def human_age(seconds: float) -> str:
    seconds = max(0, int(seconds))
    if seconds < 3600:
        return f"{seconds // 60}m"
    if seconds < 86400:
        return f"{seconds // 3600}h{(seconds % 3600) // 60:02d}m"
    return f"{seconds // 86400}g{(seconds % 86400) // 3600}h"
