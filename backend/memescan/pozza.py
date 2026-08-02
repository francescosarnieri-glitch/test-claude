"""Chi puo' riprendersi la liquidita', e quindi portarti via tutto.

E' la domanda che le altre verifiche non toccano. Il controllo sulla tassa di
vendita dice se puoi uscire; questo dice se ci sara' ancora qualcosa da cui
uscire. Sono le due meta' della stessa paura.

Fino a qui si sapeva rispondere solo sulle pozze in stile v2, dove chi mette
la liquidita' riceve una ricevuta uguale per tutti e basta contare quante ne
sono state bruciate. Ma su questa chain le v2 sono 17 su 84: le altre sono v3
e v4, e li' la ricevuta non esiste piu'.

Come si fa lo stesso:

- **v3.** La pozza ha un indirizzo suo ed emette `Mint` a ogni versamento. Il
  campo `owner` di quell'evento e' chi detiene la posizione. Quasi sempre e'
  un contratto gestore, e allora il proprietario vero e' chi ha in mano l'NFT
  della posizione: si trova incrociando la stessa transazione con l'evento
  `IncreaseLiquidity` del gestore, che porta il numero della posizione, e
  chiedendogli `ownerOf`. Nessun indirizzo cablato: il gestore si scopre dal
  primo evento.
- **v4.** La pozza non ha un indirizzo, e' un numero dentro un contratto
  unico. I versamenti si trovano lo stesso, per numero, con `ModifyLiquidity`,
  e chi li ha fatti e' il `sender`. Quei gestori non emettono
  `IncreaseLiquidity`, ma la posizione resta un ERC-721: il numero si legge
  dalla nascita dell'NFT - un `Transfer` che parte dall'indirizzo zero nella
  stessa transazione - e da li' si torna a `ownerOf`. Su qualche gestore su
  misura (DopplerHookInitializer e simili) non si arriva in fondo, e allora si
  dice solo che sta in un contratto invece di indovinare.

Costa poco dove serve. Le monete che questo scanner guarda hanno ore di vita e
una o due posizioni: le pozze da settecento posizioni sono quelle vecchie e
famose, che non sono candidate a niente.

Tre risposte possibili, e la differenza fra le ultime due conta:

- **bruciata**: la posizione e' a un indirizzo morto. Non la ritira piu'
  nessuno, mai.
- **in un contratto**: sta in un blocca-liquidita' (su questa chain ne gira
  uno che si chiama PonsLaunchLocker). E' molto meglio di un portafoglio, ma
  non e' per sempre: un blocco ha una scadenza, e leggerla vorrebbe dire
  conoscere il contratto uno per uno.
- **in un portafoglio**: una persona puo' ritirarla quando vuole, oggi.

Non e' un divieto. Serve una moneta con la pozza libera per chi entra ed esce
in venti minuti, e chi lo fa lo deve sapere - non gli va tolta.
"""

from __future__ import annotations

from dataclasses import dataclass

from .chain import BURN_ADDRESSES, TOPIC_TRANSFER, event_topic, get_rpc, selector
from .util import get_logger

log = get_logger("memescan.pozza")

TOPIC_MINT_V3 = event_topic("Mint(address,address,int24,int24,uint128,uint256,uint256)")
TOPIC_INCREASE = event_topic("IncreaseLiquidity(uint256,uint128,uint256,uint256)")
TOPIC_MODIFY_V4 = event_topic("ModifyLiquidity(bytes32,address,int24,int24,int256,bytes32)")
SEL_OWNER_OF = selector("ownerOf(uint256)")

BRUCIATA = "bruciata"
IN_CONTRATTO = "in_contratto"
IN_PORTAFOGLIO = "in_portafoglio"
SCONOSCIUTA = "sconosciuta"

#: Oltre questo numero di versamenti la pozza non e' un lancio: e' una coppia
#: vecchia con anni di movimenti. Non vale le chiamate per esaminarla.
MAX_VERSAMENTI = 60

#: Un id di pozza v4 e' una parola da 32 byte, un indirizzo ne ha 20.
LUNGHEZZA_INDIRIZZO = 42


@dataclass(slots=True)
class Custodia:
    dove: str = SCONOSCIUTA
    proprietario: str = ""
    #: Da dove viene la risposta: serve a spiegarla e a non fidarsi troppo.
    via: str = ""

    @property
    def ritirabile(self) -> bool:
        """Vero solo quando qualcuno puo' portarla via adesso."""
        return self.dove == IN_PORTAFOGLIO

    @property
    def bloccata(self) -> bool:
        return self.dove in (BRUCIATA, IN_CONTRATTO)


async def _e_contratto(rpc, indirizzo: str) -> bool:
    code = await rpc.get_code(indirizzo)
    return bool(code) and code != "0x"


async def _classifica_proprietario(rpc, indirizzo: str, via: str) -> Custodia:
    if indirizzo.lower() in BURN_ADDRESSES:
        return Custodia(BRUCIATA, indirizzo, via)
    if await _e_contratto(rpc, indirizzo):
        return Custodia(IN_CONTRATTO, indirizzo, via)
    return Custodia(IN_PORTAFOGLIO, indirizzo, via)


