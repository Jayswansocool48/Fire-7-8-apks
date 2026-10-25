#!/bin/bash
# Started by the Hub Server app in CasaOS.
# Everything it prints goes to hub-server.log in this same folder, so you can
# read it in the CasaOS Files app if something goes wrong.

LOG=/data/hub-server.log
if [ -f "$LOG" ] && [ "$(stat -c%s "$LOG")" -gt 5000000 ]; then mv -f "$LOG" "$LOG.old"; fi
exec >>"$LOG" 2>&1

echo
echo "===== $(date) - starting Hub server ====="
cd /data || exit 1

if [ ! -f /data/hub_server.py ]; then
  echo "ERROR: hub_server.py is not in /DATA/AppData/hub-server/. Files found there:"
  ls -la /data
  sleep 60
  exit 1
fi

if ! python3 -c "import playwright" 2>/dev/null; then
  echo "Installing Playwright for Python..."
  pip3 install --break-system-packages playwright || pip3 install playwright
fi

# Makes sure the Chromium build matches the installed Playwright (quick if it already does).
python3 -m playwright install chromium || echo "WARNING: Chromium install failed - see above"

echo "Launching hub_server.py (TV: ${TV_IP:-auto-detect})"
if [ -n "$TV_IP" ]; then
  exec python3 /data/hub_server.py --config /data/config.json --tv "$TV_IP"
else
  exec python3 /data/hub_server.py --config /data/config.json
fi
