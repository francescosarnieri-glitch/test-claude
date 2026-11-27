"""Copia del database su un repository GitHub privato.

Il punto non e' avere una copia: e' poterla rimettere. Chi usa questo scanner
non ha accesso alla macchina che lo ospita, quindi un backup da scaricare a
mano sarebbe un backup inutilizzabile. Su GitHub invece lo script di primo
avvio lo ritrova da solo e lo ripristina prima che il servizio parta.

Serve un repository DIVERSO da quello del codice, e un token che possa
scrivere solo li'. Il motivo e' che la macchina si aggiorna da sola tirando
giu' il codice da GitHub: se il token potesse scrivere anche nel repository
del codice, chiunque entrasse nella macchina potrebbe farle eseguire
qualunque cosa al giro di aggiornamento successivo.

Il ramo del backup si riscrive ogni volta invece di accumulare commit: un
database e' un file binario che cambia di continuo, e tenerne la storia
completa gonfierebbe il repository per sempre senza servire a nessuno.
"""

from __future__ import annotations

import asyncio
import gzip
import os
import shutil
import sqlite3
import tempfile
import time
from pathlib import Path

from .config import settings
from .store import get_store
from .util import get_logger, now

log = get_logger("memescan.backup")

RAMO = "backup"

#: Il database, scompattato. E' il nome che vede chi ripristina, e quello che
#: avevano i backup vecchi: continua a essere accettato in lettura.
NOME_FILE = "memescan.db"

#: Quello che spediamo da adesso. Un database SQLite e' per meta' spazio
#: vuoto e testo ripetuto: compresso cala di parecchie volte, e GitHub
#: rifiuta i file sopra i cento megabyte.
NOME_FILE_GZ = "memescan.db.gz"

#: Oltre questa soglia GitHub rifiuta il file e il backup non parte. Serve per
#: dirlo prima e con parole chiare, invece di far leggere a qualcuno il
#: messaggio di un hook di pre-receive.
LIMITE_GITHUB = 100 * 1024 * 1024

#: Righe che git e GitHub stampano sempre e che non spiegano niente. Vanno
#: tolte queste e **solo** queste: scartando tutto cio' che comincia per
#: «remote:» si butterebbe via anche la riga che dice quale file e quanto
#: pesa, che e' esattamente quella che serve.
RUMORE = (
    "remote: error: trace:",
    "remote: error: see http",
    "to https://github.com",
    "hint:",
)


#: Dove stanno repository e token dentro la tabella delle impostazioni.
CHIAVE_REPO = "backup_repo"
CHIAVE_TOKEN = "backup_token"


def _valore(chiave: str, dal_file: str) -> str:
    """Prima quello scritto dalla dashboard, poi quello del file .env.

    Serve il primo perche' chi usa lo scanner non ha accesso alla macchina:
    senza, per accendere il backup bisognerebbe rifare il server da capo.
    Il secondo resta per le installazioni fatte da riga di comando.
    """
    try:
        return get_store().get_settings().get(chiave) or dal_file
    except Exception:  # pragma: no cover - database non ancora pronto
        return dal_file


def repository() -> str:
    return _valore(CHIAVE_REPO, settings.backup_repo)


def token() -> str:
    return _valore(CHIAVE_TOKEN, settings.backup_token)


def configurato() -> bool:
    return bool(repository() and token())


def _url() -> str:
    """URL con le credenziali. Non finisce mai nei log: vedi _git()."""
    return f"https://x-access-token:{token()}@github.com/{repository()}.git"


async def _git(*args: str, cwd: str) -> tuple[int, str]:
    proc = await asyncio.create_subprocess_exec(
        "git", *args, cwd=cwd,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
    )
    out, _ = await proc.communicate()
    testo = out.decode("utf-8", "replace")
    # Il token compare negli URL degli errori di git: va tolto prima di loggare.
    segreto = token()
    if segreto:
        testo = testo.replace(segreto, "***")
    return proc.returncode, testo.strip()


