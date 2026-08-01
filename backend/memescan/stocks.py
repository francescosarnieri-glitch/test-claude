"""Riconosce le azioni tokenizzate, e chi prova a spacciarsi per una.

Su Robinhood Chain, accanto alle meme coin, girano le azioni vere portate
on-chain: Apple, Tesla, NVIDIA, AMD. Non sono lanci, non esplodono in
un'ora, e trattarle come candidati sporca ogni statistica.

Distinguerle dall'eta' della pozza funzionava a meta': una meme coin di
quaranta giorni finiva nello stesso cesto. Il segnale giusto e' un altro e
non si puo' falsificare: **le emette tutte lo stesso indirizzo**. Verificato
su Apple, Tesla, NVIDIA, AMD, Micron e CoreWeave, un solo creatore per tutte.

Da qui esce anche il secondo pezzo, che vale piu' del primo. Cercando "AMD"
su questa chain compaiono sette monete che si chiamano AMD, con dieci holder
l'una: sono trappole per chi legge il simbolo e pensa all'azione. Il nome si
copia, l'icona si copia, il creatore no. Chiamarsi come un'azione senza
essere il contratto che l'ha emessa e' una prova, non un sospetto.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from .config import settings
from .models import PairSnapshot
from .util import get_logger

log = get_logger("memescan.stocks")

#: Come si chiamano le azioni ufficiali: "Apple • Robinhood Token".
SUFFISSO_UFFICIALE = "robinhood token"

AZIONE = "azione"
CLONE_AZIONE = "clone_azione"
LANCIO = "lancio"

# Quale sia il contratto ufficiale di un simbolo non cambia mai: si tiene per
# tutta la vita del processo. La chiave e' il simbolo in minuscolo.
_ufficiali: dict[str, str] = {}
_cercati: dict[str, float] = {}
_TTL_RICERCA = 3600.0


@dataclass(slots=True)
class Verdetto:
    natura: str
    motivo: str = ""
    ufficiale: str = ""

    @property
    def e_azione(self) -> bool:
        return self.natura == AZIONE

    @property
    def e_clone(self) -> bool:
        return self.natura == CLONE_AZIONE


def _emittente() -> str:
    return (settings.stock_issuer or "").lower()


def _sembra_ufficiale(nome: str) -> bool:
    return SUFFISSO_UFFICIALE in (nome or "").lower()


async def _creatore(blockscout, indirizzo: str) -> str:
    try:
        info = await blockscout.address_info(indirizzo)
    except Exception as exc:  # pragma: no cover - dipende dalla rete
        log.debug("creatore non leggibile per %s: %s", indirizzo[:10], exc)
        return ""
    return (info.get("creator") or "").lower()


async def _azione_ufficiale_con_simbolo(blockscout, simbolo: str) -> str:
    """Indirizzo dell'azione ufficiale con quel simbolo, se esiste.

    Stringa vuota se non esiste o se non si e' potuto verificare: nel dubbio
    non si accusa nessuno.
    """
    chiave = simbolo.lower()
    if chiave in _ufficiali:
        return _ufficiali[chiave]
    # Il valore di partenza deve essere "infinitamente tempo fa", non zero:
    # time.monotonic() conta dall'avvio della macchina, quindi su un server
    # appena acceso vale poche centinaia di secondi e con zero risulterebbe
    # "cercato un attimo fa" per sempre.
    if time.monotonic() - _cercati.get(chiave, float("-inf")) < _TTL_RICERCA:
        return ""

    _cercati[chiave] = time.monotonic()
    try:
        risultati = await blockscout.search_tokens(simbolo)
    except Exception as exc:  # pragma: no cover - dipende dalla rete
        log.debug("ricerca del simbolo %s non riuscita: %s", simbolo, exc)
        return ""

    for voce in risultati:
        if (voce.get("symbol") or "").lower() != chiave:
            continue
        if not _sembra_ufficiale(voce.get("name", "")):
            continue
        indirizzo = (voce.get("address") or "").lower()
        # Il nome e' solo un indizio: la prova e' chi l'ha creato.
        if indirizzo and await _creatore(blockscout, indirizzo) == _emittente():
            _ufficiali[chiave] = indirizzo
            log.info("azione ufficiale %s: %s", simbolo, indirizzo[:12])
            return indirizzo
    return ""


async def classifica(blockscout, snapshot: PairSnapshot) -> Verdetto:
    """Dice se un token e' un'azione, la copia di un'azione, o un lancio."""
    emittente = _emittente()
    if not emittente:
        return Verdetto(LANCIO)

    token = snapshot.token_address.lower()
    creatore = await _creatore(blockscout, token)
    if creatore and creatore == emittente:
        return Verdetto(AZIONE, "emessa dal contratto ufficiale")

    # Spacciarsi per ufficiale nel nome senza esserlo e' gia' una risposta:
    # non serve nemmeno sapere quale azione stia copiando.
    if _sembra_ufficiale(snapshot.name) and creatore:
        return Verdetto(
            CLONE_AZIONE,
            "si presenta come azione ufficiale ma non l'ha emessa Robinhood",
        )

    simbolo = (snapshot.symbol or "").strip()
    if not simbolo:
        return Verdetto(LANCIO)

    ufficiale = await _azione_ufficiale_con_simbolo(blockscout, simbolo)
    if ufficiale and ufficiale != token:
        return Verdetto(
            CLONE_AZIONE,
            f"usa il simbolo dell'azione {simbolo.upper()} senza esserlo",
            ufficiale,
        )
    return Verdetto(LANCIO)


def dimentica() -> None:
    """Svuota le cache. Serve ai test e dopo una pulizia del database."""
    _ufficiali.clear()
    _cercati.clear()
