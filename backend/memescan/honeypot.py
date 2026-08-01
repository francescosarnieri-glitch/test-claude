"""Controlla che si possa vendere, e a che prezzo.

E' l'unica truffa che porta via tutto invece di una parte. Chi entra e esce in
venti minuti puo' convivere con una moneta che poi muore: ci ha gia'
guadagnato sopra. Non puo' convivere con una moneta che non lo lascia uscire.

Il caso che ha fatto nascere questo modulo: $lambo, tassa in acquisto 0% e
tassa in vendita 100%. Compri quanto vuoi, e quando vendi il contratto si
prende tutto. Il nostro controllo di sicurezza la dava "accettabile", perche'
guardava se *esistesse* una funzione per cambiare le tasse e non quanto
valessero davvero.

Come si misura sul serio. Chiedere al contratto quanto trattiene non basta:
su questa chain nessuna delle monete provate lo dichiara, e chi truffa non ha
nessun motivo di dirlo. L'unico modo onesto e' vendere per finta e contare
quanto arriva davvero alla pozza:

1. si prende il piu' grosso fra chi possiede i token e non e' un contratto;
2. per la durata di una singola simulazione si mette del codice nostro dentro
   quell'indirizzo (state override: niente firme, niente spese, niente che
   tocchi la chain);
3. quel codice legge il saldo della pozza, manda i token, rilegge il saldo;
4. la differenza fra quanto e' partito e quanto e' arrivato **e'** la tassa.

Zero interpretazioni: e' il numero che vedresti sul conto.

Le due trappole in cui si cade misurando, entrambe viste dal vivo mentre
questo modulo veniva scritto, ed entrambe fatali perche' fanno accusare una
moneta onesta:

- **il saldo dell'explorer e' vecchio.** Un indirizzo elencato fra gli holder
  poteva aver gia' venduto tutto: il contratto rispondeva "saldo
  insufficiente" e sembrava un rifiuto a vendere. Il saldo si rilegge sempre
  dalla chain prima di simulare.
- **esiste un tetto per transazione.** Provare a vendere tutto in un colpo
  viene rifiutato da tante monete perfettamente sane. Si prova con una fetta
  piccola, e se anche quella viene rifiutata si scende ancora: solo se non
  passa nemmeno un importo minuscolo la moneta e' davvero chiusa.

Cosa NON copre: chi lascia vendere oggi puo' chiudere domani (per quello resta
il controllo sulle funzioni modificabili), e chi guarda l'indirizzo puo'
lasciar uscire noi e non te. Il controllo su Based Bot prima di comprare resta
necessario. Qui si tolgono di mezzo le trappole riconoscibili, non tutte
quelle possibili.
"""

from __future__ import annotations

from dataclasses import dataclass

from .chain import SEL, get_rpc, selector
from .util import get_logger

log = get_logger("memescan.honeypot")

#: Metodi con cui *alcuni* contratti dichiarano la tassa di vendita. Non c'e'
#: uno standard e nessuno e' obbligato a dirlo: vale come ultima spiaggia
#: quando la simulazione non si puo' fare, mai contro quello che si e' misurato.
GETTER_TASSA_VENDITA = (
    selector("sellTax()"),
    selector("_sellTax()"),
    selector("sellFee()"),
    selector("_sellFee()"),
    selector("sellTotalFees()"),
    selector("totalSellFee()"),
    selector("sellTaxPercentage()"),
)

#: Alcuni contratti le esprimono in millesimi o in punti base: un valore di
#: 1000 puo' voler dire 1000% (assurdo) oppure 100%. Si sceglie la scala piu'
#: piccola che dia un valore plausibile invece di gridare al lupo.
SCALE = (1, 10, 100, 1000, 10000)

#: Oltre questa percentuale non e' una commissione, e' un muro.
TASSA_INSOSTENIBILE = 25.0

#: Quanto vendere, come frazione del saldo di chi vende. Si parte da una fetta
#: piccola e si scende: un rifiuto sul grande e un permesso sul piccolo vuol
#: dire "tetto per transazione", non "non si esce".
FRAZIONI = (100, 10_000, 1_000_000)

#: Sotto questa quantita' di unita' grezze la percentuale non e' piu'
#: affidabile: si dice solo se la vendita passa, non quanto costa.
MINIMO_MISURABILE = 10_000

#: Quanti holder provare prima di arrendersi a trovarne uno con saldo vero.
MAX_CANDIDATI = 5

#: Come parla un nodo quando e' il *contratto* ad aver detto di no. Tutto il
#: resto ("invalid argument", "method not found", ...) e' il nodo che si
#: lamenta di noi, non il token che rifiuta di farti uscire. La differenza e'
#: vitale: un RPC di riserva che non accetta gli state override farebbe
#: sembrare ogni moneta una trappola, e lo scanner smetterebbe di segnalare
#: qualsiasi cosa senza dire perche'.
SEGNI_DI_RIFIUTO = ("revert", "invalid opcode", "out of gas", "execution error")


