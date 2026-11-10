"""Genera modelli illustrativi di busta paga (fac-simile) con anagrafica vuota.

Uso:  python3 genera.py   -> scrive buste_paga_facsimile.html
"""
from pathlib import Path

# --------------------------------------------------------------------------
# Dati economici (anagrafica, azienda e codici identificativi lasciati vuoti)
# --------------------------------------------------------------------------
CONTRATTO = dict(
    qual="22", qualifica="OPERAIO", livello="6^", cod_liv="6",
    gg_contr="26", ore_contr="173,00", retr_tabel="1.237,88",
    retr_oraria="8,36925", retr_giorn="55,68", supe_riass="210,00",
    retr_fatto="1.447,88",
)

OTTOBRE = dict(
    mese="OTTOBRE", anno="2017", contratto=CONTRATTO,
    voci=[
        ("8002", "LAVORO ORDINARIO (giorni)", "26,00", "55,68769", "1.447,88", "", ""),
        ("8725", "ASSENZE (giorni/ore)", "13,00", "-55,68769", "-723,94", "", ""),
        ("8702", "1^GIORNO INFORTUNIO", "1,00", "", "55,69", "", ""),
        ("8705", "CARENZA INFORTUNIO", "3,00", "", "167,06", "", ""),
        ("8703", "INFORTUNIO C/DITTA", "9,00", "", "45,20", "", ""),
        ("8258", "CREDITO DL 66/14 EROGATO", "", "", "81,53", "", ""),
        ("9117", "RATA ADDIZ.REGIONALE A.P.", "", "", "", "11,74", ""),
        ("8391", "BUONI PASTO", "11,00", "5,29000", "", "", "58,19"),
        ("9999", "CTR C/AZIE SAN.ARTI", "", "", "", "", "10,42"),
    ],
    tot=dict(lordo="991,89", imp_soc="992,00", c1="91,16", c4="1,49", tot_soc="92,65",
             imp_irpef="901,52", irpef_lorda="207,35", tot_detr="127,65", tot_irpef="79,70",
             arr_prec="0,22", trat_corpo="11,74", tot_tratt="184,31", arr_att="0,89",
             netto="890,00"),
    ferie=dict(fer_ap="6,00", fer_mat="16,60", fer_god="18,00", fer_res="4,60",
               per_ap="7,98", per_mat="13,34", per_god="", per_res="21,32",
               fes_ap="16,02", fes_mat="26,66", fes_god="", fes_res="42,68"),
    stat=dict(q="1", sett="5", ore_inps="173,00", gg_inps="26,00", gg_min="17",
              ore_inail="96,00", gg_inail="12", imp_inail="992,00", tfr="102,29",
              detr_lav="127,65"),
    progr=dict(inail="13857,00", soc="13857,00", contr="1294,22", oneri="",
               irpef="12584,33", lorda="2946,30", detr="1327,85", pagata="1618,45"),
    # giorno -> (ore lavorate, sigla, quantita)
    cal={1: ("", "R", ""), 2: ("8,00", "", ""), 3: ("", "1G", "8,00"),
         4: ("", "CI", "8,00"), 5: ("", "CI", "8,00"), 6: ("", "CI", "8,00"),
         7: ("", "Z", ""), 8: ("", "R", ""),
         **{d: ("", "IN", "8,00") for d in (9, 10, 11, 12, 13, 16, 17)},
         14: ("", "Z", ""), 15: ("", "R", ""),
         **{d: ("8,00", "", "") for d in (18, 19, 20, 23, 24, 25, 26, 27, 30, 31)},
         21: ("", "Z", ""), 22: ("", "R", ""), 28: ("", "Z", ""), 29: ("", "R", "")},
    legenda=[("R", "GIORNO DI RIPOSO"), ("1G", "1^GIORNO INFORTUNIO OP.MENS."),
             ("CI", "CARENZA INFORTUNIO MENSILIZZ"), ("Z", "GIORNO ZERO ORE"),
             ("IN", "INFORTUNIO OPERAIO MENS.GG")],
)

