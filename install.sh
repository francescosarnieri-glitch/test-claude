#!/usr/bin/env bash
#
# Installazione di memescan su un server Ubuntu (pensato per Oracle Cloud).
#
# Fa tutto: dipendenze, scaricamento del codice, configurazione, servizio di
# sistema che riparte da solo e regola di firewall. Si puo' rilanciare senza
# problemi: aggiorna quello che c'e' invece di duplicarlo.
#
# Uso:
#   export TELEGRAM_BOT_TOKEN="..."
#   export TELEGRAM_CHAT_ID="..."
#   bash install.sh
#
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/francescosarnieri-glitch/test-claude.git}"
REPO_BRANCH="${REPO_BRANCH:-claude/meme-coin-trends-kezt9k}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/memescan}"
SERVICE_NAME="memescan"
PORT="${PORT:-8080}"

blue()  { printf '\033[1;34m%s\033[0m\n' "$*"; }
green() { printf '\033[1;32m%s\033[0m\n' "$*"; }
red()   { printf '\033[1;31m%s\033[0m\n' "$*"; }

blue "==> memescan · installazione"

# --- 1. Controlli preliminari ------------------------------------------------

if [[ $EUID -eq 0 ]]; then
    red "Non lanciarlo come root: usa l'utente normale (ubuntu o opc)."
    exit 1
fi

if [[ -z "${TELEGRAM_BOT_TOKEN:-}" || -z "${TELEGRAM_CHAT_ID:-}" ]]; then
    red "Mancano TELEGRAM_BOT_TOKEN o TELEGRAM_CHAT_ID."
    echo "Impostali prima di lanciare lo script:"
    echo '  export TELEGRAM_BOT_TOKEN="123456:AAA..."'
    echo '  export TELEGRAM_CHAT_ID="123456789"'
    exit 1
fi

# --- 2. Dipendenze di sistema ------------------------------------------------

blue "==> Installo le dipendenze di sistema"
sudo apt-get update -qq
sudo apt-get install -y -qq python3 python3-venv python3-pip git curl

# --- 3. Codice ---------------------------------------------------------------

if [[ -d "$INSTALL_DIR/.git" ]]; then
    blue "==> Aggiorno il codice esistente"
    git -C "$INSTALL_DIR" fetch --quiet origin "$REPO_BRANCH"
    git -C "$INSTALL_DIR" checkout --quiet "$REPO_BRANCH"
    git -C "$INSTALL_DIR" reset --hard --quiet "origin/$REPO_BRANCH"
else
    blue "==> Scarico il codice"
    git clone --quiet --branch "$REPO_BRANCH" "$REPO_URL" "$INSTALL_DIR"
fi

# --- 4. Ambiente Python ------------------------------------------------------

blue "==> Preparo l'ambiente Python"
cd "$INSTALL_DIR/backend"
python3 -m venv .venv
./.venv/bin/pip install --quiet --upgrade pip
./.venv/bin/pip install --quiet -r requirements.txt

# --- 5. Configurazione -------------------------------------------------------

# Il token dell'API protegge la dashboard: il server ha un IP pubblico, quindi
# senza questo chiunque lo scoprisse potrebbe leggere e modificare la watchlist.
if [[ -f .env ]] && grep -q '^API_TOKEN=.\+' .env; then
    API_TOKEN="$(grep '^API_TOKEN=' .env | cut -d= -f2-)"
    blue "==> Riuso il token API esistente"
else
    API_TOKEN="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
    blue "==> Genero un nuovo token API"
fi

cp -n .env.example .env
python3 - "$TELEGRAM_BOT_TOKEN" "$TELEGRAM_CHAT_ID" "$API_TOKEN" <<'PYTHON'
import sys
from pathlib import Path

bot_token, chat_id, api_token = sys.argv[1:4]
values = {
    "TELEGRAM_BOT_TOKEN": bot_token,
    "TELEGRAM_CHAT_ID": chat_id,
    "API_TOKEN": api_token,
}

path = Path(".env")
lines = path.read_text().splitlines()
seen = set()
out = []
for line in lines:
    key = line.split("=", 1)[0].strip()
    if key in values:
        out.append(f"{key}={values[key]}")
        seen.add(key)
    else:
        out.append(line)
for key, value in values.items():
    if key not in seen:
        out.append(f"{key}={value}")
path.write_text("\n".join(out) + "\n")
print("  .env aggiornato")
PYTHON

# --- 6. Servizio di sistema --------------------------------------------------

blue "==> Configuro il servizio di sistema"
sudo tee "/etc/systemd/system/${SERVICE_NAME}.service" > /dev/null <<UNIT
[Unit]
Description=memescan - scanner di meme coin
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$INSTALL_DIR/backend
ExecStart=$INSTALL_DIR/backend/.venv/bin/python -m memescan.cli serve
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --quiet "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

# --- 7. Firewall -------------------------------------------------------------

# Le immagini Ubuntu di Oracle arrivano con iptables che blocca tutto tranne la
# porta 22. Senza questa regola la dashboard non risponde, anche con la Security
# List aperta nel pannello Oracle: servono entrambe.
blue "==> Apro la porta $PORT sul firewall locale"
if ! sudo iptables -C INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null; then
    sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport "$PORT" -j ACCEPT
fi
if command -v netfilter-persistent > /dev/null; then
    sudo netfilter-persistent save > /dev/null 2>&1 || true
fi

# --- 8. Verifica -------------------------------------------------------------

blue "==> Verifico che sia partito"
sleep 8
if systemctl is-active --quiet "$SERVICE_NAME"; then
    green "  servizio attivo"
else
    red "  il servizio non e' partito. Log:"
    sudo journalctl -u "$SERVICE_NAME" -n 30 --no-pager
    exit 1
fi

PUBLIC_IP="$(curl -s --max-time 10 https://api.ipify.org || echo '<IP-DEL-SERVER>')"

echo
green "======================================================"
green " memescan e' installato e in esecuzione"
green "======================================================"
echo
echo "  Dashboard:   http://${PUBLIC_IP}:${PORT}"
echo "  Token API:   ${API_TOKEN}"
echo
echo "  Segnati il token: serve per aprire la dashboard."
echo
echo "  Comandi utili:"
echo "    sudo systemctl status ${SERVICE_NAME}      stato del servizio"
echo "    sudo journalctl -u ${SERVICE_NAME} -f      log dal vivo"
echo "    sudo systemctl restart ${SERVICE_NAME}     riavvio"
echo
echo "  Manca ancora un passaggio nel pannello Oracle:"
echo "  apri la porta ${PORT} nella Security List della rete."
echo
