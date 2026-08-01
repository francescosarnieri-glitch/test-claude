"""API HTTP e hosting della dashboard.

Lo stesso processo fa girare il motore e serve l'interfaccia: e' un servizio
per un utente solo, non c'e' motivo di separarli e cosi' il deploy resta un
singolo container.
"""

from __future__ import annotations

import asyncio
import json
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import backup, tunables
from .config import settings
from .store import get_store
from .util import get_logger, now, setup_logging
from .worker import Engine

log = get_logger("memescan.api")

WEB_DIR = Path(__file__).resolve().parent / "web"

engine: Engine | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global engine
    setup_logging()
    engine = Engine()
    await engine.start()
    try:
        yield
    finally:
        await engine.stop()


app = FastAPI(title="memescan", version="1.0.0", lifespan=lifespan)


async def require_token(request: Request) -> None:
    """Protezione minima quando il servizio e' esposto fuori dalla rete locale.

    Se API_TOKEN e' vuoto l'API resta aperta: comodo in locale, da non fare su
    un VPS raggiungibile da internet.
    """
    if not settings.api_token:
        return
    provided = (
        request.headers.get("x-api-token")
        or request.query_params.get("token")
        or (request.headers.get("authorization", "").removeprefix("Bearer ").strip())
    )
    if provided != settings.api_token:
        raise HTTPException(status_code=401, detail="token non valido")


def _decode_row(row: dict) -> dict:
    """Riporta a oggetti i campi JSON salvati come testo in SQLite."""
    out = dict(row)
    for key in ("safety_json", "breakdown_json", "payload_json"):
        if key in out and isinstance(out[key], str):
            try:
                out[key.removesuffix("_json")] = json.loads(out[key] or "{}")
            except json.JSONDecodeError:
                out[key.removesuffix("_json")] = {}
            out.pop(key)
    return out


@app.get("/api/health")
async def health() -> dict:
    store = get_store()
    status = engine.status if engine else {}
    return {
        "ok": True,
        "uptime_seconds": now() - status.get("started_at", now()),
        "chain_ok": status.get("chain_ok", False),
        "last_scan": status.get("last_scan", 0),
        "last_wallet_poll": status.get("last_wallet_poll", 0),
        "scans": status.get("scans", 0),
        "errors": status.get("errors", 0),
        "telegram": settings.telegram_enabled,
        "tracked_wallets": len(store.list_tracked_wallets()),
        "missing_config": settings.missing_required(),
    }


@app.get("/api/stats", dependencies=[Depends(require_token)])
async def stats() -> dict:
    return get_store().stats()


@app.get("/api/candidates", dependencies=[Depends(require_token)])
async def candidates(
    status: str | None = Query(default=None),
    limit: int = Query(default=50, le=300),
    min_score: float = Query(default=0),
) -> list[dict]:
    rows = get_store().list_candidates(status=status, limit=limit, min_score=min_score)
    return [_decode_row(row) for row in rows]


@app.get("/api/recent", dependencies=[Depends(require_token)])
async def recent(limit: int = Query(default=50, le=300)) -> list[dict]:
    return [_decode_row(row) for row in get_store().list_recent(limit=limit)]


@app.get("/api/pending", dependencies=[Depends(require_token)])
async def pending(limit: int = Query(default=40, le=200)) -> list[dict]:
    """Pool visti on-chain ma non ancora indicizzati: i piu' giovani in assoluto."""
    return [_decode_row(row) for row in get_store().list_pending(limit=limit)]


@app.get("/api/watchlist", dependencies=[Depends(require_token)])
async def watchlist(limit: int = Query(default=60, le=200)) -> list[dict]:
    """I token salvati con la stella. Prima non c'era modo di rivederli."""
    return [_decode_row(row) for row in get_store().list_watchlist(limit=limit)]


@app.get("/api/performance", dependencies=[Depends(require_token)])
async def performance() -> dict:
    """Risponde a "sta funzionando?": esiti delle chiamate e motivi di scarto.

    I numeri c'erano gia' tutti, sparsi fra intestazione e query mai lette.
    Metterli insieme e' l'unico modo di rispondere alla domanda vera.
    """
    store = get_store()
    return {**store.stats(), "scarti_24h": store.rejection_stats()}


