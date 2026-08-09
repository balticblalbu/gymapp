# AI Workout Tracker

Trainings-Tracking per **Sprache – direkt in der App**: Du tippst auf das Mikrofon, sagst was du trainiert hast, und **Claude** macht daraus strukturierte Trainingsdaten mit Statistiken, Charts und Rekorden.

```
App (Mikrofon) → Spracherkennung auf dem Handy → Text → Claude (Anthropic API)
    → Validierung → PostgreSQL → REST API → App
```

**Kein Login, kein Konto, kein Telegram nötig.** Die Instanz läuft privat für genau einen Nutzer.
Die Spracherkennung passiert **auf dem Gerät** (Androids eingebauter Recognizer) — es wird nie Audio
verschickt, nur der erkannte Text. Telegram ist weiterhin eingebaut, aber standardmäßig aus.

---

## Inhalt

| Ordner | Inhalt |
|---|---|
| `backend/` | REST-API (Fastify), Telegram-Bot, KI-Pipeline, Prisma-Schema, Tests |
| `android/` | Android-App (Kotlin, Jetpack Compose, Material 3, Room) |
| `docs/` | Architektur, Berechnungsformeln, Design-Vorschau |
| `docker-compose.yml` | PostgreSQL + Backend |

---

## 1. Voraussetzungen

| Software | Version | Wofür |
|---|---|---|
| Node.js | ≥ 20 (empfohlen 22 LTS) | Backend |
| Docker + Docker Compose | aktuell | PostgreSQL (oder eigene Postgres-Instanz ≥ 14) |
| Android Studio | Ladybug (2024.2) oder neuer | App bauen |
| JDK | 17 | wird von Android Studio mitgeliefert |

Telegram-Bot-Token bekommst du bei **@BotFather**, ein OpenAI-Key unter <https://platform.openai.com/api-keys>.

---

## 2. Backend starten (Schritt für Schritt)

### 2.1 Datenbank starten

```bash
docker compose up -d db
```

Ohne Docker: eine Postgres-Datenbank `gymapp` anlegen und `DATABASE_URL` entsprechend setzen.

### 2.2 Konfiguration anlegen

```bash
cd backend
cp .env.example .env
```

`.env` öffnen und ausfüllen:

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `DATABASE_URL` | ja | Bei docker-compose unverändert lassen |
| `JWT_SECRET` | ja | Langer Zufallswert, siehe unten |
| `ANTHROPIC_API_KEY` | ja | Claude wertet die Spracheingabe aus. Ohne Key läuft der eingebaute regelbasierte Parser weiter |
| `LLM_MODEL` | nein | Standard `claude-opus-5` |
| `SINGLE_USER_MODE` | nein | Standard `true` – kein Login, ein lokales Konto wird beim Start angelegt |
| `TELEGRAM_BOT_TOKEN` | nur für den optionalen Bot | Token von @BotFather, zusätzlich `TELEGRAM_ENABLED=true` |
| `OPENAI_API_KEY` | nur für Telegram-Sprachnachrichten | Wird für Whisper gebraucht; die App braucht ihn nicht |
| `DEFAULT_TIMEZONE` | nein | Standard `Europe/Berlin` |
| `TELEGRAM_ALLOWED_USER_IDS` | nein | Harte Whitelist von Telegram-IDs |

JWT-Secret erzeugen:

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

### 2.3 Abhängigkeiten, Datenbank, Seed

```bash
npm install
npx prisma migrate dev --name init
npm run seed
```

`npm run seed` legt 14 Muskelgruppen und ~35 Übungen mit deutschen Namen und Synonymen an — die Basis, damit „Bankdrücken", „Bank drücken" und „Benchpress" alle dieselbe Übung treffen.

### 2.4 Backend starten

```bash
npm run dev
```

Läuft auf <http://localhost:3000>, Health-Check: <http://localhost:3000/health>.

**Alternative — alles in Docker:**

```bash
docker compose up -d --build
```

Migration und Seed laufen dabei automatisch beim Start.

---

## 3. Telegram-Bot (optional)

Standardmäßig **aus** (`TELEGRAM_ENABLED=false`) – die App braucht ihn nicht. Wer ihn trotzdem
will, setzt `TELEGRAM_ENABLED=true`, trägt den Bot-Token ein und folgt den Schritten unten.
Für Telegram-**Sprach**nachrichten wird zusätzlich ein `OPENAI_API_KEY` gebraucht, weil Anthropic
keine Transkriptions-API anbietet; Telegram-**Text**nachrichten laufen über Claude.

### Einrichtung

1. In Telegram **@BotFather** öffnen → `/newbot` → Namen vergeben.
2. Den Token in `backend/.env` als `TELEGRAM_BOT_TOKEN` eintragen.
3. Backend neu starten. Im Log erscheint `Telegram bot polling`.
4. Deinen Bot in Telegram öffnen und `/start` senden.