def _motivo(out: str) -> str:
    """Il pezzo di output di git che spiega davvero cos'e' andato storto.

    Tagliare i primi duecento caratteri sembrava ragionevole e invece
    nascondeva proprio la risposta. Quando GitHub rifiuta un file troppo
    grande scrive prima due righe di intestazione - un codice di traccia e un
    link alla documentazione - e solo dopo la riga con il nome del file e il
    peso. Il taglio prendeva le due inutili e buttava via quella buona: per
    mesi l'unica cosa visibile sulla dashboard e' stato un codice esadecimale.

    Qui le righe di servizio si scartano e si tiene il resto.
    """
    utili = []
    for riga in out.splitlines():
        riga = riga.strip()
        if not riga or any(riga.lower().startswith(x) for x in RUMORE):
            continue
        # «remote: error: File ... is 142 MB» si legge meglio senza il
        # prefisso che dice soltanto da che parte arriva.
        for prefisso in ("remote: error: ", "remote: "):
            if riga.startswith(prefisso):
                riga = riga[len(prefisso):]
                break
        utili.append(riga)
    # Se restasse solo rumore, meglio il messaggio intero troncato che niente:
    # un errore che non abbiamo previsto va comunque fatto vedere.
    testo = " · ".join(utili) if utili else out
    return testo[:600]


#: Quanto indietro tenere le monete scartate. Servono solo a ricordare «questa
#: l'ho gia' guardata e non andava»: oltre un mese quella memoria non vale piu'
#: niente, perche' una moneta di due mesi fa non la ricompra nessuno.
GIORNI_SCARTI = 30

#: Quanto indietro tenere i movimenti dei portafogli. I conteggi che contano
#: guardano le ultime ore; tre mesi sono gia' molto piu' del necessario, e
#: servono solo a non perdere del tutto lo storico su cui si fa la pagella.
GIORNI_MOVIMENTI = 90


def _alleggerisci(conn: sqlite3.Connection) -> dict[str, int]:
    """Toglie dalla **copia** la zavorra, senza toccare il database vero.

    Il backup aveva smesso di partire perche' il file ha superato i cento
    megabyte che GitHub accetta. La tentazione sarebbe svuotare il database
    vero, ma non si cancella niente finche' non esiste una copia a cui tornare,
    e la copia e' proprio quello che manca. Quindi si taglia qui: il database
    in funzione resta esattamente com'e', e a dimagrire e' solo il bagaglio.

    Cosa se ne va, e perche' puo' andarsene:

    - **le monete scartate da oltre un mese.** Non sono un risultato, sono il
      ricordo di un controllo gia' fatto. Perderle costa che lo scanner le
      riguardi una volta, non che si perda qualcosa di tuo.
    - **i movimenti dei portafogli oltre i tre mesi.** Ogni conteggio che
      decide qualcosa guarda le ultime ore.

    Cosa non si tocca mai: i portafogli seguiti, le monete segnalate, quelle
    salvate, le pagelle, gli avvisi e le impostazioni. E' tutto cio' per cui
    un backup esiste.
    """
    adesso = int(time.time())
    tagli = (
        ("monete scartate", (
            "DELETE FROM candidates WHERE status = 'rejected' AND watchlisted = 0 "
            "AND COALESCE(alerted_at, 0) = 0 AND COALESCE(last_updated, 0) < ?",
            adesso - GIORNI_SCARTI * 86400,
        )),
        ("movimenti vecchi", (
            "DELETE FROM wallet_events WHERE ts < ?",
            adesso - GIORNI_MOVIMENTI * 86400,
        )),
    )
    tolti: dict[str, int] = {}
    for nome, (sql, parametro) in tagli:
        try:
            tolti[nome] = conn.execute(sql, (parametro,)).rowcount
        except sqlite3.Error as exc:
            # Una tabella che non c'e' o che e' cambiata non deve far saltare
            # il backup: meglio una copia un po' piu' pesante che nessuna.
            log.warning("non ho potuto alleggerire «%s»: %s", nome, exc)
    conn.commit()
    return tolti


