import { describe, expect, it } from 'vitest';
import {
  bestE1rm,
  estimateOneRepMax,
  groupSets,
  isE1rmExtrapolated,
  mean,
  median,
  movingAverage,
  percentChange,
  robustTrend,
  setVolume,
  summarizeSets,
} from '../src/domain/calculations';
import { computeStreak } from '../src/services/statsService';
import { civilDateToUtc } from '../src/lib/dates';

describe('Volumen', () => {
  it('berechnet Gewicht × Wiederholungen', () => {
    expect(setVolume({ weightKg: 100, reps: 10 })).toBe(1000);
  });

  it('ignoriert Sätze ohne Gewicht oder Wiederholungen', () => {
    expect(setVolume({ weightKg: 100, reps: null })).toBe(0);
    expect(setVolume({ weightKg: null, reps: 10 })).toBe(0);
    expect(setVolume({ durationSec: 1200 })).toBe(0);
  });

  it('summiert mehrere Sätze korrekt', () => {
    // 100×10 + 110×8 + 110×7 = 1000 + 880 + 770 = 2650 kg
    const summary = summarizeSets([
      { weightKg: 100, reps: 10 },
      { weightKg: 110, reps: 8 },
      { weightKg: 110, reps: 7 },
    ]);
    expect(summary.volumeKg).toBe(2650);
    expect(summary.sets).toBe(3);
    expect(summary.reps).toBe(25);
  });

  it('kann Aufwärmsätze ausschließen', () => {
    const sets = [
      { weightKg: 60, reps: 10, isWarmup: true },
      { weightKg: 100, reps: 10, isWarmup: false },
    ];
    expect(summarizeSets(sets).volumeKg).toBe(1600);
    expect(summarizeSets(sets, false).volumeKg).toBe(1000);
  });
});

describe('1RM (Epley)', () => {
  it('gibt bei einer Wiederholung das Gewicht selbst zurück', () => {
    expect(estimateOneRepMax(140, 1)).toBe(140);
  });

  it('rechnet nach der Epley-Formel', () => {
    // 100 × (1 + 10/30) = 133.33
    expect(estimateOneRepMax(100, 10)).toBeCloseTo(133.33, 1);
    expect(estimateOneRepMax(120, 8)).toBeCloseTo(152, 1);
  });

  it('deckelt die Wiederholungen bei 12 und meldet das', () => {
    expect(estimateOneRepMax(100, 30)).toBe(estimateOneRepMax(100, 12));
    expect(isE1rmExtrapolated(30)).toBe(true);
    expect(isE1rmExtrapolated(8)).toBe(false);
  });

  it('liefert 0 für unsinnige Eingaben', () => {
    expect(estimateOneRepMax(0, 10)).toBe(0);
    expect(estimateOneRepMax(100, 0)).toBe(0);
    expect(estimateOneRepMax(-50, 5)).toBe(0);
  });

  it('findet den besten Satz', () => {
    const result = bestE1rm([
      { weightKg: 100, reps: 10 }, // 133.3
      { weightKg: 130, reps: 3 }, // 143.0
      { weightKg: 110, reps: 8 }, // 139.3
    ]);
    expect(result.value).toBeCloseTo(143, 0);
    expect(result.set?.weightKg).toBe(130);
  });
});

describe('Statistik-Hilfsfunktionen', () => {
  it('mean und median', () => {
    expect(mean([100, 110, 120])).toBe(110);
    expect(median([100, 110, 300])).toBe(110);
    expect(median([10, 20, 30, 40])).toBe(25);
    expect(median([])).toBe(0);
  });

  it('gleitender Durchschnitt', () => {
    expect(movingAverage([10, 20, 30], 2)).toEqual([10, 15, 25]);
  });

  it('prozentuale Veränderung', () => {
    expect(percentChange(100, 110)).toBe(10);
    expect(percentChange(110, 100)).toBe(-9.1);
    // Kein Nenner -> null statt Infinity
    expect(percentChange(0, 100)).toBeNull();
    expect(percentChange(null, 100)).toBeNull();
  });

  it('robuster Trend nutzt den Median und braucht genug Daten', () => {
    const trend = robustTrend([100, 100, 100], [110, 110, 110]);
    expect(trend.changePercent).toBe(10);
    expect(trend.reliable).toBe(true);
  });

  it('lässt einen Ausreißer den Trend nicht verfälschen', () => {
    // Ein einzelner Rekordsatz von 200 kg darf nicht als +50 % gelten.
    const trend = robustTrend([100, 100, 100, 100], [100, 100, 100, 200]);
    expect(trend.changePercent).toBe(0);
  });

  it('meldet zu wenig Daten als unzuverlässig', () => {
    const trend = robustTrend([100], [120]);
    expect(trend.reliable).toBe(false);
    expect(trend.changePercent).toBeNull();
  });
});

describe('Satz-Gruppierung', () => {
  it('fasst identische Sätze zusammen', () => {
    const groups = groupSets([
      { weightKg: 100, reps: 10 },
      { weightKg: 100, reps: 10 },
      { weightKg: 100, reps: 10 },
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].count).toBe(3);
  });

  it('trennt unterschiedliche Sätze', () => {
    const groups = groupSets([
      { weightKg: 100, reps: 10 },
      { weightKg: 110, reps: 8 },
      { weightKg: 110, reps: 8 },
    ]);
    expect(groups).toHaveLength(2);
    expect(groups[1].count).toBe(2);
  });
});

describe('Trainingsserie', () => {
  const d = (iso: string) => civilDateToUtc(iso);

  it('zählt aufeinanderfolgende aktive Tage', () => {
    const dates = [d('2026-08-09'), d('2026-08-07'), d('2026-08-05'), d('2026-08-03')];
    expect(computeStreak(dates, d('2026-08-09'))).toBe(7);
  });

  it('bricht bei einer zu langen Pause ab', () => {
    const dates = [d('2026-08-09'), d('2026-08-08'), d('2026-07-20')];
    expect(computeStreak(dates, d('2026-08-09'))).toBe(2);
  });

  it('ist 0, wenn das letzte Training zu lange her ist', () => {
    expect(computeStreak([d('2026-07-01')], d('2026-08-09'))).toBe(0);
  });

  it('ist 0 ohne Trainings', () => {
    expect(computeStreak([], d('2026-08-09'))).toBe(0);
  });
});
