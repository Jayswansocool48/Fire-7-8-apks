#!/usr/bin/env bash
# Hub server without CasaOS: installs (or updates) Hub as a service for your user on Debian or Ubuntu.
#
#   ./install.sh               install, or update to the files next to this script
#   ./install.sh --check       show what's installed, whether it runs, and whether GitHub has a newer version
#   ./install.sh --uninstall   stop Hub and delete it with all of its settings and data
#
# On CasaOS, use docker-compose.yml instead (see README.md).
set -euo pipefail

APP_DIR="${HUB_DIR:-$HOME/.local/share/hub-server}"
UNIT_DIR="$HOME/.config/systemd/user"
UNIT="$UNIT_DIR/hub-server.service"
PORT=8765
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAW="https://raw.githubusercontent.com/Jayswansocool48/Fire-7-8-apks/main/hub-server"

say() { printf '%s\n' "$*"; }
version_of() { sed -n 's/^VERSION = "\(.*\)"/\1/p' "$1" 2>/dev/null; }

check() {
  if [ -f "$APP_DIR/hub_server.py" ]; then
    say "Installed: Hub server $(version_of "$APP_DIR/hub_server.py") in $APP_DIR"
  else
    say "Not installed in $APP_DIR"
  fi
  if systemctl --user is-active --quiet hub-server.service 2>/dev/null; then say "Service: running"; else say "Service: not running"; fi
  if curl -fsS -m 4 "http://127.0.0.1:$PORT/api/status" > /dev/null 2>&1; then
    say "Control page: http://$(hostname -I 2>/dev/null | awk '{print $1}'):$PORT"
  else
    say "Control page: not answering on port $PORT"
  fi
  latest=$(curl -fsS -m 8 "$RAW/VERSION" 2>/dev/null | tr -d ' \n' || true)
  [ -n "$latest" ] && say "Latest on GitHub: $latest"
}

uninstall() {
  say "Stopping Hub and deleting $APP_DIR (settings, notes, wallpapers, sent files and caches)."
  systemctl --user disable --now hub-server.service 2>/dev/null || true
  rm -f "$UNIT"
  systemctl --user daemon-reload 2>/dev/null || true
  rm -rf "$APP_DIR"
  say "Hub server removed. To take the channel off the TV, open http://<your TV's IP> in a browser and press Delete."
}

install() {
  for f in hub_server.py hub_control.html VERSION; do
    [ -f "$SRC/$f" ] || { say "Missing $f next to install.sh. Unzip all of hub-server.zip first."; exit 1; }
  done
  command -v python3 > /dev/null || { say "Needs Python 3: sudo apt install python3 python3-venv"; exit 1; }
  python3 -c "import venv" 2> /dev/null || { say "Needs python3-venv: sudo apt install python3-venv"; exit 1; }

  old=$(version_of "$APP_DIR/hub_server.py")
  mkdir -p "$APP_DIR/fonts" "$UNIT_DIR"
  cp "$SRC/hub_server.py" "$SRC/hub_control.html" "$SRC/VERSION" "$APP_DIR/"
  cp "$SRC/install.sh" "$APP_DIR/" 2> /dev/null || true
  cp "$SRC"/fonts/* "$APP_DIR/fonts/"

  if [ ! -x "$APP_DIR/venv/bin/python" ]; then
    say "Setting up Python packages (one time, a few minutes)..."
    python3 -m venv "$APP_DIR/venv"
  fi
  "$APP_DIR/venv/bin/pip" install --quiet --upgrade playwright pillow
  # Chromium for Hub's TV browser. The system libraries it needs come from apt, so this asks for your password once.
  if ! "$APP_DIR/venv/bin/python" -m playwright install --with-deps chromium; then
    say "Couldn't install Chromium's system libraries. Run this, then install.sh again:"
    say "  sudo $APP_DIR/venv/bin/python -m playwright install-deps chromium"
    exit 1
  fi

  cat > "$UNIT" <<UNIT
[Unit]
Description=Hub server (Roku Hub companion)
After=network-online.target

[Service]
WorkingDirectory=$APP_DIR
ExecStart=$APP_DIR/venv/bin/python $APP_DIR/hub_server.py --config $APP_DIR/config.json
Restart=on-failure
RestartSec=5
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
UNIT
  chmod 600 "$UNIT"
  systemctl --user daemon-reload
  systemctl --user enable --now hub-server.service
  systemctl --user restart hub-server.service
  # keep Hub running after you log out
  loginctl enable-linger "$USER" 2> /dev/null || say "Tip: run 'sudo loginctl enable-linger $USER' so Hub keeps running after you log out."

  new=$(version_of "$APP_DIR/hub_server.py")
  if [ -n "$old" ] && [ "$old" != "$new" ]; then say "Updated Hub server $old to $new."; else say "Installed Hub server $new."; fi
  sleep 3
  check
}

case "${1:-}" in
  --check) check ;;
  --uninstall) uninstall ;;
  ""|--install|--update) install ;;
  *) say "Usage: install.sh [--check | --uninstall]"; exit 2 ;;
esac
