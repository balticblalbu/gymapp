# Berechnungen – transparent dokumentiert

Alle Formeln stehen in `backend/src/domain/calculations.ts` und `backend/src/services/statsService.ts` und sind in `backend/tests/calculations.test.ts` abgesichert.

## Volumen

```
Satzvolumen  = Gewicht × Wiederholungen
Gesamtvolumen = Σ (Gewicht × Wiederholungen)
```

Nur Sätze mit **Gewicht und Wiederholungen** zählen ins Kilogramm-Volumen. Körpergewichtsübungen ohne Zusatzgewicht fließen über die Wiederholungszahl ein — sonst würden 20 Liegestütze die Tonnage einer Bankdrück-Einheit verfälschen.

Beispiel: 100×10 + 110×8 + 110×7 = 1.000 + 880 + 770 = **2.650 kg**

## Geschätztes 1RM (Epley)

```
1RM = Gewicht × (1 + Wiederholungen / 30)
```

- Bei 1 Wiederholung wird das Gewicht selbst zurückgegeben.
- **Ab 12 Wiederholungen wird gedeckelt**, weil die Formel darüber deutlich überschätzt. Die App markiert solche Werte als extrapoliert.
- Ein **echter 1RM-Versuch** (`isOneRmTest`) wird separat gespeichert und nie mit einer Schätzung vermischt.

## Fortschritt

Naiv wäre: Durchschnitt jetzt gegen Durchschnitt vorher. Das ist anfällig — ein einziger starker Satz sieht dann wie langfristiger Fortschritt aus. Stattdessen:

1. **Pro Satz ein Leistungswert** (`performanceMetric`):
   - Kraft → geschätztes 1RM (fasst Gewicht *und* Wiederholungen in einer Zahl)
   - Körpergewicht → 1RM bei Zusatzgewicht, sonst Wiederholungen
   - Ausdauer → Geschwindigkeit (m/s), sonst Distanz
   - Zeit → Sekunden
2. **Median** statt Mittelwert im aktuellen und im gleich langen vorherigen Fenster.
3. **Mindestens 2 Sätze** in beiden Fenstern, sonst wird kein Trend ausgewiesen (`reliable: false` → die App zeigt „–").
4. Prozentuale Veränderung nur bei sinnvollem Nenner; sonst `null` statt `∞`.

Jede Übung wird also **mit sich selbst** verglichen, nie mit einer anderen.

## Muskelgruppen-Fortschritt

```
Fortschritt(Muskelgruppe) = Σ (Übungstrend × Beitrag) / Σ Beitrag
```

Gewichtet nach Anzahl der Sätze und dem Beitragsfaktor der Übung (primär 1,0 / sekundär 0,4). Es wird **kein Rohvolumen zwischen verschiedenen Übungen verglichen** — Bankdrücken-Kilos und Cable-Fly-Kilos sind nicht dieselbe Währung. Untergruppen rollen auf (Bizeps/Trizeps → Arme, Quadrizeps/Beinbeuger → Beine).

## Persönliche Rekorde

Sieben Typen: höchstes Gewicht, meiste Wiederholungen, bestes Satz-Volumen, bestes Trainings-Volumen, bestes geschätztes 1RM, längste Dauer, größte Distanz.

Rekorde werden **nicht fortgeschrieben, sondern neu berechnet**: die komplette Historie einer Übung wird chronologisch durchlaufen und jede Verbesserung als Rekord gespeichert. Dadurch bleibt die Historie korrekt, wenn ein Satz nachträglich geändert oder gelöscht wird. Aufwärmsätze zählen nicht.

## Trainingsserie (Streak)

Aufeinanderfolgende aktive Tage, wobei eine Pause von **bis zu 3 Tagen** die Serie nicht bricht (sonst wäre die Serie fast immer 1, weil Ruhetage zum Training gehören). Liegt das letzte Training länger als 3 Tage zurück, ist die Serie 0.

## Zeiträume

`7d`, `30d`, `90d`, `6m`, `1y`, `all` — jeweils inklusive Starttag. Das Vergleichsfenster ist exakt gleich lang und liegt direkt davor.

## Einheiten

Intern wird **alles in Kilogramm und Metern** gespeichert; Pfund/Meilen sind reine Anzeigeoptionen. Umrechnung: 1 kg = 2,2046226218 lb.
