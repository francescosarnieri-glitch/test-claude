FROM python:3.11-slim

# Nessuna dipendenza di sistema: httpx e eth-abi sono pure Python o wheel.
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY memescan ./memescan

# Il database vive su un volume: sopravvive a un aggiornamento dell'immagine.
ENV DB_PATH=/data/memescan.db \
    HOST=0.0.0.0 \
    PORT=8080 \
    PYTHONUNBUFFERED=1
VOLUME ["/data"]
EXPOSE 8080

# urlopen solleva un'eccezione se la risposta non e' 2xx: il codice di uscita
# diverso da zero basta a segnalare il container come non sano.
HEALTHCHECK --interval=60s --timeout=10s --start-period=40s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/api/health', timeout=5)"

CMD ["python", "-m", "memescan.cli", "serve"]