@app.get("/api/alerts", dependencies=[Depends(require_token)])
async def alerts(limit: int = Query(default=50, le=200)) -> list[dict]:
    return [_decode_row(row) for row in get_store().recent_alerts(limit=limit)]


@app.get("/api/notifiche", dependencies=[Depends(require_token)])
async def notifiche(limit: int = Query(default=50, le=200)) -> list[dict]:
    """Solo la pozza che si ritira: l'unico avviso che chiede di agire subito."""
    return [_decode_row(row) for row in get_store().notifiche(limit=limit)]


@app.get("/api/wallets", dependencies=[Depends(require_token)])
async def wallets() -> dict:
    store = get_store()
    # Quanti token diversi ha comprato ciascuno nell'ultimo giorno: e' il
    # numero che distingue una whale da uno sniper automatico.
    # Solo i lanci: le azioni tokenizzate non dicono niente su chi e' whale.
    attivita = store.wallet_activity(solo_lanci=True)
    limite = tunables.get("max_wallet_tokens_per_day")
    righe = []
    for wallet in store.list_tracked_wallets(enabled_only=False):
        comprati = attivita.get(wallet["address"], 0)
        righe.append({
            **wallet,
            "tokens_24h": comprati,
            "is_bot": bool(limite > 0 and comprati > limite),
        })
    # I piu' sospetti in cima: sono quelli su cui c'e' da decidere.
    righe.sort(key=lambda r: r["tokens_24h"], reverse=True)
    return {
        "wallets": righe,
        "bot_limit": limite,
        "recent_events": store.recent_wallet_events(limit=40),
    }


@app.post("/api/wallets", dependencies=[Depends(require_token)])
async def add_wallet(payload: dict) -> dict:
    address = (payload.get("address") or "").strip().lower()
    if not address.startswith("0x") or len(address) != 42:
        raise HTTPException(status_code=400, detail="indirizzo EVM non valido")
    get_store().add_tracked_wallet(address, label=payload.get("label", ""))
    return {"ok": True, "address": address}


@app.delete("/api/wallets/{address}", dependencies=[Depends(require_token)])
async def remove_wallet(address: str) -> dict:
    get_store().remove_tracked_wallet(address)
    return {"ok": True}


@app.post("/api/watchlist/{token_address}", dependencies=[Depends(require_token)])
async def toggle_watchlist(token_address: str, payload: dict | None = None) -> dict:
    watched = True if payload is None else bool(payload.get("watched", True))
    get_store().set_watchlist(token_address, watched)
    return {"ok": True, "watched": watched}


@app.post("/api/rescan/{token_address}", dependencies=[Depends(require_token)])
async def rescan(token_address: str) -> dict:
    if engine is None:
        raise HTTPException(status_code=503, detail="motore non ancora avviato")
    return _decode_row(await engine.rescan_token(token_address.lower()))


@app.post("/api/wallets/discover", dependencies=[Depends(require_token)])
async def discover_wallets() -> dict:
    """Avvia la ricerca dei wallet profittevoli senza far aspettare la risposta.

    La scansione dei primi acquirenti dura minuti: se la richiesta restasse
    appesa, il telefono andrebbe in timeout e sembrerebbe non aver funzionato.
    """
    if engine is None:
        raise HTTPException(status_code=503, detail="motore non ancora avviato")
    asyncio.create_task(engine.discover_wallets())
    return {"started": True}


@app.get("/api/settings", dependencies=[Depends(require_token)])
async def read_settings() -> list[dict]:
    return tunables.snapshot()


@app.put("/api/settings", dependencies=[Depends(require_token)])
async def write_settings(payload: dict) -> list[dict]:
    """Applica le modifiche fatte dalla dashboard, una chiave alla volta."""
    for key, value in (payload or {}).items():
        try:
            if value is None:
                tunables.reset(key)
            else:
                tunables.set_value(key, value)
        except KeyError:
            raise HTTPException(status_code=400, detail=f"impostazione sconosciuta: {key}")
        except (TypeError, ValueError):
            raise HTTPException(status_code=400, detail=f"valore non valido per {key}")
    return tunables.snapshot()


@app.get("/api/backup", dependencies=[Depends(require_token)])
async def backup_stato() -> dict:
    return backup.riepilogo()