@dataclass(slots=True)
class Verdetto:
    vendibile: bool = True
    tassa_vendita: float = -1.0
    motivo: str = ""
    verificato: bool = False

    @property
    def bloccante(self) -> bool:
        return not self.vendibile


# --------------------------------------------------------------------------
# Il simulatore di vendita
# --------------------------------------------------------------------------

def _parola(numero: int) -> str:
    return f"{numero:064x}"


def _indirizzo(valore: str) -> str:
    """Venti byte nudi, come li vuole PUSH20."""
    return valore.lower().removeprefix("0x").rjust(40, "0")


def _argomento(valore: str) -> str:
    """Indirizzo come argomento ABI: 32 byte con gli zeri davanti."""
    return valore.lower().removeprefix("0x").rjust(64, "0")


def _simulatore(token: str, pair: str, quantita: int) -> str:
    """Il codice che viene messo, per un istante, dentro chi possiede i token.

    Fa tre cose e se ne va: legge il saldo della pozza, prova a mandarci
    `quantita` token, rilegge il saldo. Torna due parole da 32 byte: se il
    trasferimento e' andato a buon fine, e quanto e' arrivato davvero.

    Non ci sono salti ne' condizioni: se il trasferimento viene rifiutato la
    CALL torna zero e il codice prosegue, cosi' anche il rifiuto e' una
    risposta leggibile invece di far fallire tutta la simulazione.
    """
    tok, poz, qta = _indirizzo(token), _indirizzo(pair), _parola(quantita)
    balance_of = "6370a0823160e01b600052" f"73{poz}600452"  # calldata di balanceOf(pozza)
    return "0x" + "".join((
        balance_of,
        f"6020608060246000" f"73{tok}" "5afa50",  # staticcall -> b0 in mem[0x80]
        "60805160e052",                           # mem[0xe0] = b0
        "63a9059cbb60e01b600052" f"73{poz}600452" f"7f{qta}602452",  # transfer(pozza, qta)
        "602060a0604460006000" f"73{tok}" "5af1",  # call, risposta in mem[0xa0]
        "61010052",                                # mem[0x100] = esito della CALL
        balance_of,
        f"602060c060246000" f"73{tok}" "5afa50",   # staticcall -> b1 in mem[0xc0]
        "60e05160c05103" "61012052",               # mem[0x120] = b1 - b0
        "6040610100f3",                            # return mem[0x100..0x140]
    ))


def _leggi_simulazione(esito: str) -> tuple[bool, int]:
    """Spacchetta le due parole tornate dal simulatore."""
    grezzo = (esito or "").removeprefix("0x")
    if len(grezzo) < 128:
        return False, 0
    try:
        return int(grezzo[:64], 16) != 0, int(grezzo[64:128], 16)
    except ValueError:
        return False, 0


# --------------------------------------------------------------------------
# I pezzi del controllo
# --------------------------------------------------------------------------

def _decodifica_uint(risultato: str | None) -> int | None:
    if not risultato or risultato in ("0x", "0x0"):
        return 0 if risultato else None
    try:
        return int(risultato, 16)
    except ValueError:
        return None


def _percentuale(grezzo: int) -> float | None:
    """Riporta a percentuale un numero di scala ignota.

    Una tassa dichiarata puo' essere 5 (5%), 500 (punti base) o 50 (su mille).
    Si sceglie la scala piu' piccola che dia un valore plausibile: meglio
    sottostimare che accusare un contratto onesto.
    """
    for scala in SCALE:
        valore = grezzo / scala
        if valore <= 100:
            return valore
    return None


async def _tassa_dichiarata(rpc, token: str) -> float:
    """Percentuale dichiarata dal contratto, o -1 se non la dichiara."""
    risultati = await rpc.batch([
        ("eth_call", [{"to": token, "data": sel}, "latest"])
        for sel in GETTER_TASSA_VENDITA
    ])
    peggiore = -1.0
    for risultato in risultati:
        grezzo = _decodifica_uint(risultato)
        if not grezzo:
            continue
        valore = _percentuale(grezzo)
        if valore is not None:
            peggiore = max(peggiore, valore)
    return peggiore