Der Bot ist standardmäßig im **Polling-Modus** — funktioniert ohne öffentliche IP, auch hinter NAT und zu Hause. Für einen Server mit Domain kannst du auf Webhook umstellen (`TELEGRAM_MODE=webhook` + `TELEGRAM_WEBHOOK_URL` + `TELEGRAM_WEBHOOK_SECRET`).

### Bot-Befehle

| Befehl | Wirkung |
|---|---|
| `/start` | Begrüßung und Verbindungsanleitung |
| `/help` | Beispiele für Sprachnachrichten |
| `/link ABC123` | Telegram mit dem App-Konto verbinden |
| `/today` | Heutiges Training |
| `/history` | Letzte Trainings |
| `/stats` | Statistik-Überblick |
| `/exercises` | Übungskatalog |
| `/cancel` | Offenen Vorschlag verwerfen |

---

## 4. Android-App

### 4.1 Projekt öffnen

Android Studio → **Open** → Ordner `android/` wählen. Beim ersten Öffnen lädt Gradle die Abhängigkeiten.

### 4.2 Server-Adresse konfigurieren

Die App muss wissen, wo dein Backend läuft. Drei Wege:

1. **In der App** (am einfachsten): Auf dem Anmeldebildschirm auf *„Server-Adresse ändern"* tippen.
2. **`android/local.properties`**:
   ```properties
   api.base.url=http://192.168.1.20:3000/
   ```
3. **Build-Parameter**: `./gradlew assembleDebug -PapiBaseUrl=http://192.168.1.20:3000/`

| Situation | Adresse |
|---|---|
| Android-Emulator, Backend auf demselben Rechner | `http://10.0.2.2:3000/` (Standard) |
| Echtes Handy im gleichen WLAN | `http://<lokale-IP-des-PCs>:3000/` |
| Server im Internet | `https://deine-domain.tld/` |

Die lokale IP findest du mit `hostname -I` (Linux) bzw. `ipconfig` (Windows). Klartext-HTTP ist per Netzwerk-Konfiguration nur für lokale Adressen erlaubt — im Internet erzwingt die App HTTPS.

### 4.3 APK bauen

```bash
cd android
./gradlew assembleDebug
```

Die APK liegt danach unter:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

Release-Variante (minifiziert, mit R8):

```bash
./gradlew assembleRelease
```
→ `android/app/build/outputs/apk/release/app-release.apk`

> Die Release-Konfiguration ist mit dem Debug-Keystore signiert, damit sie sich sofort installieren lässt. Für den Play Store einen eigenen Keystore erzeugen und in `app/build.gradle.kts` unter `signingConfigs` eintragen.

> **Hinweis:** Im Repo liegt kein `gradle-wrapper.jar` (Binärdatei). Android Studio ergänzt den Wrapper beim ersten Öffnen automatisch. Auf der Kommandozeile alternativ einmalig `gradle wrapper` ausführen (mit einer installierten Gradle-Version ≥ 8.9) oder direkt `gradle assembleDebug` statt `./gradlew assembleDebug` verwenden.

### 4.4 APK installieren

