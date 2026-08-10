#!/usr/bin/env bash
# Baut eine neue APK-Version, aktualisiert dist/update.json für den lokalen
# WLAN-Server und committet + pusht alles automatisch.
#
#   ./release.sh 5 1.4.0 "Was sich geändert hat"
#
# Danach reicht in der App: Profil → Nach Update suchen.
set -euo pipefail

CODE="${1:?Versionscode fehlt, z.B. 5}"
NAME="${2:?Versionsname fehlt, z.B. 1.4.0}"
NOTES="${3:-}"

cd "$(dirname "$0")"

# Java/Android SDK/Gradle liegen im session-eigenen Scratchpad, das bei jeder
# neuen Sitzung leer ist – hier den zuletzt befüllten Ordner automatisch finden.
TOOLCHAIN_DIR=$(find /tmp/claude-1000 -maxdepth 6 -type d -name "jdk-17*" -printf '%T@ %h\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
if [ -z "$TOOLCHAIN_DIR" ]; then
  echo "Keine Java/Android-Toolchain gefunden. Muss neu heruntergeladen werden." >&2
  exit 1
fi
export JAVA_HOME="$(find "$TOOLCHAIN_DIR" -maxdepth 1 -type d -name "jdk-17*" | head -1)"
export ANDROID_HOME="$TOOLCHAIN_DIR/android-sdk"
GRADLE_BIN="$(find "$TOOLCHAIN_DIR" -maxdepth 2 -type d -name "gradle-*" | head -1)/bin/gradle"
export PATH="$JAVA_HOME/bin:$(dirname "$GRADLE_BIN"):$PATH"

echo "sdk.dir=$ANDROID_HOME" > android/local.properties

# versionCode/versionName in build.gradle.kts hochziehen
sed -i -E "s/versionCode = [0-9]+/versionCode = $CODE/" android/app/build.gradle.kts
sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"$NAME\"/" android/app/build.gradle.kts

( cd android && "$GRADLE_BIN" assembleDebug --no-daemon --console=plain )

# Portierte Kotlin-Tests laufen mit, damit ein Release nie eine kaputte
# Berechnung ausliefert.
( cd android && "$GRADLE_BIN" testDebugUnitTest --no-daemon --console=plain )

mkdir -p dist
cp android/app/build/outputs/apk/debug/app-debug.apk dist/WorkoutTracker.apk
cp dist/WorkoutTracker.apk ~/Schreibtisch/WorkoutTracker.apk

LAN_IP=$(ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+' || echo "127.0.0.1")

cat > dist/update.json <<JSON
{
  "versionCode": $CODE,
  "versionName": "$NAME",
  "apkUrl": "http://$LAN_IP:8080/WorkoutTracker.apk",
  "notes": "$NOTES"
}
JSON

git add -A
if ! git diff --cached --quiet; then
  git -c user.name="Jan" -c user.email="jpaetrow@gmail.com" commit -q -m "Release $NAME (Code $CODE)

$NOTES"
  git push
else
  echo "Nichts zu committen."
fi

echo
echo "✓ Version $NAME ist committet, gepusht und liegt bereit."
echo "  Zum Ausliefern im WLAN: ./serve-updates.sh"
echo "  In der App danach: Profil → Nach Update suchen"
