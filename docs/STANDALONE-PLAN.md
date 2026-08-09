# Umbau: App ohne Server

Ziel: Die Android-App läuft eigenständig. Keine Backend-Instanz, kein PostgreSQL, kein PC im WLAN.
Room ist die Wahrheit, die Statistiken rechnet die App, und Claude wird direkt von der App aufgerufen
— mit einem API-Key, den der Nutzer in den Einstellungen einträgt (verschlüsselt im Android Keystore,
**nicht** in die APK kompiliert).

Das Backend bleibt im Repo. Es ist weiterhin die Referenzimplementierung der Berechnungen, hat die
Tests, und wer später doch synchronisieren will (zweites Gerät, Telegram), schaltet es wieder ein.

## Vorgehen: additiv, nie ein kaputter Baum

Jede Phase endet mit einer baubaren App. Erst in Phase 4 wird umgeschaltet.

| Phase | Inhalt | Status |
|---|---|---|
| 1 | Reine Logik nach Kotlin: `core/domain/Calculations.kt` — Volumen, Epley-1RM, Median-Trends, Gruppierung | **fertig**, 18 Unit-Tests grün |
| 2 | Room wird vollwertig: Entities für Übungen, Muskelgruppen, Trainings, Sätze, Rekorde + Seed | offen |
| 3 | `data/ai/ClaudeClient.kt` — direkter Anthropic-Aufruf, Prompt + JSON-Schema, Datumsauflösung, Übungs-Matching | offen |
| 4 | Repositories auf lokal umstellen; Netzwerkschicht entfernen; Einstellungen um API-Key-Feld ergänzen | offen |
| 5 | Unit-Tests der portierten Logik (JVM, `app/src/test/`) gegen dieselben Fälle wie im Backend | läuft mit (Phase 1 abgedeckt) |

## Was 1:1 portiert wird

Diese Backend-Dateien sind die Vorlage — die Formeln dürfen sich **nicht** unterscheiden, sonst zeigen
alte und neue Daten verschiedene Zahlen:

| Backend (TypeScript) | Android (Kotlin) |
|---|---|
| `src/domain/calculations.ts` | `core/domain/Calculations.kt` ✅ |
| `src/services/statsService.ts` | `core/domain/Statistics.kt` |
| `src/services/recordService.ts` | `core/domain/Records.kt` |
| `src/ai/dateResolver.ts` | `core/ai/DateResolver.kt` |
| `src/ai/exerciseMatcher.ts` | `core/ai/ExerciseMatcher.kt` |
| `src/ai/numberWords.ts` | `core/ai/NumberWords.kt` |
| `src/ai/llm/prompt.ts` + `schema.ts` | `data/ai/ClaudePrompt.kt` |
| `prisma/seed.ts` | `data/local/Seed.kt` |
| `tests/calculations.test.ts`, `dates.test.ts` | `app/src/test/…` |

Die dokumentierten Formeln stehen in [`CALCULATIONS.md`](CALCULATIONS.md) und gelten unverändert.

## Claude direkt aus der App

`POST https://api.anthropic.com/v1/messages`

```
x-api-key: <Key aus den Einstellungen>
anthropic-version: 2023-06-01
content-type: application/json
```

```json
{
  "model": "claude-opus-5",
  "max_tokens": 4096,
  "system": "<Prompt aus ClaudePrompt.kt>",
  "messages": [{ "role": "user", "content": "<erkannter Text>" }],
  "output_config": {
    "effort": "low",
    "format": { "type": "json_schema", "schema": { /* wie im Backend */ } }
  }
}
```

Wichtig, sonst gibt es einen 400:

- **Keine** `temperature` / `top_p` / `top_k` — Claude Opus 5 lehnt Sampling-Parameter ab.
- `effort` gehört **in** `output_config`, nicht auf die oberste Ebene.
- Vor dem Auslesen von `content` immer `stop_reason` prüfen: bei `"refusal"` ist `content` leer.

Der Key wird **nie** einkompiliert. Er kommt aus einem Feld in den Einstellungen und landet im
bestehenden `TokenStore` (EncryptedSharedPreferences, Android Keystore).

## Was dabei verloren geht — bewusst akzeptiert

- **Daten liegen nur auf dem Handy.** Sicherung läuft über den vorhandenen CSV/JSON-Export.
  Vor einem Gerätewechsel exportieren.
- **Kein zweites Gerät**, keine Telegram-Anbindung — beides bräuchte wieder den Server.
- **Der Key liegt auf dem Gerät.** Verschlüsselt und nur für dich; bei Verlust des Handys in der
  Anthropic Console rotieren.

## Wenn du das Backend doch wieder willst

Es bleibt lauffähig: `docker compose up -d --build`, `SINGLE_USER_MODE=true` ist gesetzt.
Die App bekäme dann wieder eine Netzwerkschicht — deshalb wird sie in Phase 4 entfernt und nicht
gelöscht, sondern bleibt in der Git-Historie auffindbar.


## Nächster Schritt

Phase 2: `core/domain/Statistics.kt` + `Records.kt` und die Room-Entities. Vorlage sind
`backend/src/services/statsService.ts` und `recordService.ts`. Danach Phase 3 (Claude-Client),
zuletzt Phase 4 (Umschalten) — die App baut bis dahin unverändert gegen das Backend.

Ausführen der portierten Tests:

```bash
cd android && gradle testDebugUnitTest
```
