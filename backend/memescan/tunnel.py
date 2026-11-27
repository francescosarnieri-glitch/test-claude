"""Dov'e' raggiungibile la dashboard, adesso.

La dashboard non sta su un indirizzo fisso. Passa da un tunnel Cloudflare
«al volo», che in cambio di una connessione in uscita regala un indirizzo
https pubblico - e ne regala uno **diverso ogni volta che parte**. Serviva
cosi' perche' sull'istanza gratuita non si puo' contare ne' su una porta in
entrata ne' su un indirizzo IP stabile.

Il prezzo di quella scelta e' questo modulo. Chi installa la macchina riceve
l'indirizzo una volta, al primo avvio, da uno script di sistema che gira al
boot e poi mai piu'. Ma il tunnel ha `Restart=always`: se cade - e cade - si
rialza da solo con un indirizzo nuovo, e nessuno lo dice a nessuno. Da fuori
si vede solo una dashboard che non risponde piu', senza un motivo visibile e
senza un posto dove andare a cercarlo. A chi usa questo scanner dal telefono,
senza accesso alla macchina, resta una sola mossa: riavviare tutto e sperare.

Quindi l'indirizzo se lo legge il programma, e lo dice lui. La fonte giusta e'
il log di cloudflared, non il file salvato al boot: quel file racconta com'era
il mondo all'accensione, il log racconta com'e' adesso. Il log viene cancellato
a ogni avvio del tunnel, percio' il primo indirizzo che ci compare dentro e'
quello valido in questo momento.

Non solleva mai un'eccezione. Un indirizzo che non si riesce a leggere e' una
riga in meno in un messaggio, non un motivo per non far partire lo scanner.
"""

from __future__ import annotations

import os
import re
from pathlib import Path

from .util import get_logger

log = get_logger("memescan.tunnel")

#: Il log di cloudflared: la verita' di adesso. Viene riscritto da zero a ogni
#: avvio del tunnel, quindi il primo indirizzo che contiene e' quello buono.
LOG_CLOUDFLARED = os.getenv("CLOUDFLARED_LOG", "/var/log/cloudflared.log")

#: Scritto una sola volta, al boot, dallo script di installazione. Vale come
#: ripiego: se il tunnel e' ripartito dopo, qui c'e' un indirizzo morto.
FILE_INDIRIZZO = os.getenv("TUNNEL_URL_FILE", "/opt/memescan/tunnel-url.txt")

INDIRIZZO = re.compile(r"https://[a-z0-9][a-z0-9-]*\.trycloudflare\.com")

#: Il log cresce finche' il tunnel resta acceso. L'indirizzo lo annuncia nelle
#: prime righe, quindi non serve leggerlo tutto: mezzo mega basta e avanza, e
#: su una macchina da un giga di memoria non si legge un file senza un tetto.
MAX_LETTURA = 512 * 1024


def _dal_log(percorso: str) -> str:
    testo = Path(percorso).read_text(errors="replace")[:MAX_LETTURA]
    trovati = INDIRIZZO.findall(testo)
    return trovati[0] if trovati else ""


def _dal_file(percorso: str) -> str:
    trovati = INDIRIZZO.findall(Path(percorso).read_text(errors="replace"))
    return trovati[0] if trovati else ""


def indirizzo_dashboard() -> str:
    """L'indirizzo pubblico della dashboard, o stringa vuota se non si sa.

    Prima il log del tunnel, poi il file del boot. L'ordine conta: il file e'
    aggiornato solo alla prima accensione, e dopo un riavvio del tunnel
    direbbe con sicurezza una cosa falsa. Meglio nessun indirizzo che uno
    sbagliato - su uno sbagliato si perde tempo a chiedersi perche' non va.
    """
    for nome, leggi, percorso in (
        ("log del tunnel", _dal_log, LOG_CLOUDFLARED),
        ("file del boot", _dal_file, FILE_INDIRIZZO),
    ):
        try:
            trovato = leggi(percorso)
        except FileNotFoundError:
            continue
        except OSError as exc:
            # Il caso che conta davvero: il log lo scrive cloudflared da root e
            # lo scanner gira con un altro utente. Se non e' leggibile va detto
            # nel log dello scanner, o la riga mancante nel messaggio resta
            # senza spiegazione.
            log.warning("%s non leggibile (%s): %s", nome, percorso, exc)
            continue
        if trovato:
            return trovato
    return ""
