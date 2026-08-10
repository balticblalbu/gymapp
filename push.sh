#!/usr/bin/env bash
# Lädt den Code zu GitHub und zeigt danach, wie das Release angelegt wird.
# Git fragt nach Username und Token – die tippst du selbst ein.
set -euo pipefail
cd "$(dirname "$0")"

echo "Lade Code zu github.com/jpaetrow-dotcom/gymapp …"
echo "Username: jpaetrow-dotcom"
echo "Password: dein Personal Access Token (NICHT das GitHub-Passwort)"
echo

git push -u origin main

cat <<'TXT'

✓ Code ist oben.

Jetzt noch das Release, damit der Update-Button der App etwas findet:

  1. https://github.com/jpaetrow-dotcom/gymapp/releases/new  öffnen
  2. Tag:   v1.1.0
  3. Diese beiden Dateien ins Feld "Attach binaries" ziehen:
TXT
echo "       $(pwd)/dist/WorkoutTracker.apk"
echo "       $(pwd)/dist/update.json"
cat <<'TXT'
  4. "Publish release" klicken

Danach in der App: Profil → Nach Update suchen.
TXT
