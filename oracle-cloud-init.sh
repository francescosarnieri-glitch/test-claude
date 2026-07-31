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

# Si possono riempire qui, oppure passare dall'ambiente. La seconda strada
# permette di incollare poche righe invece dell'intero script:
#
#   #!/bin/bash
#   export TELEGRAM_BOT_TOKEN="123456:AAA..."
#   export TELEGRAM_CHAT_ID="123456789"
#   curl -fsSL <url-di-questo-file> | bash
#
TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_CHAT_ID="${TELEGRAM_CHAT_ID:-}"

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

# Il testo va passato con veri a capo: --data-urlencode li codifica gia' lui.
# Scrivere a mano la sequenza di escape per l'a capo la farebbe codificare una
# seconda volta, e comparirebbe come testo dentro il messaggio.
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
    notify "❌ <b>Installazione fallita</b>
${1}

Log: /var/log/memescan-install.log"
    exit 1
}

if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_CHAT_ID" ]; then
    fail "Token o chat id Telegram non compilati nello script"
fi

notify "⚙️ <b>memescan</b>
Server acceso, installazione in corso. Ci vogliono 3-5 minuti."

# --- 1. Dipendenze ----------------------------------------------------------

export DEBIAN_FRONTEND=noninteractive

# Al primo avvio unattended-upgrades tiene occupato il lock di apt: senza
# questo timeout l'installazione fallirebbe a caso a seconda dei tempi.
APT_OPTS="-o DPkg::Lock::Timeout=600 -y -qq"

echo "--- aggiorno gli indici dei pacchetti"
apt-get $APT_OPTS update || fail "apt-get update non riuscito"

# iptables-persistent non viene piu' installato: cambia il modo in cui le
# regole del firewall sopravvivono al riavvio, e su un'immagine Oracle quelle
# regole le imposta gia' l'immagine stessa. Con il tunnel non serve comunque
# aprire nessuna porta in entrata.
echo "--- installo python, git e curl"
apt-get $APT_OPTS install python3 python3-venv python3-pip git curl \
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

# --- 5-bis. Aggiornamento automatico ----------------------------------------

# Il codice viene scaricato una volta sola all'accensione: senza questo, ogni
# modifica richiederebbe di ricreare la macchina. Un timer controlla il ramo
# su GitHub e, se e' cambiato, aggiorna e riavvia da solo.
echo "--- configuro l'aggiornamento automatico"
cat > /usr/local/bin/memescan-update <<UPDATE
#!/bin/bash
set -uo pipefail

DIR="${INSTALL_DIR}"
BRANCH="${REPO_BRANCH}"
APP_USER="${APP_USER}"
TOKEN="${TELEGRAM_BOT_TOKEN}"
CHAT="${TELEGRAM_CHAT_ID}"

# Tutti i comandi git girano come l'utente proprietario della cartella:
# eseguirli da root farebbe scattare il controllo sulla proprieta' sospetta.
run_git() { sudo -u "\$APP_USER" git -C "\$DIR" "\$@"; }

cd "\$DIR" 2>/dev/null || exit 0
run_git fetch --quiet origin "\$BRANCH" || exit 0

LOCAL="\$(run_git rev-parse HEAD)"
REMOTE="\$(run_git rev-parse "origin/\$BRANCH")"
[ "\$LOCAL" = "\$REMOTE" ] && exit 0

echo "aggiornamento \${LOCAL:0:8} -> \${REMOTE:0:8}"
CHANGED="\$(run_git diff --name-only "\$LOCAL" "\$REMOTE")"
run_git reset --hard --quiet "origin/\$BRANCH" || exit 1

# Le dipendenze si reinstallano solo se sono davvero cambiate: su una macchina
# con 1 GB di RAM un pip install inutile ogni volta sarebbe uno spreco.
if echo "\$CHANGED" | grep -q 'backend/requirements.txt'; then
    echo "requirements cambiati, reinstallo"
    sudo -u "\$APP_USER" "\$DIR/backend/.venv/bin/pip" install --quiet \\
        -r "\$DIR/backend/requirements.txt"
