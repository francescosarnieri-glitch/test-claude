"""Notifiche Telegram.

Telegram fa da app mobile per gli alert: e' gia' installata, ha le notifiche
push native e non richiede di tenere aperto niente. Il messaggio e' pensato per
essere letto in tre secondi sullo schermo del telefono, con i bottoni per
aprire subito il token dove serve.
"""

from __future__ import annotations

from html import escape

from . import tunables
from .config import settings
from .models import PairSnapshot
from .safety import SafetyReport
from .scoring import Score
from .util import HttpClient, get_logger, human_age, human_usd, safe_float

log = get_logger("memescan.notify")

TELEGRAM_API = "https://api.telegram.org"


def _score_badge(score: float) -> str:
    if score >= 80:
        return "🟢"
    if score >= 65:
        return "🟡"
    return "⚪"


#: Intestazioni per tipo di alert. Servono a capire dalla notifica, senza
#: aprire niente, se il token arriva dal punteggio del bot o dai wallet
#: che seguiamo: sono due segnali diversi e si reagisce in modo diverso.
ALERT_KINDS = {
    "scanner": "📡 SCANNER",
    "whales": "🐋 WHALES",
    "scanner_whales": "📡🐋 SCANNER + WHALES",
    # Non viene mai spedito: e' lo stato di un alert vecchio il cui motivo e'
    # decaduto. Sta qui perche' _kind_title non debba indovinare.
    "scaduto": "🕓 SCADUTO",
}


def _kind_title(kind: str) -> str:
    return ALERT_KINDS.get(kind, ALERT_KINDS["scanner"])


def _verdict_badge(verdict: str) -> str:
    return {
        "pulito": "🛡️ pulito",
        "accettabile": "🟨 accettabile",
        "rischioso": "🟧 rischioso",
        "pericoloso": "🟥 pericoloso",
    }.get(verdict, "❔ sconosciuto")