NOVEMBRE = dict(
    mese="NOVEMBRE", anno="2017", contratto=CONTRATTO,
    voci=[
        ("8002", "LAVORO ORDINARIO (giorni)", "26,00", "55,68769", "1.447,88", "", ""),
        ("8124", "FESTIVITA' non goduta", "1,00", "55,68769", "55,69", "", ""),
        ("8258", "CREDITO DL 66/14 EROGATO", "", "", "78,90", "", ""),
        ("9117", "RATA ADDIZ.REGIONALE A.P.", "", "", "", "11,73", ""),
        ("8391", "BUONI PASTO", "22,00", "5,29000", "", "", "116,38"),
        ("9999", "CTR C/AZIE SAN.ARTI", "", "", "", "", "10,42"),
    ],
    tot=dict(lordo="1.503,57", imp_soc="1.504,00", c1="138,22", c4="2,26", tot_soc="140,48",
             imp_irpef="1.365,37", irpef_lorda="322,50", tot_detr="123,06", tot_irpef="199,44",
             arr_prec="0,89", trat_corpo="11,73", tot_tratt="352,54", arr_att="0,07",
             netto="1.230,00"),
    ferie=dict(fer_ap="6,00", fer_mat="18,26", fer_god="18,00", fer_res="6,26",
               per_ap="7,98", per_mat="14,67", per_god="", per_res="22,65",
               fes_ap="16,02", fes_mat="29,33", fes_god="", fes_res="45,35"),
    stat=dict(q="1", sett="5", ore_inps="173,00", gg_inps="26,00", gg_min="26",
              ore_inail="168,00", gg_inail="21", imp_inail="1.504,00", tfr="103,85",
              detr_lav="123,06"),
    progr=dict(inail="15361,00", soc="15361,00", contr="1434,70", oneri="",
               irpef="13949,70", lorda="3268,80", detr="1450,91", pagata="1817,89"),
    cal={1: ("", "F", ""),
         **{d: ("8,00", "", "") for d in (2, 3, 6, 7, 8, 9, 10, 13, 14, 15, 16, 17,
                                           20, 21, 22, 23, 24, 27, 28, 29, 30)},
         **{d: ("", "Z", "") for d in (4, 11, 18, 25)},
         **{d: ("", "R", "") for d in (5, 12, 19, 26)}},
    legenda=[("F", "GIORNO FESTIVO"), ("Z", "GIORNO ZERO ORE"), ("R", "GIORNO DI RIPOSO")],
)


# --------------------------------------------------------------------------
# Motore di calcolo (regole 2017 ricostruite dalle buste originali: le
# formule riproducono al centesimo i valori di ottobre e novembre originali)
# --------------------------------------------------------------------------
import math

def r2(x):
    return math.floor(x * 100 + 0.5 + 1e-9) / 100

def eur(x, sep=True):
    s = f"{x:,.2f}" if sep else f"{x:.2f}"
    return s.replace(",", "X").replace(".", ",").replace("X", ".")

def irpef_lorda(imp):
    """Scaglioni IRPEF 2017 rapportati a 13 mensilita."""
    scaglioni = [(15000, .23), (28000, .27), (55000, .38), (75000, .41), (1e12, .43)]
    prev = tot = 0
    for lim, aliq in scaglioni:
        lim /= 13
        if imp > prev:
            tot += (min(imp, lim) - prev) * aliq
        prev = lim
    return r2(tot)

def detrazione_lav_dip(R, giorni):
    if R <= 8000:
        a = 1880
    elif R <= 28000:
        a = 978 + 902 * (28000 - R) / 20000
    elif R <= 55000:
        a = 978 * (55000 - R) / 27000
    else:
        a = 0
    return r2(a * giorni / 365)

def credito_dl66(R, giorni):
    a = 960 if R <= 24000 else (960 * (26000 - R) / 2000 if R < 26000 else 0)
    return r2(a * giorni / 365)

