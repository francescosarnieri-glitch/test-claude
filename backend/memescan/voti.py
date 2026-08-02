"""Quanto vale davvero un portafoglio: cosa ha preso, e com'e' andata.

Il voto di prima misurava una cosa diversa da quella che sembrava. Contava su
quante monete «andate bene» un indirizzo fosse arrivato nei primi quindici
minuti, e aveva due difetti che lo rendevano inservibile proprio per la
domanda che deve risolvere - quale portafoglio conviene copiare.

Il primo e' che il confronto era truccato. I portafogli trovati dalla ricerca
automatica sono stati scelti *perche'* comparivano fra i primi acquirenti: e'
il test che li ha selezionati. Chiedere loro quante volte sono arrivati presto
e' come chiedere ai vincitori di una gara quante gare hanno vinto.

Il secondo e' che il test era troppo debole per distinguere chiunque. Un
indirizzo che compra dieci monete al giorno, su una chain dove ne nascono
decine, ha una probabilita' bassissima di capitare proprio su una delle poche
monete esaminate e proprio nel primo quarto d'ora. Zero era il risultato
atteso da chiunque, campione o incapace.

Qui si misura un'altra cosa, e senza circolarita': **le monete che sono
finite in quel portafoglio, come sono andate dopo**. Non conta come ci sono
arrivate - comprate o consegnate da un bot che opera per conto suo - perche'
la domanda e' se quelle scelte valevano, non chi ha premuto il tasto.

Cosa NON sa dire, ed e' scritto accanto al numero invece che nascosto:

- **le monete troppo vecchie.** Lo storico dei prezzi copre una decina di
  giorni: prima di li' non si sa quanto valesse una moneta quando e' arrivata,
  e quelle restano fuori dal conto.
- **le monete che non abbiamo mai incrociato**, di cui non conosciamo la
  pozza. Anche quelle restano fuori.

Per questo il denominatore viaggia sempre insieme al voto. «8 su 12» dice
qualcosa; «8» da solo lascerebbe credere a una completezza che non c'e'.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .util import get_logger

log = get_logger("memescan.voti")

#: Da qui in su una moneta e' andata bene. Non e' il raddoppio: su questa chain
#: quasi niente raddoppia, e un metro che non seleziona mai non seleziona.
SOGLIA_ANDATA_BENE = 1.3

#: Quante monete guardare per portafoglio. Le piu' recenti: sono le uniche di
#: cui lo storico dei prezzi arriva abbastanza indietro.
MAX_TOKEN = 25


@dataclass(slots=True)
class Voto:
    valutate: int = 0
    andate_bene: int = 0
    picco_medio: float = 0.0
    #: Entrate che non si e' potuto giudicare, e perche'. Servono a spiegare un
    #: denominatore piccolo invece di lasciarlo sembrare un giudizio severo.
    senza_storico: int = 0
    senza_pozza: int = 0
    esempi: list[dict] = field(default_factory=list)

    @property
    def quota(self) -> float:
        return self.andate_bene / self.valutate if self.valutate else 0.0


def _prezzo_al(candele: list[list], istante: int) -> float:
    """Chiusura della candela che contiene quel momento.

    Le candele arrivano dalla piu' recente alla piu' vecchia. Si cerca la prima
    che non sia successiva all'istante: e' quella in cui il token e' arrivato.
    """
    for riga in candele:
        if riga[0] <= istante:
            return float(riga[4] or 0)
    return 0.0


def _massimo_dopo(candele: list[list], istante: int) -> float:
    massimi = [float(r[2] or 0) for r in candele if r[0] >= istante]
    return max(massimi) if massimi else 0.0


async def calcola(store, geckoterminal, wallet: str, max_token: int = MAX_TOKEN) -> Voto:
    """Il voto di un portafoglio, con scritto su cosa e' stato calcolato."""
    voto = Voto()
    entrate = store.token_entrati(wallet, limite=max_token)
    if not entrate:
        return voto

    picchi: list[float] = []
    for riga in entrate:
        pozza = (riga.get("pair_address") or "").strip()
        if not pozza or len(pozza) != 42:
            # Senza pozza non c'e' prezzo. Le v4 hanno un id al posto
            # dell'indirizzo e lo storico non si puo' chiedere.
            voto.senza_pozza += 1
            continue
        try:
            candele = await geckoterminal.candele(pozza)
        except Exception as exc:  # pragma: no cover - dipende dalla rete
            log.debug("storico non leggibile per %s: %s", pozza[:12], exc)
            candele = []
        entrato = int(riga.get("entrato") or 0)
        ingresso = _prezzo_al(candele, entrato)
        picco = _massimo_dopo(candele, entrato)
        if ingresso <= 0 or picco <= 0:
            voto.senza_storico += 1
            continue

        multiplo = picco / ingresso
        voto.valutate += 1
        picchi.append(multiplo)
        if multiplo >= SOGLIA_ANDATA_BENE:
            voto.andate_bene += 1
        if len(voto.esempi) < 5:
            voto.esempi.append({
                "symbol": riga.get("symbol") or "",
                "multiplo": round(multiplo, 2),
                "entrato": entrato,
            })

    voto.picco_medio = sum(picchi) / len(picchi) if picchi else 0.0
    return voto