async def _proprietario_della_posizione(rpc, gestore: str, versamenti: list[dict]) -> str:
    """Risale dall'NFT della posizione a chi lo possiede davvero.

    Il gestore non e' cablato: e' quello che compare come `owner` nell'evento
    della pozza. Da li' si cerca, nelle stesse transazioni, l'evento che porta
    il numero della posizione, e a quel numero si chiede chi ne e' padrone.
    """
    blocchi = sorted(int(v["blockNumber"], 16) for v in versamenti)
    transazioni = {v["transactionHash"] for v in versamenti}
    logs = await rpc.call("eth_getLogs", [{
        "address": gestore,
        "topics": [TOPIC_INCREASE],
        "fromBlock": hex(blocchi[0]),
        "toBlock": hex(blocchi[-1]),
    }]) or []

    numeri = [
        l["topics"][1] for l in logs
        if l.get("transactionHash") in transazioni and len(l.get("topics") or []) > 1
    ]
    if not numeri:
        # I gestori v4 non emettono IncreaseLiquidity, ma la posizione resta un
        # ERC-721: il numero si trova nella creazione dell'NFT, cioe' un
        # Transfer che parte dall'indirizzo zero, nella stessa transazione.
        creazioni = await rpc.call("eth_getLogs", [{
            "address": gestore,
            "topics": [TOPIC_TRANSFER, "0x" + "0" * 64],
            "fromBlock": hex(blocchi[0]),
            "toBlock": hex(blocchi[-1]),
        }]) or []
        numeri = [
            l["topics"][3] for l in creazioni
            if l.get("transactionHash") in transazioni and len(l.get("topics") or []) > 3
        ]
    if not numeri:
        return ""
    risposta = await rpc.eth_call(gestore, SEL_OWNER_OF + numeri[0].removeprefix("0x"))
    if not risposta or risposta == "0x" or len(risposta) < 42:
        return ""
    return "0x" + risposta[-40:]


async def _custodia_v3(rpc, pair: str) -> Custodia:
    versamenti = await rpc.call("eth_getLogs", [{
        "address": pair, "fromBlock": "0x0", "toBlock": "latest",
        "topics": [TOPIC_MINT_V3],
    }])
    if not versamenti:
        return Custodia(SCONOSCIUTA, via="nessun versamento leggibile")
    if len(versamenti) > MAX_VERSAMENTI:
        return Custodia(SCONOSCIUTA, via="pozza troppo movimentata per essere un lancio")

    # L'ultimo versamento e' quello che conta: se la liquidita' e' stata
    # rimessa dopo, e' li' che sta adesso.
    owner = "0x" + versamenti[-1]["topics"][1][-40:]
    if not await _e_contratto(rpc, owner):
        # Nessun gestore di mezzo: la posizione e' direttamente sua.
        return await _classifica_proprietario(rpc, owner, "posizione intestata a lui")

    vero = await _proprietario_della_posizione(rpc, owner, versamenti)
    if not vero:
        # Il gestore c'e' ma non dice di chi sia: resta un contratto in mezzo,
        # che e' comunque meglio di un portafoglio ma non e' una garanzia.
        return Custodia(IN_CONTRATTO, owner, "gestore di posizioni non interrogabile")
    return await _classifica_proprietario(rpc, vero, "proprietario dell'NFT della posizione")


async def _custodia_v4(rpc, pool_id: str) -> Custodia:
    versamenti = await rpc.call("eth_getLogs", [{
        "fromBlock": "0x0", "toBlock": "latest",
        "topics": [TOPIC_MODIFY_V4, pool_id],
    }])
    if not versamenti:
        return Custodia(SCONOSCIUTA, via="nessun versamento leggibile")
    gestore = "0x" + versamenti[-1]["topics"][2][-40:]
    if not await _e_contratto(rpc, gestore):
        return await _classifica_proprietario(rpc, gestore, "ha versato di persona")
    vero = await _proprietario_della_posizione(rpc, gestore, versamenti)
    if not vero:
        return Custodia(IN_CONTRATTO, gestore, "gestore di posizioni non interrogabile")
    return await _classifica_proprietario(rpc, vero, "proprietario dell'NFT della posizione")


async def controlla(pair: str, lp_bruciata_pct: float = -1.0) -> Custodia:
    """Dice dove sta la liquidita' di questa pozza, qualunque sia il suo stile.

    `lp_bruciata_pct` e' il risultato del controllo v2, se e' stato possibile
    farlo: li' la risposta c'e' gia' e non serve leggere nessun evento.
    """
    if not pair:
        return Custodia(SCONOSCIUTA, via="nessuna pozza")

    if lp_bruciata_pct >= 0:
        if lp_bruciata_pct >= 95:
            return Custodia(BRUCIATA, via="ricevute della pozza bruciate")
        if lp_bruciata_pct >= 50:
            return Custodia(IN_CONTRATTO, via=f"{lp_bruciata_pct:.0f}% delle ricevute bruciate")
        return Custodia(IN_PORTAFOGLIO, via=f"solo il {lp_bruciata_pct:.0f}% delle ricevute e' bruciato")

    rpc = get_rpc()
    try:
        if len(pair) > LUNGHEZZA_INDIRIZZO:
            return await _custodia_v4(rpc, pair)
        return await _custodia_v3(rpc, pair)
    except Exception as exc:  # pragma: no cover - dipende dalla rete
        log.debug("custodia della pozza non leggibile per %s: %s", pair[:12], exc)
        return Custodia(SCONOSCIUTA, via="lettura non riuscita")
