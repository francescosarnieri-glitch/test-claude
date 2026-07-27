#!/usr/bin/env python3
"""
Genera la tabella dei significati che l'app usa per capire le domande.

Non è codice dell'applicazione: gira una volta sola su un computer, e il suo risultato —
`engine/nlu/src/main/resources/semantica/` — viene versionato come qualunque altro
contenuto. Sul telefono non c'è nessun modello che gira: c'è una tabella di parole e
numeri, e il confronto fra due elenchi di numeri.

Cosa produce:
  vocabolario.txt   una parola per riga, l'ordine è l'indice della riga nella matrice
  vettori.bin       'CSV1' + righe(int32 LE) + dimensioni(int32 LE) + matrice int8

Da dove vengono i numeri: un modello multilingue statico (potion-multilingual-128M), che
non genera testo e non ragiona — associa a ogni parola una direzione nello spazio, in modo
che parole usate negli stessi contesti finiscano vicine.

Le dimensioni sono tutte e 256, quante ne ha il modello: la tabella è quindi il modello
intero, ruotato e quantizzato a un byte per numero. Quindici megabyte invece di sessanta,
con un errore di quantizzazione di due millesimi. Le 128 dimensioni di prima ne facevano
sette e a parità di regole rispondevano bene a una domanda in meno su venti — misurato,
non stimato, in ComprensioneTest. Sopra i 256 non c'è niente da comprare: il modello di
partenza finisce lì, e per andare oltre servirebbe un altro strumento, non più spazio.

Uso:
    pip install model2vec wordfreq numpy
    python3 tools/semantica/genera.py
"""

import glob
import json
import math
import os
import re
import struct
import unicodedata

import numpy as np
from model2vec import StaticModel
from wordfreq import top_n_list

MODELLO = "minishlab/potion-multilingual-128M"
DIMENSIONI = 256
PAROLE_COMUNI = 60_000

RADICE = os.path.join(os.path.dirname(__file__), "..", "..")
DESTINAZIONE = os.path.join(RADICE, "engine", "nlu", "src", "main", "resources", "semantica")


def normalizza(testo: str) -> str:
    testo = unicodedata.normalize("NFD", testo.lower())
    testo = "".join(c for c in testo if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", testo).strip()


def parole_dei_contenuti() -> set[str]:
    """Ogni parola che la scuola scrive: nessuna delle nostre può mancare dalla tabella."""
    trovate: set[str] = set()

    def scava(nodo):
        if isinstance(nodo, str):
            trovate.update(p for p in normalizza(nodo).split() if 2 <= len(p) <= 24)
        elif isinstance(nodo, dict):
            for valore in nodo.values():
                scava(valore)
        elif isinstance(nodo, list):
            for valore in nodo:
                scava(valore)

    for percorso in glob.glob(os.path.join(RADICE, "content", "**", "*.json"), recursive=True):
        with open(percorso, encoding="utf-8") as f:
            scava(json.load(f))
    return trovate


def main() -> None:
    comuni = {p for parola in top_n_list("it", PAROLE_COMUNI)
              for p in normalizza(parola).split() if 2 <= len(p) <= 24}
    vocabolario = sorted(comuni | parole_dei_contenuti())
    print(f"vocabolario: {len(vocabolario)} parole")

    modello = StaticModel.from_pretrained(MODELLO)
    vettori = np.asarray(modello.encode(vocabolario, show_progress_bar=False), dtype=np.float32)
    vettori /= np.maximum(np.linalg.norm(vettori, axis=1, keepdims=True), 1e-9)

    # A 256 questa è una rotazione e basta — niente si perde. Resta perché il numero di
    # dimensioni è una manopola: abbassarlo dimezza il file e costa quello che è misurato.
    centrati = vettori - vettori.mean(0, keepdims=True)
    campione = centrati[np.random.default_rng(0).choice(len(centrati), 20_000, replace=False)]
    _, _, componenti = np.linalg.svd(campione, full_matrices=False)
    ridotti = centrati @ componenti[:DIMENSIONI].T
    ridotti /= np.maximum(np.linalg.norm(ridotti, axis=1, keepdims=True), 1e-9)

    quantizzati = np.clip(np.rint(ridotti * 127.0), -127, 127).astype(np.int8)
    perdita = float(np.mean(np.abs(ridotti - quantizzati.astype(np.float32) / 127.0)))
    print(f"errore di quantizzazione: {perdita:.4f}")

    os.makedirs(DESTINAZIONE, exist_ok=True)
    with open(os.path.join(DESTINAZIONE, "vocabolario.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(vocabolario))
    with open(os.path.join(DESTINAZIONE, "vettori.bin"), "wb") as f:
        f.write(b"CSV1")
        f.write(struct.pack("<ii", quantizzati.shape[0], quantizzati.shape[1]))
        f.write(quantizzati.tobytes(order="C"))

    peso = os.path.getsize(os.path.join(DESTINAZIONE, "vettori.bin")) / 1e6
    print(f"scritti {quantizzati.shape[0]}x{quantizzati.shape[1]} — {peso:.1f} MB")


if __name__ == "__main__":
    main()
