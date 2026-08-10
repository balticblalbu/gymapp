#!/usr/bin/env bash
# Stellt die aktuelle APK im WLAN bereit, damit die App sie selbst ziehen kann.
# Starten, in der App auf "Nach Update suchen" tippen, danach mit Strg+C beenden.
set -euo pipefail
cd "$(dirname "$0")/dist"

IP=$(ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+' || echo "127.0.0.1")
echo
echo "  Update-Server läuft auf  http://$IP:8080/update.json"
echo
echo "  Handy muss im selben WLAN sein."
echo "  In der App:  Profil → Nach Update suchen → Installieren"
echo
echo "  Beenden mit Strg+C"
echo
python3 -m http.server 8080 --bind 0.0.0.0