class Notifier:
    def __init__(self) -> None:
        self.token = settings.telegram_bot_token
        self.chat_id = settings.telegram_chat_id
        self.http = HttpClient(base_url=TELEGRAM_API, timeout=20.0)
        self.enabled = settings.telegram_enabled
        if not self.enabled:
            log.warning(
                "Telegram non configurato: gli alert restano solo su dashboard e log. "
                "Imposta TELEGRAM_BOT_TOKEN e TELEGRAM_CHAT_ID per riceverli sul telefono."
            )

    async def close(self) -> None:
        await self.http.close()

    async def send(self, text: str, buttons: list[list[dict]] | None = None) -> bool:
        if not self.enabled:
            log.info("[alert non inviato] %s", text.replace("\n", " | ")[:200])
            return False
        payload: dict = {
            "chat_id": self.chat_id,
            "text": text,
            "parse_mode": "HTML",
            "disable_web_page_preview": True,
        }
        if buttons:
            payload["reply_markup"] = {"inline_keyboard": buttons}
        result = await self.http.post(f"/bot{self.token}/sendMessage", json=payload)
        if not result or not result.get("ok"):
            log.warning("invio Telegram fallito: %s", result)
            return False
        return True

    # -- costruzione dei link ----------------------------------------------

    def _links(self, snapshot: PairSnapshot) -> list[list[dict]]:
        chain = settings.dexscreener_chain
        token = snapshot.token_address
        explorer = settings.blockscout_url.rstrip("/")
        row_one = [
            {"text": "📊 Dexscreener", "url": f"https://dexscreener.com/{chain}/{token}"},
            {"text": "🔍 Explorer", "url": f"{explorer}/token/{token}"},
        ]
        row_two = [
            {"text": "🫧 Holder", "url": f"{explorer}/token/{token}?tab=holders"},
            {"text": "🐦 Cerca su X", "url": f"https://x.com/search?q={token}&f=live"},
        ]
        return [row_one, row_two]

    # -- messaggi -----------------------------------------------------------

    async def send_candidate(
        self,
        snapshot: PairSnapshot,
        safety: SafetyReport,
        score: Score,
        wallet_hits: int = 0,
        kind: str = "scanner",
    ) -> bool:
        symbol = escape(snapshot.symbol or "???")
        name = escape((snapshot.name or "")[:40])
        badge = _score_badge(score.total)

        lines = [
            f"{_kind_title(kind)} · <b>${symbol}</b>",
            f"{badge} punteggio <b>{score.total:.0f}</b>/100",
        ]
        # Quanto vale senza i punti delle balene: e' il numero che spiega in
        # quale casella e' finito, e serve a non scambiare per un buon token
        # uno che sta in piedi solo perche' l'ha comprato qualcuno.
        if score.own < score.total:
            lines.append(f"      da solo: <b>{score.own:.0f}</b>/100")
        if name and name.lower() != symbol.lower():
            lines.append(f"<i>{name}</i>")

        lines.append("")
        lines.append(
            f"💧 Liq {human_usd(snapshot.liquidity_usd)}  •  "
            f"📈 Vol 1h {human_usd(snapshot.volume_1h)}"
        )
        lines.append(
            f"🏷 MCap {human_usd(snapshot.market_cap)}  •  "
            f"⏱ {human_age(snapshot.age_seconds) if snapshot.age_seconds else 'n/d'}"
        )
        if snapshot.txns_5m:
            lines.append(
                f"🔁 5m: {snapshot.buys_5m} acquisti / {snapshot.sells_5m} vendite  •  "
                f"👥 {safety.holders or snapshot.holders or 0} holder"
            )
        if snapshot.price_change_1h:
            lines.append(f"📊 1h: {snapshot.price_change_1h:+.0f}%")

        lines.append("")
        lines.append(f"Sicurezza: {_verdict_badge(safety.verdict)}")
        details = []
        if safety.ownership_renounced:
            details.append("ownership rinunciata")
        if safety.lp_burned_pct >= 95:
            details.append("LP bruciata")
        if safety.verified:
            details.append("verificato")
        if safety.top10_pct:
            details.append(f"top10 {safety.top10_pct:.0f}%")
        if details:
            lines.append("✅ " + escape(", ".join(details)))
        for flag in safety.warnings[:3]:
            lines.append("⚠️ " + escape(flag["message"]))

        if wallet_hits:
            plural = "wallet tracciati" if wallet_hits > 1 else "wallet tracciato"
            lines.append(f"\n🐋 <b>{wallet_hits} {plural} in acquisto</b>")

        if score.notes:
            unique_notes = list(dict.fromkeys(score.notes))[:3]
            lines.append("")
            lines.append("\n".join("• " + escape(note) for note in unique_notes))

        lines.append("")
        lines.append(f"<code>{snapshot.token_address}</code>")

        return await self.send("\n".join(lines), self._links(snapshot))

    async def send_wallet_alert(
        self, events: list[dict], snapshot: PairSnapshot | None = None
    ) -> bool:
        """Alert dedicato quando i wallet tracciati comprano.

        Arriva prima di qualsiasi metrica di mercato, quindi il messaggio e'
        volutamente scarno: spesso a quel punto non ci sono ancora dati.
        """
        if not events:
            return False
        token = events[0]["token_address"]
        symbol = escape(events[0].get("symbol") or "???")
        wallets = sorted({e["wallet"] for e in events})

        lines = [
            f"{ALERT_KINDS['whales']} · <b>${symbol}</b>",
            "Comprato dai wallet che segui",
            "",
        ]
        for wallet in wallets[:5]:
            lines.append(f"• <code>{wallet[:10]}…{wallet[-6:]}</code>")
        if len(wallets) > 5:
            lines.append(f"• …e altri {len(wallets) - 5}")

        if len(wallets) >= 2:
            lines.append(f"\n⚡️ <b>{len(wallets)} wallet indipendenti sullo stesso token</b>")

        if snapshot and snapshot.liquidity_usd:
            lines.append("")
            lines.append(
                f"💧 Liq {human_usd(snapshot.liquidity_usd)}  •  "
                f"🏷 MCap {human_usd(snapshot.market_cap)}"
            )

        lines.append("")
        lines.append(f"<code>{token}</code>")

        link_source = snapshot or PairSnapshot(token_address=token, symbol=symbol)
        return await self.send("\n".join(lines), self._links(link_source))

    async def send_whales_joined(
        self, snapshot: PairSnapshot, wallet_hits: int, existing: dict | None = None
    ) -> bool:
        """Le balene sono entrate su un token gia' segnalato dal punteggio.

        E' il momento in cui due segnali indipendenti — com'e' fatto il token e
        chi lo sta comprando — dicono la stessa cosa. Prima questo passaggio
        non veniva notificato: l'alert era gia' partito e il token restava
        marcato come trovato dallo scanner, quindi la notizia si perdeva.
        """
        symbol = escape(snapshot.symbol or "???")
        row = existing or {}
        plural = "wallet tracciati" if wallet_hits > 1 else "wallet tracciato"

        lines = [
            f"{ALERT_KINDS['scanner_whales']} · <b>${symbol}</b>",
            f"<b>{wallet_hits} {plural}</b> sono appena entrati su un token che ti avevo",
            "gia' segnalato: adesso lo dicono tutti e due i segnali.",
            "",
        ]

        segnalato_a = safe_float(row.get("price_at_alert"))
        if segnalato_a and snapshot.price_usd:
            variazione = (snapshot.price_usd / segnalato_a - 1) * 100
            lines.append(f"Dal mio alert: <b>{variazione:+.0f}%</b>")
        if row.get("score"):
            lines.append(f"Punteggio all'epoca: <b>{float(row['score']):.0f}</b>/100")

        lines.append(
            f"💧 Liq {human_usd(snapshot.liquidity_usd)}  •  "
            f"🏷 MCap {human_usd(snapshot.market_cap)}"
        )
        lines.append("")
        lines.append(f"<code>{snapshot.token_address}</code>")

        return await self.send("\n".join(lines), self._links(snapshot))

    async def send_peak(
        self, snapshot: PairSnapshot, traguardo: float, multiplo: float, prima: dict
    ) -> bool:
        """Un token gia' segnalato ha raddoppiato (poi 5x, poi 10x).

        E' l'unica altra novita' che vale una notifica su un token vecchio:
        non "e' ancora sopra soglia", ma "quello che ti avevo detto sta
        andando". Arriva una volta per traguardo, non a ogni giro.
        """
        symbol = escape(snapshot.symbol or "???")
        festa = "🚀" if traguardo >= 5 else "📈"
        entrata = safe_float(prima.get("price_at_alert"))

        lines = [
            f"{festa} <b>${symbol} ha fatto {multiplo:.1f}x</b>",
            f"dal mio alert di {_kind_title(prima.get('alert_kind') or 'scanner')}",
            "",
        ]
        if entrata:
            lines.append(f"Segnalato a {entrata:.8f}".rstrip("0").rstrip("."))
            lines.append(f"Adesso {snapshot.price_usd:.8f}".rstrip("0").rstrip("."))
        lines.append(
            f"💧 Liq {human_usd(snapshot.liquidity_usd)}  •  "
            f"🏷 MCap {human_usd(snapshot.market_cap)}"
        )
        lines.append("")
        lines.append(f"<code>{snapshot.token_address}</code>")

        return await self.send("\n".join(lines), self._links(snapshot))

    async def send_startup(self, info: dict) -> bool:
        lines = [
            "🚀 <b>memescan avviato</b>",
            "",
            f"Chain: <code>{info.get('chain_id')}</code> • blocco {info.get('block_number'):,}",
            f"Wallet tracciati: {info.get('wallets', 0)}",
            f"Soglia di alert: {tunables.get('alert_min_score'):.0f}/100",
        ]
        if info.get("warnings"):
            lines.append("")
            for warning in info["warnings"]:
                lines.append("⚠️ " + escape(warning))
        return await self.send("\n".join(lines))

    async def send_error(self, message: str) -> bool:
        return await self.send(f"❌ <b>memescan</b>\n{escape(message)}")