def _copia_coerente(cartella: Path) -> tuple[Path, int, int]:
    """Prepara il file da spedire. Torna percorso, peso prima e peso dopo.

    Tre passaggi, ognuno per un motivo diverso:

    1. **La copia** la fa SQLite, non `cp`. Copiare il file mentre lo scanner
       ci scrive darebbe un backup rotto, e in modalita' WAL mancherebbe
       comunque tutto quello che sta nel file laterale.
    2. **L'alleggerimento**, che toglie dalla copia la zavorra - e solo dalla
       copia: il database in funzione non si tocca.
    3. **VACUUM** riscrive la copia compattandola. Un database da cui si
       cancella non restituisce lo spazio: resta pieno di pagine libere che
       pesano come le altre. Senza questo passaggio il punto 2 non
       servirebbe a niente.
    4. **La compressione**. Un database SQLite e' per gran parte spazio vuoto
       e testo ripetuto, e si comprime di parecchie volte. Senza, oltre i
       cento megabyte GitHub rifiuta il caricamento e il backup smette
       semplicemente di esistere - com'e' successo, in silenzio, per piu' di
       tre mesi.
    """
    grezzo = cartella / NOME_FILE
    sorgente = sqlite3.connect(settings.db_path)
    try:
        copia = sqlite3.connect(str(grezzo))
        try:
            sorgente.backup(copia)
            # Il token vive nelle impostazioni, cioe' dentro il database che
            # stiamo per spedire: senza toglierlo, la chiave che apre il
            # ripostiglio finirebbe dentro al ripostiglio. Chi ripristina ha
            # comunque gia' il suo, o dalla dashboard o dal file .env.
            copia.execute("DELETE FROM settings WHERE key = ?", (CHIAVE_TOKEN,))
            copia.commit()
            tolti = _alleggerisci(copia)
            if tolti:
                log.info(
                    "alleggerita la copia: %s",
                    ", ".join(f"{n} {k}" for k, n in tolti.items() if n > 0) or "niente",
                )
            copia.execute("VACUUM")
        finally:
            copia.close()
    finally:
        sorgente.close()

    prima = grezzo.stat().st_size
    compresso = cartella / NOME_FILE_GZ
    # A blocchi e non tutto in memoria: la macchina ha un giga di RAM, e il
    # database e' proprio il file che sta diventando grande.
    with open(grezzo, "rb") as da, gzip.open(compresso, "wb", compresslevel=6) as a:
        shutil.copyfileobj(da, a, length=1024 * 1024)
    grezzo.unlink()
    return compresso, prima, compresso.stat().st_size


async def esegui() -> dict:
    """Manda una copia del database sul ramo di backup. Ritorna cosa ha fatto."""
    if not configurato():
        return {"ok": False, "motivo": "backup non configurato"}

    lavoro = Path(tempfile.mkdtemp(prefix="memescan-backup-"))
    try:
        _, prima, peso = _copia_coerente(lavoro)
        log.info(
            "backup pronto: %.1f MB compressi da %.1f MB", peso / 1e6, prima / 1e6
        )

        # Dirlo prima, e dire cosa fare. Lasciando parlare GitHub arriverebbe
        # un messaggio di hook rifiutato, che non suggerisce niente a nessuno.
        if peso > LIMITE_GITHUB:
            return {
                "ok": False,
                "bytes": peso,
                "motivo": (
                    f"il backup pesa {peso / 1e6:.0f} MB anche compresso, e GitHub "
                    f"rifiuta i file sopra i 100 MB. Va alleggerito il database: "
                    f"non compresso sono {prima / 1e6:.0f} MB."
                ),
            }

        # Un ramo orfano ricreato da zero: un commit solo, sempre lo stesso
        # peso. Senza questo il repository crescerebbe di qualche megabyte al
        # giorno per sempre.
        for comando in (
            ("init", "-q", "-b", RAMO),
            ("config", "user.email", "memescan@local"),
            ("config", "user.name", "memescan"),
            ("add", NOME_FILE_GZ),
        ):
            code, out = await _git(*comando, cwd=str(lavoro))
            if code != 0:
                log.warning("backup fallito su `git %s`: %s", comando[0], out)
                return {"ok": False, "motivo": _motivo(out)}

        code, out = await _git(
            "commit", "-q", "-m", f"backup {now()}", cwd=str(lavoro)
        )
        if code != 0:
            return {"ok": False, "motivo": _motivo(out)}

        code, out = await _git("push", "--force", _url(), RAMO, cwd=str(lavoro))
        if code != 0:
            log.warning("push del backup fallito: %s", out)
            return {"ok": False, "motivo": _motivo(out)}

        log.info("backup inviato: %.1f MB", peso / 1e6)
        return {
            "ok": True, "bytes": peso, "bytes_db": prima,
            "repo": repository(), "ramo": RAMO,
        }
    finally:
        shutil.rmtree(lavoro, ignore_errors=True)


