# App-Updates über GitHub Releases

Die App holt sich Updates selbst — du musst nie wieder eine APK aufs Handy kopieren.

## Einmalig einrichten

1. Auf GitHub ein Repository anlegen (privat geht auch **nicht** — die Release-Assets müssen
   ohne Login erreichbar sein, also **öffentlich**; im Repo selbst muss kein Code liegen).
2. In der App ist die Adresse bereits voreingestellt (Profil → App-Update):

   ```
   https://github.com/balticblalbu/gymapp/releases/latest/download/update.json
   ```

   `latest` ist eine dauerhafte Weiterleitung auf das neueste Release — die Adresse
   ändert sich nie wieder.

## Neue Version veröffentlichen

```bash
./release.sh 2 1.1.0 "Sende-Bug behoben"
```

Das Skript zieht `versionCode`/`versionName` hoch, baut die APK und schreibt
`dist/update.json`. Dann beide Dateien an ein neues Release hängen:

```bash
gh release create v1.1.0 dist/WorkoutTracker.apk dist/update.json --notes "Sende-Bug behoben"
```

Ohne `gh` CLI: auf GitHub → *Releases* → *Draft a new release* → beide Dateien aus `dist/`
per Drag-and-drop anhängen → *Publish*.

**Der `versionCode` muss bei jedem Release steigen** — daran erkennt die App, dass es etwas
Neues gibt. Bei gleichem Code meldet sie „Du hast die neueste Version".

## In der App

Profil → **Nach Update suchen** → bei einer neueren Version erscheint **Installieren**.
Android zeigt dann seinen normalen Installationsdialog; beim ersten Mal musst du dieser App
erlauben, Apps zu installieren.

## Was nicht funktioniert

Filehoster wie workupload, Google Drive oder Dropbox-Sharelinks liefern eine HTML-Seite
statt der Datei. Test: Adresse im Browser öffnen — startet der Download **sofort** ohne
Zwischenseite, funktioniert sie auch in der App.
