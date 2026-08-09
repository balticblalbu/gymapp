# Architektur

```
┌──────────────┐   Sprache/Text   ┌───────────────────────────────────────┐
│  Telegram    │ ───────────────► │  Backend (Node 22 / TypeScript)       │
│  (Handy)     │ ◄─────────────── │                                        │
└──────────────┘   Antwort        │  ┌─────────────────────────────────┐  │
                                   │  │ Bot (grammy)                    │  │
┌──────────────┐   REST/JWT       │  │  ↓                              │  │
│ Android-App  │ ◄──────────────► │  │ SpeechToTextProvider (Whisper)  │  │
│ (Compose)    │                   │  │  ↓                              │  │
│  ↕ Room      │                   │  │ LLMWorkoutParser (OpenAI|Regel) │  │
│  Offline     │                   │  │  ↓                              │  │
└──────────────┘                   │  │ dateResolver + exerciseMatcher  │  │
                                   │  │  ↓                              │  │
                                   │  │ Pipeline: Konfidenz → speichern │  │
                                   │  │   / bestätigen / nachfragen     │  │
                                   │  └─────────────────────────────────┘  │
                                   │  Services  ·  REST API  ·  Prisma     │
                                   └───────────────┬───────────────────────┘
                                                   ▼
                                            ┌─────────────┐
                                            │ PostgreSQL  │
                                            └─────────────┘
```

## Backend-Schichten

| Ordner | Verantwortung |
|---|---|
| `src/config` | Validierte Konfiguration (zod), Start bricht bei Fehlern sofort ab |
| `src/lib` | Logger (mit Redaktion), Prisma-Client, Fehlertypen, Datums-/Einheiten-Helfer |
| `src/domain` | Reine Mathematik: Volumen, 1RM, Trends — keine Datenbank, voll testbar |
| `src/ai` | Austauschbare Provider (`SpeechToTextProvider`, `LLMWorkoutParser`), deterministische Datums- und Übungserkennung, Pipeline |
| `src/services` | Anwendungslogik gegen die Datenbank (Workouts, Übungen, Statistik, Rekorde, Sync, Auth, Linking) |
| `src/routes` | HTTP-Schicht: Validierung, Serialisierung, Berechtigung |
| `src/bot` | Telegram-Handler, Formatierung der Antworten |

## Die KI-Pipeline im Detail

1. **Nachricht empfangen** — Sprache oder Text. Jede Nachricht wird in `TelegramMessage` protokolliert.
2. **Transkription** (nur bei Sprache) über den konfigurierten `SpeechToTextProvider`.
3. **Parsing** über den `LLMWorkoutParser`. Das Modell liefert ein striktes JSON-Schema; jede Antwort wird mit zod nachvalidiert. Bei Ausfall übernimmt der regelbasierte Parser.
4. **Datum auflösen** — deterministisch im Code, in der Zeitzone des Nutzers.
5. **Übung zuordnen** — Fuzzy-Matching gegen Name, deutschen Namen und Aliase. Drei Ausgänge: sicher zuordnen / nachfragen / neu anlegen.
6. **Konfidenz bewerten** — Modellkonfidenz kombiniert mit Vollständigkeit (fehlen Sätze/Wiederholungen?).
7. **Entscheiden**: speichern · Bestätigung mit Buttons · Rückfrage.
8. **Speichern** und Rekorde neu berechnen; die Antwort enthält Volumen, Vergleich zum letzten Training und neue Rekorde.
9. **Audit-Trail**: `AiParsingResult` hält Eingabetext, Rohantwort, normalisiertes Ergebnis, Konfidenz und Status. Jeder Satz verlinkt auf den Eintrag, aus dem er entstand.

Der Gesprächskontext (`ConversationState`) lebt 30 Minuten und trägt Rückfragen, offene Bestätigungen und die zuletzt gespeicherten Einträge für Korrekturen.

## Synchronisation

- **Pull**: `GET /api/sync?since=…` liefert alles, was sich seit dem Zeitstempel geändert hat — inklusive Tombstones (`deletedAt`), damit der Client lokal löschen kann.
- **Push**: `POST /api/sync` mit einer Liste idempotenter Upserts. Die IDs erzeugt der Client (UUID), ein wiederholter Push ist dadurch harmlos.
- **Konflikte**: Last-Write-Wins über `updatedAt`. Ist der Server neuer als der Stand, von dem der Client ausging, wird die Änderung abgelehnt und als `conflict` gemeldet — so überschreibt die App keine Eingabe, die zwischenzeitlich per Telegram kam.

## Android

- **MVVM**: `ViewModel` + `StateFlow`, Screens sind zustandslos und bekommen einen UI-State.
- **Repositories** kapseln „Netzwerk zuerst, Cache als Rückfall"; jede Antwort trägt ein `fromCache`-Flag, das die App als Offline-Banner anzeigt.
- **Room** als Offline-Cache; Statistiken rechnet immer der Server, damit App und Bot dieselben Zahlen zeigen.
- **Manuelle DI** über `AppContainer`.