def _sembra_un_database(percorso: Path) -> tuple[bool, str]:
    """Controlla che il file scaricato sia davvero il nostro database.

    Sovrascrivere un database funzionante con un file rotto o con quello di
    qualcun altro sarebbe il modo peggiore di perdere i dati: proprio mentre
    si crede di metterli al sicuro.
    """
    if not percorso.exists() or percorso.stat().st_size == 0:
        return False, "il backup e' vuoto"
    try:
        conn = sqlite3.connect(f"file:{percorso}?mode=ro", uri=True)
        try:
            tabelle = {
                row[0] for row in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table'"
                )
            }
        finally:
            conn.close()
    except sqlite3.DatabaseError:
        return False, "il file non e' un database SQLite"
    mancanti = {"candidates", "tracked_wallets"} - tabelle
    if mancanti:
        return False, f"nel backup mancano le tabelle {', '.join(sorted(mancanti))}"
    return True, ""


async def scarica() -> tuple[Path | None, str, int]:
    """Tira giu' l'ultimo backup. Ritorna cartella, errore e data della copia.

    La data viene dal commit, cioe' dal file stesso: e' l'unica fonte che sa
    quando quel backup e' stato fatto anche su una macchina appena creata, che
    di suo non ricorda niente. Chi chiama deve cancellare la cartella.
    """
    if not configurato():
        return None, "backup non configurato", 0

    lavoro = Path(tempfile.mkdtemp(prefix="memescan-restore-"))
    code, out = await _git(
        "clone", "--quiet", "--depth", "1", "--branch", RAMO, _url(), ".",
        cwd=str(lavoro),
    )
    if code != 0:
        shutil.rmtree(lavoro, ignore_errors=True)
        motivo = "nessun backup trovato" if "not found" in out.lower() else _motivo(out)
        return None, motivo, 0

    # I backup nuovi arrivano compressi, quelli vecchi no. Si accettano
    # entrambi: il giorno in cui serve ripristinare non e' il giorno giusto
    # per scoprire che la copia che hai e' nel formato sbagliato.
    compresso = lavoro / NOME_FILE_GZ
    if compresso.exists():
        try:
            with gzip.open(compresso, "rb") as da, open(lavoro / NOME_FILE, "wb") as a:
                shutil.copyfileobj(da, a, length=1024 * 1024)
        except OSError as exc:
            shutil.rmtree(lavoro, ignore_errors=True)
            return None, f"il backup compresso non si apre: {exc}", 0

    ok, motivo = _sembra_un_database(lavoro / NOME_FILE)
    if not ok:
        shutil.rmtree(lavoro, ignore_errors=True)
        return None, motivo, 0

    code, quando = await _git("log", "-1", "--format=%ct", cwd=str(lavoro))
    fatto_il = int(quando) if code == 0 and quando.strip().isdigit() else 0
    return lavoro, "", fatto_il


def riepilogo() -> dict:
    """Stato del backup per la dashboard."""
    store = get_store()
    ultimo = store.get_meta("ultimo_backup", "0")
    return {
        "configurato": configurato(),
        "repo": repository(),
        # Il token non esce mai da qui: alla dashboard basta sapere che c'e'.
        "token_presente": bool(token()),
        "ultimo": int(float(ultimo or 0)),
        "esito": store.get_meta("ultimo_backup_esito", ""),
        "ripristino": int(float(store.get_meta("ultimo_ripristino", "0") or 0)),
    }
