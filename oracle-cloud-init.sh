#!/bin/bash
#
# Script di prima accensione per Oracle Cloud (campo "Initialization script"
# nella creazione dell'istanza, sotto Advanced options).
#
# Cloud-init lo esegue come root al primo avvio della macchina: installa tutto,
# configura il servizio e avvisa su Telegram quando ha finito. Non serve
# collegarsi via SSH, il che lo rende usabile anche avendo solo un telefono.
#
# Prima di incollarlo, riempi le due righe qui sotto.
#
# Log dell'installazione, se qualcosa va storto:
#   /var/log/memescan-install.log
#

TELEGRAM_BOT_TOKEN=""
TELEGRAM_CHAT_ID=""

# ---------------------------------------------------------------------------

set -uo pipefail
exec > >(tee -a /var/log/memescan-install.log) 2>&1
echo "=== memescan · installazione avviata $(date -Is) ==="

REPO_URL="https://github.com/francescosarnieri-glitch/test-claude.git"
REPO_BRANCH="claude/meme-coin-trends-kezt9k"
INSTALL_DIR="/opt/memescan"
PORT=8080

# L'utente predefinito cambia a seconda dell'immagine: Ubuntu usa "ubuntu",
# Oracle Linux usa "opc". Si prende il primo che esiste davvero.
APP_USER="ubuntu"
id -u ubuntu > /dev/null 2>&1 || APP_USER="opc"
id -u "$APP_USER" > /dev/null 2>&1 || APP_USER="root"
echo "utente applicativo: $APP_USER"

notify() {
    [ -z "$TELEGRAM_BOT_TOKEN" ] && return 0
    curl -s --max-time 20 \
        --data-urlencode "chat_id=${TELEGRAM_CHAT_ID}" \
        --data-urlencode "text=$1" \
        --data-urlencode "parse_mode=HTML" \
        "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" > /dev/null || true
}

fail() {
    echo "ERRORE: $1"
    notify "❌ <b>Installazione fallita</b>%0A${1}%0A%0ALog: /var/log/memescan-install.log"
    exit 1
}

if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_CHAT_ID" ]; then
    fail "Token o chat id Telegram non compilati nello script"
fi

notify "⚙️ <b>memescan</b>%0AServer acceso, installazione in corso. Ci vogliono 3-5 minuti."

# --- 1. Dipendenze ----------------------------------------------------------

export DEBIAN_FRONTEND=noninteractive

# Al primo avvio unattended-upgrades tiene occupato il lock di apt: senza
# questo timeout l'installazione fallirebbe a caso a seconda dei tempi.
APT_OPTS="-o DPkg::Lock::Timeout=600 -y -qq"

echo "--- aggiorno gli indici dei pacchetti"
apt-get $APT_OPTS update || fail "apt-get update non riuscito"

echo "--- installo python, git e curl"
apt-get $APT_OPTS install python3 python3-venv python3-pip git curl iptables-persistent \
    || fail "installazione dei pacchetti non riuscita"

# --- 2. Codice --------------------------------------------------------------

echo "--- scarico il codice"
rm -rf "$INSTALL_DIR"
git clone --quiet --branch "$REPO_BRANCH" "$REPO_URL" "$INSTALL_DIR" \
    || fail "clone del repository non riuscito"
chown -R "$APP_USER:$APP_USER" "$INSTALL_DIR"

# --- 3. Ambiente Python -----------------------------------------------------

echo "--- preparo l'ambiente Python"
cd "$INSTALL_DIR/backend" || fail "cartella backend non trovata"
sudo -u "$APP_USER" python3 -m venv .venv || fail "creazione del virtualenv non riuscita"
sudo -u "$APP_USER" ./.venv/bin/pip install --quiet --upgrade pip
sudo -u "$APP_USER" ./.venv/bin/pip install --quiet -r requirements.txt \
    || fail "installazione delle dipendenze Python non riuscita"

# --- 4. Configurazione ------------------------------------------------------

echo "--- scrivo la configurazione"
API_TOKEN="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"

sudo -u "$APP_USER" cp .env.example .env
sudo -u "$APP_USER" python3 - "$TELEGRAM_BOT_TOKEN" "$TELEGRAM_CHAT_ID" "$API_TOKEN" <<'PYTHON'
import sys
from pathlib import Path

values = dict(zip(("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "API_TOKEN"), sys.argv[1:4]))
path = Path(".env")
out, seen = [], set()
for line in path.read_text().splitlines():
    key = line.split("=", 1)[0].strip()
    if key in values:
        out.append(f"{key}={values[key]}")
        seen.add(key)
    else:
        out.append(line)
out += [f"{k}={v}" for k, v in values.items() if k not in seen]
path.write_text("\n".join(out) + "\n")
PYTHON
chmod 600 .env
chown "$APP_USER:$APP_USER" .env

# --- 5. Servizio ------------------------------------------------------------

echo "--- configuro il servizio di sistema"
cat > /etc/systemd/system/memescan.service <<UNIT
[Unit]
Description=memescan - scanner di meme coin
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${APP_USER}
WorkingDirectory=${INSTALL_DIR}/backend
ExecStart=${INSTALL_DIR}/backend/.venv/bin/python -m memescan.cli serve
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable memescan
systemctl start memescan

# --- 6. Firewall ------------------------------------------------------------

# Le immagini di Oracle bloccano tutto tranne la porta 22 con una regola REJECT
# in fondo alla catena INPUT: la nuova regola va inserita prima di quella.
echo "--- apro la porta $PORT"
iptables -I INPUT 6 -m state --state NEW -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null \
    || iptables -I INPUT -p tcp --dport "$PORT" -j ACCEPT
netfilter-persistent save > /dev/null 2>&1 || true

# --- 7. Verifica e avviso ---------------------------------------------------

echo "--- verifico l'avvio"
sleep 20

PUBLIC_IP="$(curl -s --max-time 10 https://api.ipify.org || echo '')"

if systemctl is-active --quiet memescan; then
    echo "servizio attivo"
    notify "✅ <b>memescan è attivo</b>%0A%0AIl server sta scansionando Robinhood Chain. Gli alert arriveranno qui.%0A%0A🖥 Dashboard: http://${PUBLIC_IP}:${PORT}%0A🔑 Token: <code>${API_TOKEN}</code>%0A%0A⚠️ Per aprire la dashboard da fuori manca ancora la regola nella Security List del pannello Oracle."
else
    journalctl -u memescan -n 40 --no-pager
    notify "⚠️ <b>Installato ma il servizio non parte</b>%0AIP: ${PUBLIC_IP}%0AControlla il log: /var/log/memescan-install.log"
fi

echo "=== installazione conclusa $(date -Is) ==="