def calcola(lordo, progr_irpef_prec, mese_n, giorni, addiz, arr_prec):
    imp = round(lordo)                       # imponibile contributivo arrotondato all'euro
    c1 = r2(imp * .0919)                     # contributo IVS dipendente 9,19%
    c4 = r2(imp * .0015)                     # contributo aggiuntivo 0,15%
    soc = r2(c1 + c4)
    imp_irpef = r2(lordo - soc + 2.28)       # +2,28 quota imponibile fissa presente negli originali
    il = irpef_lorda(imp_irpef)
    R = (progr_irpef_prec + imp_irpef) / mese_n * 13   # reddito annuo presunto
    d = detrazione_lav_dip(R, giorni)
    ti = r2(il - d)
    cr = credito_dl66(R, giorni)
    tt = r2(soc + ti + addiz + arr_prec)
    grezzo = r2(lordo - tt + cr)
    netto = math.ceil(grezzo - 1e-9)
    return dict(imp=imp, c1=c1, c4=c4, soc=soc, imp_irpef=imp_irpef, il=il, d=d, ti=ti,
                cr=cr, tt=tt, netto=netto, arr_att=r2(netto - grezzo))

def risolvi_integrazione(base, prog, mese_n, giorni, addiz, arr_prec, netto_obiettivo):
    """Trova l'integrazione lorda minima (al centesimo) che porta al netto voluto."""
    best = None
    for cent in range(0, 500000):
        x = cent / 100
        r = calcola(base + x, prog, mese_n, giorni, addiz, arr_prec)
        if r["netto"] == netto_obiettivo and (best is None or r["arr_att"] < best[1]["arr_att"]):
            best = (x, r)
            if r["arr_att"] == 0:
                break
        elif r["netto"] > netto_obiettivo:
            break
    return best

# Progressivi a fine settembre (ricavati dagli originali)
PROGR_SETT = dict(inail=12865.00, soc=12865.00, contr=1201.57, irpef=11682.81,
                  lorda=2738.95, detr=1200.20, pagata=1538.75)

def simula(b, base_lordo, prog, mese_n, giorni, addiz, arr_prec, netto, tfr_base):
    x, r = risolvi_integrazione(base_lordo, prog["irpef"], mese_n, giorni, addiz, arr_prec, netto)
    b = dict(b)
    voci = [v for v in b["voci"] if v[0] != "8258"]
    pos = next(i for i, v in enumerate(voci) if v[0] == "9117")
    voci.insert(pos, ("8258", "CREDITO DL 66/14 EROGATO", "", "", eur(r["cr"]), "", ""))
    voci.insert(pos, ("8030", "INTEGRAZIONE RETRIBUTIVA", "", "", eur(x), "", ""))
    b["voci"] = voci
    b["tot"] = dict(lordo=eur(base_lordo + x), imp_soc=eur(r["imp"]), c1=eur(r["c1"]), c4=eur(r["c4"]),
                    tot_soc=eur(r["soc"]), imp_irpef=eur(r["imp_irpef"]), irpef_lorda=eur(r["il"]),
                    tot_detr=eur(r["d"]), tot_irpef=eur(r["ti"]), arr_prec=eur(arr_prec),
                    trat_corpo=eur(addiz), tot_tratt=eur(r["tt"]), arr_att=eur(r["arr_att"]),
                    netto=eur(r["netto"]))
    st = dict(b["stat"]); st.update(imp_inail=eur(r["imp"]), detr_lav=eur(r["d"]),
                                     tfr=eur(r2(tfr_base + x * (1 / 13.5 - 0.005))))
    b["stat"] = st
    np = dict(inail=prog["inail"] + r["imp"], soc=prog["soc"] + r["imp"], contr=r2(prog["contr"] + r["soc"]),
              irpef=r2(prog["irpef"] + r["imp_irpef"]), lorda=r2(prog["lorda"] + r["il"]),
              detr=r2(prog["detr"] + r["d"]), pagata=r2(prog["pagata"] + r["ti"]))
    b["progr"] = {k: eur(v, False) for k, v in np.items()} | {"oneri": ""}
    return b, np, r, x

OTT_SIM, PROGR_OTT, R_OTT, X_OTT = simula(OTTOBRE, 991.89, PROGR_SETT, 10, 31, 11.74, 0.22, 1560, 102.29)
NOV_SIM, PROGR_NOV, R_NOV, X_NOV = simula(NOVEMBRE, 1503.57, PROGR_OTT, 11, 30, 11.73, R_OTT["arr_att"], 1611, 103.85)


def vuoto():
    """Modello completamente vuoto (solo struttura)."""
    from collections import defaultdict
    d = defaultdict(str)
    return dict(mese="", anno="", contratto=d, voci=[], tot=d, ferie=d, stat=d,
                progr=d, cal={}, legenda=[])


