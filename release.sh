#!/usr/bin/env bash
# Baut die APK und legt update.json daneben – beides als Release-Assets hochladen.
#
#   ./release.sh 2 1.1.0 "Sende-Bug behoben"
#
# Danach auf GitHub ein Release anlegen und BEIDE Dateien aus dist/ anhängen.
set -euo pipefail

CODE="${1:?Versionscode fehlt, z.B. 2}"
NAME="${2:?Versionsname fehlt, z.B. 1.1.0}"
NOTES="${3:-}"
REPO="${GITHUB_REPO:-jpaetrow-dotcom/gymapp}"

cd "$(dirname "$0")"

# versionCode/versionName in build.gradle.kts hochziehen
sed -i -E "s/versionCode = [0-9]+/versionCode = $CODE/" android/app/build.gradle.kts
sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"$NAME\"/" android/app/build.gradle.kts

( cd android && ./gradlew assembleDebug )

mkdir -p dist
cp android/app/build/outputs/apk/debug/app-debug.apk dist/WorkoutTracker.apk

cat > dist/update.json <<JSON
{
  "versionCode": $CODE,
  "versionName": "$NAME",
  "apkUrl": "https://github.com/$REPO/releases/latest/download/WorkoutTracker.apk",
  "notes": "$NOTES"
}
JSON

echo
echo "Fertig. In dist/ liegen:"
ls -1 dist/
echo
echo "Beide Dateien an ein neues GitHub-Release anhängen. Mit gh CLI:"
echo "  gh release create v$NAME dist/WorkoutTracker.apk dist/update.json --notes \"$NOTES\""