Per USB mit aktiviertem USB-Debugging:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Oder die APK-Datei aufs Handy kopieren und antippen („Installation aus unbekannten Quellen" muss erlaubt sein).

---

## 5. Erste Schritte in der App

1. App öffnen – **keine Registrierung, kein Login**. Beim ersten Aufruf legt der Server
   automatisch das eine lokale Konto an.
2. Server-Adresse eintragen (siehe 4.2), falls nicht schon per `local.properties` gesetzt.
3. Auf den **Mikrofon-Button** unten rechts tippen, Mikrofonzugriff erlauben und lossprechen:

```
Du:     🎤 "Hab heute Bankdrücken gemacht. Erst 100 Kilo für zehn,
            dann 110 für acht und danach nochmal 110 für sieben."

Claude: ✓ Gespeichert
        Bankdrücken · 100 kg × 10, 110 kg × 8, 110 kg × 7
        Volumen: 2.650 kg · vs. letztes Training: +5,2 %
```

Wenn Claude unsicher ist, fragt es nach, statt etwas Falsches zu speichern. Alles bleibt in der
App nachträglich editierbar.

### Optional: derselbe Workflow über Telegram

```
Du:  🎤 "Hab heute Bankdrücken gemacht. Erst 100 Kilo für zehn,
         dann 110 für acht und danach nochmal 110 für sieben."

Bot: ✓ Gespeichert
     Bankdrücken
     100 kg × 10, 110 kg × 8, 110 kg × 7
     Volumen: 2.650 kg
     vs. letztes Training: +5,2 %
```

Die App aktualisiert sich beim nächsten Öffnen bzw. über **Profil → Jetzt synchronisieren**.

---

## 6. Was der Bot versteht

**Training erfassen**
- „Hab heute drei Sätze Bankdrücken gemacht mit 100 Kilo und jeweils zehn Wiederholungen."
- „Beim Squat 140 Kilo, acht Wiederholungen, vier Sätze."
- „Brust heute: Bankdrücken 100 Kilo 3x10, Schrägbank 80 Kilo 3x8 und Cable Fly 40 Kilo 3x12." → drei Übungen
- „Ich hab heute 20 Minuten auf dem Laufband gemacht."
- „120 Kilo geschafft, sechs Wiederholungen. Danach noch zweimal fünf." → 120×6, 120×5, 120×5

**Datum**
„heute", „gestern", „vorgestern", „am Montag", „letzten Freitag", „am 5. August", „vor 3 Tagen".
Bei unklaren Angaben („letzte Woche") fragt der Bot nach, statt zu raten.

**Neue Übung**
- „Neue Übung: Cable Lateral Raise. Muskelgruppe Schultern."

**Korrektur**
- „Die 120 Kilo waren eigentlich 110."
- „Beim letzten Satz waren es nur 4 Wiederholungen."

**Rückfragen (Kontext bleibt 30 Minuten erhalten)**
```
Du:  "Bankdrücken 100 Kilo."
Bot: "❓ Bankdrücken erkannt. Wie viele Sätze und Wiederholungen?"
Du:  "Drei mal zehn."
Bot: "✓ Gespeichert – Bankdrücken 100 kg × 10 × 3"
```

**Bestätigungssystem** — je nach Sicherheit der KI:
- **hoch** (≥ 85 %): direkt speichern
- **mittel** (50–85 %): Vorschlag mit Buttons *Speichern / Bearbeiten / Abbrechen*
- **niedrig** (< 50 %): Rückfrage statt Speicherung

Die Schwellen stellst du über `AI_AUTOSAVE_THRESHOLD` und `AI_CONFIRM_THRESHOLD` ein.

**Textnachrichten funktionieren genauso wie Sprachnachrichten** — dieselbe Pipeline, nur ohne Transkriptionsschritt.

---

## 7. Tests

```bash
cd backend
npm test
```

- **70 Unit-Tests** laufen ohne Datenbank: Volumen, 1RM, Trends, Streak, Datumserkennung, Zahlwörter, Übungs-Matching, Parser mit deutschen und englischen Eingaben.
- **32 Integrationstests** (API, Auth, Sync, Rekorde, komplette Telegram-Pipeline) brauchen eine laufende, geseedete Datenbank. Ohne DB werden sie automatisch übersprungen.

Mit Datenbank:

```bash
docker compose up -d db
npx prisma migrate deploy && npm run seed
npm test
```

---

## 8. Technische Entscheidungen

| Entscheidung | Begründung |
|---|---|
| **Claude (`claude-opus-5`) als Parser** | Versteht freie deutsche Sprache deutlich besser als Regeln. Angesprochen mit Structured Outputs (`output_config.format`), damit die Antwort immer dem JSON-Schema entspricht, und mit `effort: "low"` – Sätze und Wiederholungen aus einem Satz zu ziehen braucht keine tiefe Reasoning-Runde. Sampling-Parameter werden bewusst nicht gesetzt (Opus 5 lehnt sie ab). |
| **Spracherkennung auf dem Gerät** | Anthropic bietet keine Speech-to-Text-API. Androids eingebauter `SpeechRecognizer` kann Deutsch, kostet nichts, und es verlässt kein Audio das Handy – nur der erkannte Text geht an den Server. |
| **Einzelnutzer-Modus ohne Login** | Für eine private Instanz im eigenen WLAN ist ein Login reine Reibung. Die Auth-Schicht bleibt im Code: `SINGLE_USER_MODE=false` stellt Registrierung und JWT wieder her. |
| **Bot im API-Prozess**, nicht separat | Teilt Datenbankverbindung und Domänenlogik; ein Deploy statt zwei. Der Bot-Code liegt isoliert in `backend/src/bot/`. |
| **Datum wird im Code aufgelöst**, nicht vom LLM | Das Modell liefert nur den wörtlichen Ausdruck („letzten Freitag"), die Auflösung passiert deterministisch in der Zeitzone des Nutzers und ist testbar. Verhindert eine ganze Fehlerklasse. |
| **Regelbasierter Parser als Fallback** | Ohne API-Key oder bei LLM-Ausfall funktioniert das System weiter. Dient gleichzeitig als testbare Referenzimplementierung. |
| **Rekorde werden neu berechnet, nicht fortgeschrieben** | Beim Bearbeiten oder Löschen eines Satzes bleibt die Rekordhistorie korrekt. Bei einem Nutzer vernachlässigbar teuer. |
| **Charts selbst gezeichnet** (Compose Canvas) | Eine Abhängigkeit weniger, volle Kontrolle über Theming, interaktiv per Tap. |
| **Manuelle DI statt Hilt** | Der Abhängigkeitsgraph ist klein; spart Annotation Processing und Buildzeit. |
| **CommonJS im Backend** | Robuster mit Prisma/Fastify/grammy als ESM mit Pfad-Endungen. |
| **Room-Cache mit JSON-Payload** | Kleine Schemafläche, trotzdem echte lokale Datenbank mit Filterspalten. Statistiken rechnet immer der Server. |
| **Ein Training pro Kalendertag** | Passt zum Telegram-Workflow: mehrere Nachrichten am selben Tag ergänzen dieselbe Einheit. |

Details zu allen Formeln: [`docs/CALCULATIONS.md`](docs/CALCULATIONS.md).

---

## 9. Sicherheit

- Passwörter mit **bcrypt** (12 Runden) gehasht.
- **JWT-Access-Token** (15 min) + rotierende Refresh-Tokens; nur der SHA-256-Hash liegt in der Datenbank.
- Tokens auf dem Handy in **EncryptedSharedPreferences** (Android Keystore), nie im Code, nicht im Cloud-Backup.
- **Kein API-Key in der App** — Whisper und LLM werden ausschließlich serverseitig aufgerufen.
- Telegram nur nach **Account-Linking** per Einmalcode; optionale harte Whitelist über `TELEGRAM_ALLOWED_USER_IDS`.
- **Rate Limiting**, Helmet, CORS-Whitelist, Zod-Validierung auf jedem Endpunkt.
- SQL-Injection ausgeschlossen durch Prisma (parametrisierte Queries).
- **Logging redigiert** Tokens, Passwörter und API-Keys.
- Alle Endpunkte prüfen Eigentümerschaft — fremde Trainings liefern 404 statt 403 (kein Information Leak).

---

## 10. Fehlersuche

| Problem | Ursache / Lösung |
|---|---|
| Backend: „Keine Verbindung zur Datenbank" | `docker compose up -d db` läuft nicht oder falsche `DATABASE_URL`. |
| App: „Kein API-Key hinterlegt" in den Einstellungen | `ANTHROPIC_API_KEY` fehlt in `backend/.env`. Ohne ihn läuft nur der regelbasierte Parser. |
| App: Mikrofon reagiert nicht | Mikrofonzugriff in den Android-App-Einstellungen erlauben. Auf Geräten ohne Google-Spracherkennung stattdessen tippen. |
| Bot antwortet nicht | `TELEGRAM_BOT_TOKEN` fehlt/falsch, oder Backend nicht gestartet. Log prüfen: `Telegram bot polling`. |
| Bot: „Dieser Chat ist noch mit keinem Account verbunden" | In der App unter Profil einen Link-Code erzeugen und `/link CODE` senden. |
| „Sprachnachrichten sind nicht aktiviert" | `OPENAI_API_KEY` fehlt. Textnachrichten funktionieren trotzdem. |
| App: „Keine Verbindung zum Server" | Falsche Server-Adresse. Emulator → `10.0.2.2`, echtes Handy → lokale IP des PCs, gleiches WLAN, Firewall Port 3000. |
| App zeigt alte Daten | Profil → *Jetzt synchronisieren*. |
| Gradle-Fehler „SDK location not found" | `android/local.properties` mit `sdk.dir=/pfad/zum/Android/Sdk` anlegen (macht Android Studio automatisch). |

---

## 11. API-Überblick

Alle Endpunkte außer Registrierung/Login/Refresh brauchen `Authorization: Bearer <token>`.

```
POST   /api/auth/register|login|refresh|logout
GET    /api/auth/me                    PATCH /api/auth/me       DELETE /api/auth/me
GET    /api/exercises                  POST  /api/exercises
PATCH  /api/exercises/:id              DELETE /api/exercises/:id
GET    /api/exercises/:id/stats?period=7d|30d|90d|6m|1y|all
GET    /api/muscle-groups
GET    /api/workouts                   POST  /api/workouts
GET    /api/workouts/:id               PATCH /api/workouts/:id   DELETE /api/workouts/:id
POST   /api/workouts/:id/exercises     DELETE /api/workouts/exercises/:id
POST   /api/workouts/exercises/:id/sets
PATCH  /api/workouts/sets/:id          DELETE /api/workouts/sets/:id
GET    /api/stats/dashboard|overview|calendar|volume|records
POST   /api/telegram/link-code         GET /api/telegram/status  DELETE /api/telegram/link
POST   /api/ai/parse                   GET  /api/ai/status
GET    /api/sync?since=...             POST /api/sync
GET    /api/export?format=csv|json
GET    /health
```