# --------------------------------------------------------------------------
# Rendering HTML
# --------------------------------------------------------------------------
def c(label, value="", w=1, cls=""):
    return (f'<div class="c {cls}" style="flex:{w}"><span class="l">{label}</span>'
            f'<span class="v">{value}</span></div>')


def row(*cells, cls=""):
    return f'<div class="r {cls}">{"".join(cells)}</div>'


def pagina(b, titolo):
    k, t, f, s, p = b["contratto"], b["tot"], b["ferie"], b["stat"], b["progr"]

    testata = f"""
    <div class="top">
      <div class="ditta">
        <div class="lab">Ditta</div>
        <div class="blank"></div><div class="blank"></div>
        <div class="cf">Cod.fiscale : <span class="blank inline"></span></div>
      </div>
      <div class="titolo">
        <div class="t1">PROSPETTO PAGA</div>
        <div class="t2">{titolo}</div>
        <div class="foglio">Foglio N. ____</div>
      </div>
    </div>"""

    anag = "".join([
        row(c("Mese retribuito", f'{b["mese"]} &nbsp; {b["anno"]}', 2.2), c("Cod. azi.", "", .7),
            c("Cod. fil.", "", .6), c("Matr. INPS azienda", "", 1.4), c("Posizione INAIL", "", 1.4),
            c("Codice", "", .7), c("Cognome e nome", "", 3.4), c("Data assunz.", "", 1),
            c("Scad. doc.", "", .8), c("Scatti anz.", "", .8)),
        row(c("Codice fiscale", "", 2.2), c("Comune di residenza", "", 3.1), c("Data di nas.", "", 1),
            c("Data ass. conv.", "", 1), c("Situazione ANF", "", 2.1), c("Data cessazione", "", 1.8),
            c("GG. contr.", k["gg_contr"], .8), c("Ore contr.", k["ore_contr"], .8)),
        row(c("Qual.", k["qual"], .6), c("Qualifica", k["qualifica"], 2.2), c("Centro di costo", "", 3),
            c("Reparto", "", 4.4), c("% P. time", "", .9), c("Cartel.", "", .8),
            c("Livello", k["livello"], .8), c("Cod. liv.", k["cod_liv"], .8)),
    ])

    elementi = f"""
    <div class="r el">
      <div class="side">Voci di tariffa</div>
      <div class="elg">
        {row(c("Att.", "", .4, "tiny"), c("Retr. tabel.", k["retr_tabel"], 1.4), *[c("", "", 1) for _ in range(6)],
             c("Retr. oraria contr.", k["retr_oraria"], 1.6), c("Retr. giorn. contr.", k["retr_giorn"], 1.4))}
        {row(c("Prec.", "", .4, "tiny"), c("", k["retr_tabel"], 1.4), *[c("", "", 1) for _ in range(6)],
             c("", "", 1.6), c("", "", 1.4))}
        {row(c("Att.", "", .4, "tiny"), *[c("", "", 1) for _ in range(6)],
             c("Supe. riass.", k["supe_riass"], 1.4), c("Retr. di fatto", k["retr_fatto"], 1.6), c("", "", 1.4))}
        {row(c("Prec.", "", .4, "tiny"), *[c("", "", 1) for _ in range(6)],
             c("", k["supe_riass"], 1.4), c("", "", 1.6), c("", "", 1.4))}
      </div>
    </div>"""

    righe = "".join(
        f"<tr><td>{a}</td><td class='d'>{d}</td><td>{o}</td><td>{bs}</td>"
        f"<td>{cp}</td><td>{tr}</td><td>{st}</td></tr>"
        for a, d, o, bs, cp, tr, st in b["voci"])
    righe += "<tr class='fill'><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>"
    voci = f"""
    <table class="voci">
      <colgroup><col style="width:7%"><col style="width:31%"><col style="width:9%"><col style="width:11%">
      <col style="width:15%"><col style="width:13%"><col style="width:14%"></colgroup>
      <thead><tr><th>Codice</th><th class="d">Descrizione</th><th>Ore/Giorni</th><th>Base</th>
      <th>Competenze</th><th>Trattenute</th><th>Statistiche</th></tr></thead>
      <tbody>{righe}</tbody>
    </table>"""

    totali = "".join([
        row(c("Totale lordo", t["lordo"], 1.3), c("Impon. contr. soc.", t["imp_soc"], 1.3),
            c("Contributo 1", t["c1"]), c("Contributo 2"), c("Contributo 3"), c("Contributo 4", t["c4"]),
            c("Contributo 5"), c("Tot. contr. soc.", t["tot_soc"], 1.2, "hl")),
        row(c("Imp. T.S. ante 2001", "", 1.3), c("%", "", .4), c("IRPEF", "", .9),
            c("Imp. T.S. TFR 2001"), c("%", "", .4), c("IRPEF netta", "", .9), c("IRPEF su rival."),
            c("IRPEF già pag."), c("Tot. trat. IRPEF T.S.", "", 1.2, "hl")),
        row(c("Imp. T.S.S. arr. A.P.", "", 1.3), c("%", "", .4), c("IRPEF A.P.", "", .9),
            c("Oneri deducibili"), c("Imp. IRPEF", t["imp_irpef"], 1.3), c("IRPEF lorda", t["irpef_lorda"]),
            c("Tot. detr.", t["tot_detr"]), c("Tot. trat. IRPEF", t["tot_irpef"], 1.2, "hl")),
        row(c("Acconto", "", 1.3), c("Rata prestito", "", 1.3), c("Res. prestito", "", 1.3),
            c("Tratt. sindac."), c("Covelco", "", .8), c("Arr. prec.", t["arr_prec"], .8),
            c("Trat. corpo", t["trat_corpo"]), c("Tot. trattenute", t["tot_tratt"], 1.2, "hl")),
        row(c("Conguaglio fine anno cess. rapp.", "", 1.3, "small"), c("Progr. on. ded."),
            c("Progr. imp. IRPEF"), c("Imp. IRPEF annuo"), c("Progr. detr. IRPEF"), c("Progr. rit. IRPEF"),
            c("Addizionale IRPEF"), c("Conguaglio IRPEF +/-", "", 1.2, "hl")),
        row(c("IRPEF erario", "", 1.3), c("Addiz. regionale", "", 1.3), c("Addiz. comunale", "", 1.3),
            c("", "", 1.4), c("Arr. attuale", t["arr_att"]), c("Netto busta", t["netto"], 1.2, "netto")),
    ])

    ferie = "".join([
        row(c("Ferie A.P.", f["fer_ap"]), c("Ferie mat.", f["fer_mat"]), c("Ferie god.", f["fer_god"]),
            c("Ferie res.", f["fer_res"]), c("Perm. A.P.", f["per_ap"]), c("Perm. mat.", f["per_mat"]),
            c("Perm. god.", f["per_god"]), c("Perm. res.", f["per_res"]), c("Rol. A.P."), c("Rol. mat."),
            c("Rol. god."), c("Rol. res.")),
        row(c("Fest. A.P.", f["fes_ap"]), c("Fest. mat.", f["fes_mat"]), c("Fest. god.", f["fes_god"]),
            c("Fest. res.", f["fes_res"]), c("Fless. A.P."), c("Fless. mat."), c("Fless. god."),
            c("Fless. res."), c("B. ore A.P."), c("B. ore mat."), c("B. ore god."), c("B. ore res.")),
    ])

    stat = "".join([
        row(c("Dati statistici", "", 1.1, "hd"), c("Q/INPS", s["q"], .5), c("Pos.", "", .5),
            c("Sett.", s["sett"], .5), c("Ore INPS", s["ore_inps"], .9), c("GG. INPS", s["gg_inps"], .8),
            c("GG. minim.", s["gg_min"], .6), c("Ore INAIL", s["ore_inail"], .8),
            c("GG. INAIL", s["gg_inail"], .6), c("Imponibile INAIL", s["imp_inail"], 1.6),
            c("Imponibili sil./cosi", "", 1.2), c("TFR mese", s["tfr"], 1.4)),
        row(c("Detrazioni spettanti", "", 1.1, "hd"), c("Lavoro dip.", s["detr_lav"], 1.5),
            c("Coniuge", "", .9), c("Figli", "", .8), c("Altri carichi", "", .6),
            c("Oneri", "", 1.4), c("Ulteriore detraz.", "", 1.6), c("Diff. minori", "", 1.2),
            c("Diff. apprendisti", "", 1.4)),
        row(c("Progres. annui", "", 1.1, "hd"), c("Imponibile INAIL", p["inail"], 1.4),
            c("Imp. contrib. soc.", p["soc"], 1.4), c("Contrib. soc.", p["contr"], 1.1),
            c("Oneri deduc.", p["oneri"], .9), c("Imponibile IRPEF", p["irpef"], 1.4),
            c("IRPEF lorda", p["lorda"], 1.1), c("Tot. detr.", p["detr"], 1),
            c("IRPEF pagata", p["pagata"], 1.1)),
        row(c("C/C", "", 2.5), c("Banca accredito", "", 4.5), c("Firma", "", 3)),
    ])

    cal_rows = ""
    for g in range(1, 32):
        ore, sig, q = b["cal"].get(g, ("", "", ""))
        cal_rows += (f"<tr><td class='g'>{g}</td><td>{ore}</td><td class='s'>{sig}</td>"
                     f"<td>{q}</td><td class='s'></td><td></td></tr>")
    leg = "".join(f"<tr><td class='s'>{a}</td><td class='d'>{d}</td></tr>" for a, d in b["legenda"])
    leg += "".join("<tr><td class='s'></td><td class='d'></td></tr>" for _ in range(6 - len(b["legenda"])))
    cal = f"""
    <table class="cal">
      <thead><tr><th></th><th>Ore lavorate</th><th>Sigla</th><th>Quantità</th><th>Sigla</th><th>Quantità</th></tr></thead>
      <tbody>{cal_rows}</tbody>
    </table>
    <table class="leg"><thead><tr><th>Sigla</th><th class="d">Descrizione</th></tr></thead><tbody>{leg}</tbody></table>
    <div class="firma">Firma del dipendente<div class="line"></div></div>"""

    return f"""
  <section class="page">
    <div class="wm">FAC-SIMILE · USO ILLUSTRATIVO</div>
    {testata}
    <div class="box">{anag}{elementi}</div>
    <div class="body">
      <div class="left">
        <div class="box">{voci}</div>
        <div class="box">{totali}</div>
        <div class="box">{ferie}</div>
        <div class="box">{stat}</div>
      </div>
      <div class="right">{cal}</div>
    </div>
    <div class="foot">Modello fac-simile a scopo illustrativo — privo di valore legale. Dati anagrafici e aziendali omessi.</div>
  </section>"""