@app.put("/api/backup/config", dependencies=[Depends(require_token)])
async def backup_config(payload: dict) -> dict:
    """Salva repository e token del backup scritti dalla dashboard.

    Finiscono nel database e non nel file di configurazione, perche' chi usa
    lo scanner non ha accesso alla macchina: altrimenti per accendere il
    backup bisognerebbe rifare il server da capo.
    """
    store = get_store()
    repo = (payload.get("repo") or "").strip().strip("/")
    if repo:
        if repo.startswith("http"):
            # Incollare l'indirizzo completo dal browser e' la cosa piu'
            # naturale del mondo: si accetta e si tiene solo owner/repo.
            repo = repo.split("github.com/", 1)[-1].removesuffix(".git").strip("/")
        if repo.count("/") != 1:
            raise HTTPException(status_code=400, detail="serve nella forma proprietario/repository")
        store.set_setting(backup.CHIAVE_REPO, repo)

    gettone = (payload.get("token") or "").strip()
    if gettone:
        store.set_setting(backup.CHIAVE_TOKEN, gettone)

    if payload.get("dimentica"):
        store.clear_setting(backup.CHIAVE_REPO)
        store.clear_setting(backup.CHIAVE_TOKEN)
    return backup.riepilogo()


@app.post("/api/backup", dependencies=[Depends(require_token)])
async def backup_adesso() -> dict:
    if engine is None:
        raise HTTPException(status_code=503, detail="motore non ancora avviato")
    if not backup.configurato():
        raise HTTPException(status_code=400, detail="backup non configurato")
    # Non backup_once: quella controlla se e' l'ora della copia notturna e, se
    # oggi e' gia' stata fatta, non fa niente. Premuto a mano vuol dire adesso.
    esito = await engine.backup_adesso()
    if not esito.get("ok"):
        raise HTTPException(status_code=502, detail=esito.get("motivo") or "backup non riuscito")
    return backup.riepilogo()


@app.post("/api/restore", dependencies=[Depends(require_token)])
async def restore(payload: dict | None = None) -> dict:
    """Rimette il database dell'ultimo backup preso da GitHub.

    Come per la pulizia serve una conferma esplicita: sovrascrive tutto quello
    che c'e' adesso, e non deve poter partire da una richiesta ripetuta.
    """
    if not (payload or {}).get("confirm"):
        raise HTTPException(status_code=400, detail="conferma mancante")
    if engine is None:
        raise HTTPException(status_code=503, detail="motore non ancora avviato")
    if not backup.configurato():
        raise HTTPException(status_code=400, detail="backup non configurato")
    esito = await engine.restore()
    if not esito.get("ok"):
        raise HTTPException(status_code=409, detail=esito.get("motivo", "ripristino fallito"))
    return esito


@app.post("/api/wipe", dependencies=[Depends(require_token)])
async def wipe(payload: dict | None = None) -> dict:
    """Svuota i dati raccolti. Le soglie di Setup restano dove sono.

    La conferma esplicita nel corpo della richiesta e' voluta: e' l'unica
    chiamata che distrugge dati, e non deve poter partire per sbaglio da un
    link aperto per caso o da una richiesta ripetuta dal browser.
    """
    if not (payload or {}).get("confirm"):
        raise HTTPException(status_code=400, detail="conferma mancante")
    if engine is None:
        raise HTTPException(status_code=503, detail="motore non ancora avviato")
    return await engine.wipe()


@app.get("/api/config", dependencies=[Depends(require_token)])
async def config() -> dict:
    return settings.as_dict()


@app.get("/")
async def index() -> FileResponse:
    """Serve la dashboard dicendo al browser di non conservarla.

    Senza questo, l'app installata sulla schermata Home continua a mostrare la
    copia scaricata la prima volta: dopo un aggiornamento bisognerebbe svuotare
    la cache o reinstallare l'icona per vedere le novita'. La pagina pesa una
    ventina di KB, quindi riscaricarla a ogni apertura non costa nulla.
    """
    return FileResponse(
        WEB_DIR / "index.html",
        headers={"Cache-Control": "no-store, must-revalidate", "Pragma": "no-cache"},
    )


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})


if WEB_DIR.exists():
    app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")


def run() -> None:
    import uvicorn

    setup_logging()
    uvicorn.run(
        "memescan.api:app",
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    run()
