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
import os
import shutil
import sqlite3
import tempfile
from pathlib import Path

from .config import settings
from .store import get_store
from .util import get_logger, now

log = get_logger("memescan.backup")

RAMO = "backup"
NOME_FILE = "memescan.db"


def configurato() -> bool:
    return bool(settings.backup_repo and settings.backup_token)


def _url() -> str:
    """URL con le credenziali. Non finisce mai nei log: vedi _git()."""
    return f"https://x-access-token:{settings.backup_token}@github.com/{settings.backup_repo}.git"


async def _git(*args: str, cwd: str) -> tuple[int, str]:
    proc = await asyncio.create_subprocess_exec(
        "git", *args, cwd=cwd,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
    )
    out, _ = await proc.communicate()
    testo = out.decode("utf-8", "replace")
    # Il token compare negli URL degli errori di git: va tolto prima di loggare.
    if settings.backup_token:
        testo = testo.replace(settings.backup_token, "***")
    return proc.returncode, testo.strip()


def _copia_coerente(destinazione: Path) -> int:
    """Copia il database mentre lo scanner ci sta scrivendo.

    Copiare il file con `cp` durante una scrittura darebbe un backup rotto, e
    in WAL mancherebbe comunque tutto quello che sta nel file laterale. Il
    backup di SQLite invece produce una copia coerente senza fermare niente.
    """
    sorgente = sqlite3.connect(settings.db_path)
    try:
        copia = sqlite3.connect(str(destinazione))
        try:
            sorgente.backup(copia)
        finally:
            copia.close()
    finally:
        sorgente.close()
    return destinazione.stat().st_size


async def esegui() -> dict:
    """Manda una copia del database sul ramo di backup. Ritorna cosa ha fatto."""
    if not configurato():
        return {"ok": False, "motivo": "backup non configurato"}

    lavoro = Path(tempfile.mkdtemp(prefix="memescan-backup-"))
    try:
        peso = _copia_coerente(lavoro / NOME_FILE)

        # Un ramo orfano ricreato da zero: un commit solo, sempre lo stesso
        # peso. Senza questo il repository crescerebbe di qualche megabyte al
        # giorno per sempre.
        for comando in (
            ("init", "-q", "-b", RAMO),
            ("config", "user.email", "memescan@local"),
            ("config", "user.name", "memescan"),
            ("add", NOME_FILE),
        ):
            code, out = await _git(*comando, cwd=str(lavoro))
            if code != 0:
                log.warning("backup fallito su `git %s`: %s", comando[0], out)
                return {"ok": False, "motivo": out[:200]}

        code, out = await _git(
            "commit", "-q", "-m", f"backup {now()}", cwd=str(lavoro)
        )
        if code != 0:
            return {"ok": False, "motivo": out[:200]}

        code, out = await _git("push", "--force", _url(), RAMO, cwd=str(lavoro))
        if code != 0:
            log.warning("push del backup fallito: %s", out)
            return {"ok": False, "motivo": out[:200]}

        log.info("backup inviato: %.1f MB", peso / 1e6)
        return {"ok": True, "bytes": peso, "repo": settings.backup_repo, "ramo": RAMO}
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


async def scarica() -> tuple[Path | None, str]:
    """Tira giu' l'ultimo backup. Ritorna la cartella temporanea e l'errore.

    Chi chiama deve cancellare la cartella quando ha finito.
    """
    if not configurato():
        return None, "backup non configurato"

    lavoro = Path(tempfile.mkdtemp(prefix="memescan-restore-"))
    code, out = await _git(
        "clone", "--quiet", "--depth", "1", "--branch", RAMO, _url(), ".",
        cwd=str(lavoro),
    )
    if code != 0:
        shutil.rmtree(lavoro, ignore_errors=True)
        motivo = "nessun backup trovato" if "not found" in out.lower() else out[:200]
        return None, motivo

    ok, motivo = _sembra_un_database(lavoro / NOME_FILE)
    if not ok:
        shutil.rmtree(lavoro, ignore_errors=True)
        return None, motivo
    return lavoro, ""


def riepilogo() -> dict:
    """Stato del backup per la dashboard."""
    store = get_store()
    ultimo = store.get_meta("ultimo_backup", "0")
    return {
        "configurato": configurato(),
        "repo": settings.backup_repo,
        "ultimo": int(float(ultimo or 0)),
        "esito": store.get_meta("ultimo_backup_esito", ""),
    }