CSS = """
@page { size: A4 portrait; margin: 8mm; }
:root { --ink:#1c2430; --line:#4a5566; --soft:#eef1f5; --lab:#5b6575; --paper:#fff; }
* { box-sizing: border-box; }
body { margin:0; background:#d9dde3; color:var(--ink); font-family: Arial, Helvetica, sans-serif; }
.page { position:relative; width:194mm; height:281mm; margin:10mm auto; padding:4mm; background:var(--paper);
        display:flex; flex-direction:column; gap:2mm; overflow:hidden; box-shadow:0 2px 10px rgba(0,0,0,.15);
        page-break-after:always; }
@media print { body { background:#fff; } .page { margin:0; box-shadow:none; } }
.wm { position:absolute; top:48%; left:50%; transform:translate(-50%,-50%) rotate(-32deg); font-size:34pt;
      font-weight:700; color:rgba(180,30,30,.10); white-space:nowrap; letter-spacing:2px; pointer-events:none; z-index:5; }
.v, td { font-family: "Courier New", Courier, monospace; }
.top { display:flex; justify-content:space-between; align-items:flex-start; height:24mm; }
.ditta { width:60%; font-size:8pt; }
.ditta .lab { font-size:7pt; color:var(--lab); margin-bottom:2mm; }
.blank { border-bottom:1px dotted #9aa3b0; height:5mm; width:75%; margin-bottom:1mm; }
.blank.inline { display:inline-block; width:40mm; height:3mm; margin:0; }
.cf { margin-top:1mm; }
.titolo { text-align:right; }
.titolo .t1 { font-size:15pt; font-weight:700; letter-spacing:3px; }
.titolo .t2 { font-size:8pt; color:var(--lab); margin-top:1mm; }
.foglio { font-size:8pt; margin-top:5mm; }
.box { border:1px solid var(--line); }
.r { display:flex; }
.r + .r { border-top:1px solid var(--line); }
.c { position:relative; min-height:8mm; padding:2.6mm 1mm .6mm; border-left:1px solid var(--line); min-width:0; }
.c:first-child { border-left:none; }
.l { position:absolute; top:.4mm; left:.8mm; right:.5mm; font-size:4.6pt; text-transform:uppercase; color:var(--lab);
     line-height:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.v { display:block; text-align:right; font-size:7.4pt; white-space:nowrap; }
.c.small .l { white-space:normal; }
.c.tiny { background:var(--soft); }
.c.tiny .l { font-weight:700; }
.c.hd { background:var(--soft); }
.c.hd .l { font-weight:700; white-space:normal; font-size:5pt; }
.c.hl { background:var(--soft); }
.c.netto { background:#e3e9f2; }
.c.netto .l { font-weight:700; color:var(--ink); }
.c.netto .v { font-weight:700; font-size:9pt; }
.el { border-top:1px solid var(--line); }
.side { writing-mode:vertical-rl; transform:rotate(180deg); font-size:4.6pt; text-transform:uppercase; color:var(--lab);
        padding:1mm .5mm; border-right:1px solid var(--line); text-align:center; }
.elg { flex:1; }
.elg .c { min-height:5.2mm; }
.body { flex:1; display:flex; gap:2mm; min-height:0; }
.left { flex:1; display:flex; flex-direction:column; gap:2mm; min-width:0; }
.left > .box:first-child { flex:1; display:flex; }
.right { width:41mm; display:flex; flex-direction:column; gap:2mm; }
table { border-collapse:collapse; width:100%; table-layout:fixed; }
th { font-size:4.8pt; text-transform:uppercase; color:var(--lab); font-weight:400; padding:.6mm .5mm;
     border-bottom:1px solid var(--line); background:var(--soft); }
td { font-size:7.4pt; padding:.55mm 1mm; text-align:right; white-space:nowrap; overflow:hidden; }
th.d, td.d { text-align:left; }
.voci { height:100%; }
.voci th, .voci td { border-left:1px solid var(--line); }
.voci th:first-child, .voci td:first-child { border-left:none; }
.voci td:first-child { text-align:left; }
.voci tr.fill td { height:100%; }
.cal, .leg { border:1px solid var(--line); }
.cal th, .cal td { border-left:1px solid var(--line); }
.cal td { border-top:1px solid #c5ccd6; font-size:6.6pt; padding:.35mm .6mm; height:4.15mm; }
.cal td.g { background:var(--soft); text-align:center; font-size:6pt; }
td.s { text-align:center; font-weight:700; }
.cal th { font-size:3.8pt; padding:.5mm .2mm; }
.cal col, .cal th:first-child { width:5mm; }
.leg td { font-size:6pt; border-top:1px solid #c5ccd6; padding:.6mm .8mm; height:4mm; }
.leg th:first-child, .leg td:first-child { width:8mm; border-right:1px solid var(--line); }
.firma { margin-top:auto; font-size:5pt; text-transform:uppercase; color:var(--lab); }
.firma .line { border-bottom:1px solid var(--line); height:8mm; }
.foot { font-size:5.5pt; color:var(--lab); text-align:center; }
"""


def main():
    print(f"Ottobre: integrazione {eur(X_OTT)} -> netto {R_OTT['netto']}")
    print(f"Novembre: integrazione {eur(X_NOV)} -> netto {R_NOV['netto']}")
    pagine = (pagina(OTT_SIM, "Fac-simile — simulazione ottobre (netto 1.560,00)")
              + pagina(NOV_SIM, "Fac-simile — simulazione novembre (netto 1.611,00)")
              + pagina(vuoto(), "Modello vuoto"))
    html = (f'<!doctype html><html lang="it"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width, initial-scale=1">'
            f'<title>Buste paga fac-simile</title><style>{CSS}</style></head><body>{pagine}</body></html>')
    out = Path(__file__).with_name("buste_paga_facsimile.html")
    out.write_text(html, encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main()