async def _venditore_con_saldo(rpc, token: str, candidati) -> tuple[str, int]:
    """Il primo fra i candidati che possiede davvero qualcosa, adesso.

    L'explorer elenca chi *ha avuto* i token; il saldo va riletto dalla chain,
    altrimenti si simula una vendita da un portafoglio vuoto e il "saldo
    insufficiente" sembra un rifiuto a vendere.
    """
    validi = [c for c in candidati if c][:MAX_CANDIDATI]
    if not validi:
        return "", 0
    risultati = await rpc.batch([
        ("eth_call", [{"to": token, "data": SEL["balanceOf"] + _argomento(c)}, "latest"])
        for c in validi
    ])
    for indirizzo, risultato in zip(validi, risultati):
        saldo = _decodifica_uint(risultato) or 0
        if saldo > 0:
            return indirizzo, saldo
    return "", 0


def _e_un_rifiuto(messaggio: str) -> bool:
    """Distingue "il contratto ha detto di no" da "il nodo non ci sta capendo"."""
    testo = (messaggio or "").lower()
    return any(segno in testo for segno in SEGNI_DI_RIFIUTO)


async def _misura_vendita(
    rpc, token: str, pair: str, venditore: str, saldo: int
) -> tuple[bool | None, float | None]:
    """Vende per finta, dal grande al piccolo.

    Ritorna (vendibile, tassa). `vendibile` a None vuol dire "non si e' potuto
    sapere": la rete non ha risposto, e su un dubbio non si accusa. `tassa` a
    None vuol dire che si e' potuto verificare l'uscita ma non misurarne il
    costo, perche' l'importo passato era troppo piccolo per fare percentuali.
    """
    for divisore in FRAZIONI:
        quantita = max(saldo // divisore, 1)
        riuscita, esito = await rpc.eth_call_esito(
            venditore, "0x", sender=venditore,
            codice=_simulatore(token, pair, quantita),
        )
        if not riuscita:
            if esito == "rpc_muto" or not _e_un_rifiuto(esito):
                # La rete tace, o il nodo si sta lamentando di noi: in nessuno
                # dei due casi il contratto ha detto di no.
                return None, None
            # Il contratto ha rifiutato *questo* importo: si riprova piu'
            # piccolo, perche' potrebbe essere solo un tetto per transazione.
            continue

        andata_a_buon_fine, arrivato = _leggi_simulazione(esito)
        if not andata_a_buon_fine or arrivato <= 0:
            # Rifiutato a questo importo: puo' essere un tetto, si scende.
            continue

        if quantita < MINIMO_MISURABILE:
            return True, None
        # Le monete a riflessione possono far arrivare piu' del mandato: e'
        # un regalo, non una tassa negativa.
        return True, max(0.0, 100.0 * (1 - arrivato / quantita))

    return False, None


# --------------------------------------------------------------------------

async def controlla(token: str, pair: str = "", venditori=()) -> Verdetto:
    """Dice se il token si puo' vendere e quanto costa venderlo.

    `venditori` sono gli holder candidati a fare da cavia, dal piu' grosso in
    giu': devono essere persone e non contratti, perche' pozze e router hanno
    spesso permessi che a te non verrebbero dati.
    """
    rpc = get_rpc()
    verdetto = Verdetto()

    venditore, saldo = ("", 0)
    if pair:
        venditore, saldo = await _venditore_con_saldo(rpc, token, venditori)

    if venditore:
        vendibile, misurata = await _misura_vendita(rpc, token, pair, venditore, saldo)
        if vendibile is False:
            verdetto.vendibile = False
            verdetto.verificato = True
            verdetto.tassa_vendita = 100.0
            verdetto.motivo = "il contratto non lascia mandare i token alla pozza"
            log.info("%s: %s", token[:12], verdetto.motivo)
            return verdetto
        if vendibile and misurata is not None:
            # Misurato: cosa succede davvero. Non serve chiedere al contratto
            # cosa dichiara, e comunque non gli si crederebbe.
            verdetto.verificato = True
            verdetto.tassa_vendita = misurata
            if misurata >= TASSA_INSOSTENIBILE:
                verdetto.vendibile = False
                verdetto.motivo = f"vendendo se ne prende il {misurata:.0f}%"
                log.info("%s: %s", token[:12], verdetto.motivo)
            return verdetto
        # Si e' visto che l'uscita non e' murata, ma non quanto costa.
        verdetto.verificato = bool(vendibile)

    # Ultima spiaggia: quello che il contratto dichiara di trattenere. Vale
    # poco (quasi nessuno lo dichiara, e chi truffa non ha motivo di farlo) ma
    # e' meglio di niente quando non c'e' modo di simulare.
    dichiarata = await _tassa_dichiarata(rpc, token)
    if dichiarata >= 0:
        verdetto.tassa_vendita = dichiarata
        if dichiarata >= TASSA_INSOSTENIBILE:
            verdetto.vendibile = False
            verdetto.motivo = f"tassa di vendita dichiarata al {dichiarata:.0f}%"
            log.info("%s: %s", token[:12], verdetto.motivo)
    return verdetto