fi

systemctl restart memescan
sleep 12

SUBJECT="\$(run_git log -1 --pretty=%s)"
if systemctl is-active --quiet memescan; then
    STATUS="Il servizio è ripartito correttamente."
else
    # Il messaggio deve dire che qualcosa non va anche quando il servizio
    # e' morto: altrimenti l'aggiornamento fallirebbe in silenzio.
    STATUS="⚠️ Attenzione: dopo l'aggiornamento il servizio non riparte."
fi

curl -s --max-time 20 \\
    --data-urlencode "chat_id=\$CHAT" \\
    --data-urlencode "parse_mode=HTML" \\
    --data-urlencode "text=🔄 <b>memescan aggiornato</b>

\$STATUS

<i>\$SUBJECT</i>" \\
    "https://api.telegram.org/bot\$TOKEN/sendMessage" > /dev/null
UPDATE
chmod +x /usr/local/bin/memescan-update

cat > /etc/systemd/system/memescan-update.service <<UNIT
[Unit]
Description=memescan - controlla e applica gli aggiornamenti
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/memescan-update
UNIT

cat > /etc/systemd/system/memescan-update.timer <<UNIT
[Unit]
Description=memescan - controllo periodico degli aggiornamenti

[Timer]
OnBootSec=5min
OnUnitActiveSec=10min
Persistent=true

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
systemctl enable --now memescan-update.timer > /dev/null 2>&1

# --- 6. Tunnel ---------------------------------------------------------------

# La dashboard non viene esposta aprendo una porta in entrata, ma con un
# tunnel: e' il server a collegarsi verso l'esterno e a ricevere in cambio un
# indirizzo https pubblico. Cosi' non dipendiamo da regole di firewall, dal
# routing del cloud o dall'indirizzo IP, che sull'istanza gratuita puo'
# cambiare a ogni riavvio.
echo "--- installo il tunnel"
ARCH="amd64"
[ "$(uname -m)" = "aarch64" ] && ARCH="arm64"
CLOUDFLARED_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${ARCH}.deb"

if curl -fsSL --max-time 120 -o /tmp/cloudflared.deb "$CLOUDFLARED_URL"; then
    dpkg -i /tmp/cloudflared.deb > /dev/null 2>&1 || apt-get $APT_OPTS -f install
    rm -f /tmp/cloudflared.deb
fi

if command -v cloudflared > /dev/null; then
    cat > /etc/systemd/system/memescan-tunnel.service <<UNIT
[Unit]
Description=memescan - tunnel pubblico verso la dashboard
After=network-online.target memescan.service
Wants=network-online.target

[Service]
Type=simple
ExecStartPre=/bin/rm -f /var/log/cloudflared.log
ExecStart=/usr/bin/cloudflared tunnel --no-autoupdate --logfile /var/log/cloudflared.log --url http://127.0.0.1:${PORT}
Restart=always
RestartSec=15

[Install]
WantedBy=multi-user.target
UNIT

    # L'indirizzo del tunnel e' generato al volo e cambia a ogni riavvio:
    # va letto dal log e comunicato, altrimenti resterebbe sconosciuto.
    cat > /usr/local/bin/memescan-tunnel-notify <<NOTIFY
#!/bin/bash
TOKEN="${TELEGRAM_BOT_TOKEN}"
CHAT="${TELEGRAM_CHAT_ID}"
API_TOKEN="${API_TOKEN}"

for _ in \$(seq 1 40); do
    URL="\$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' /var/log/cloudflared.log 2>/dev/null | head -1)"
    [ -n "\$URL" ] && break
    sleep 3
done

[ -z "\$URL" ] && exit 0
echo "\$URL" > /opt/memescan/tunnel-url.txt

curl -s --max-time 20 \\
    --data-urlencode "chat_id=\$CHAT" \\
    --data-urlencode "parse_mode=HTML" \\
    --data-urlencode "text=🖥 <b>Dashboard raggiungibile</b>

\$URL

🔑 Token: <code>\$API_TOKEN</code>

L'indirizzo cambia a ogni riavvio del server: quando succede te ne arriva uno nuovo qui." \\
    "https://api.telegram.org/bot\$TOKEN/sendMessage" > /dev/null
NOTIFY
    chmod +x /usr/local/bin/memescan-tunnel-notify

    cat > /etc/systemd/system/memescan-tunnel-notify.service <<UNIT
[Unit]
Description=memescan - comunica l'indirizzo del tunnel
After=memescan-tunnel.service
Requires=memescan-tunnel.service

[Service]
Type=oneshot
ExecStart=/usr/local/bin/memescan-tunnel-notify

[Install]
WantedBy=multi-user.target
UNIT

    systemctl daemon-reload
    systemctl enable --now memescan-tunnel > /dev/null 2>&1
    systemctl enable memescan-tunnel-notify > /dev/null 2>&1
    TUNNEL_OK=1
else
    echo "cloudflared non installato: la dashboard restera' raggiungibile solo dalla rete locale"
    TUNNEL_OK=0
fi

# La porta resta aperta anche sul firewall locale, cosi' se un domani il
# traffico in entrata funzionasse la dashboard sarebbe raggiungibile anche
# per via diretta. Non installiamo nulla per renderlo persistente.
iptables -I INPUT 6 -m state --state NEW -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null \
    || iptables -I INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null || true

# --- 7. Verifica e avviso ---------------------------------------------------

echo "--- verifico l'avvio"
sleep 20

# L'IP pubblico si chiede al servizio di metadati di Oracle, che risponde con
# l'indirizzo realmente assegnato a questa scheda di rete. Un servizio esterno
# come ipify direbbe soltanto da quale indirizzo il traffico esce, che con un
# NAT gateway di mezzo e' un indirizzo diverso e non raggiungibile da fuori.
# L'istanza richiede IMDSv2, quindi serve l'header di autorizzazione.
PUBLIC_IP="$(curl -s --max-time 10 -H 'Authorization: Bearer Oracle' \
    'http://169.254.169.254/opc/v2/vnics/' 2>/dev/null \
    | grep -o '"publicIp"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | head -1 | cut -d'"' -f4)"

if [ -z "$PUBLIC_IP" ]; then
    echo "metadati Oracle non disponibili, ripiego su un servizio esterno"
    PUBLIC_IP="$(curl -s --max-time 10 https://api.ipify.org || echo '')"
fi
echo "IP pubblico rilevato: ${PUBLIC_IP:-nessuno}"

if systemctl is-active --quiet memescan; then
    echo "servizio attivo"
    if [ "${TUNNEL_OK:-0}" = "1" ]; then
        # L'indirizzo del tunnel arriva con un messaggio a parte, appena
        # cloudflared lo ha generato: qui non e' ancora disponibile.
        notify "✅ <b>memescan è attivo</b>

Il server sta scansionando Robinhood Chain. Gli alert arriveranno qui.

🔗 Tra qualche secondo ricevi l'indirizzo della dashboard in un altro messaggio.

🔑 Token: <code>${API_TOKEN}</code>
🖥 IP del server: ${PUBLIC_IP:-non rilevato}"
        systemctl start memescan-tunnel-notify > /dev/null 2>&1 &
    else
        notify "✅ <b>memescan è attivo</b>

Il server sta scansionando Robinhood Chain. Gli alert arriveranno qui.

🖥 Dashboard: http://${PUBLIC_IP}:${PORT}
🔑 Token: <code>${API_TOKEN}</code>

⚠️ Il tunnel non si è installato: la dashboard è raggiungibile solo se il traffico in entrata funziona."
    fi
else
    journalctl -u memescan -n 40 --no-pager
    notify "⚠️ <b>Installato ma il servizio non parte</b>
IP: ${PUBLIC_IP}
Controlla il log: /var/log/memescan-install.log"
fi

echo "=== installazione conclusa $(date -Is) ==="
