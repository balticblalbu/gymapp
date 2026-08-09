import { describe, expect, it } from 'vitest';
import { normalizeNumberWords, parseGermanNumberWord } from '../src/ai/numberWords';
import { parseHeuristically, parseSegment, extractMuscleGroups, findKnownExercises } from '../src/ai/llm/heuristicParser';
import { decideMatch, levenshtein, matchExercise, normalizeName } from '../src/ai/exerciseMatcher';
import type { ParseContext } from '../src/ai/types';

const CATALOGUE = [
  'Bench Press', 'Bankdrücken', 'bankdruecken',
  'Squat', 'Kniebeuge',
  'Barbell Row', 'Langhantelrudern', 'Rudern',
  'Biceps Curl', 'Bizepscurl',
  'Lat Pulldown', 'Latzug',
  'Incline Bench Press', 'Schrägbankdrücken', 'Schrägbank',
  'Cable Fly', 'Kabelzug-Fliegende',
  'Treadmill', 'Laufband',
];

const context: ParseContext = {
  todayIso: '2026-08-09',
  timezone: 'Europe/Berlin',
  locale: 'de',
  knownExercises: CATALOGUE,
};

describe('Zahlwörter', () => {
  it('erkennt einfache deutsche Zahlwörter', () => {
    expect(parseGermanNumberWord('zehn')).toBe(10);
    expect(parseGermanNumberWord('drei')).toBe(3);
    expect(parseGermanNumberWord('zwölf')).toBe(12);
  });

  it('erkennt zusammengesetzte deutsche Zahlwörter', () => {
    expect(parseGermanNumberWord('hundert')).toBe(100);
    expect(parseGermanNumberWord('einhundertzehn')).toBe(110);
    expect(parseGermanNumberWord('hundertzwanzig')).toBe(120);
    expect(parseGermanNumberWord('zweiundzwanzig')).toBe(22);
    expect(parseGermanNumberWord('fünfundvierzig')).toBe(45);
  });

  it('ersetzt Zahlwörter im Satz durch Ziffern', () => {
    expect(normalizeNumberWords('hundert Kilo für zehn Wiederholungen')).toBe('100 Kilo für 10 Wiederholungen');
    expect(normalizeNumberWords('drei Sätze')).toBe('3 Sätze');
  });

  it('versteht englische Zahlenketten', () => {
    expect(normalizeNumberWords('one hundred twenty kilos')).toBe('120 kilos');
    expect(normalizeNumberWords('twenty-five reps')).toBe('25 reps');
  });

  it('lässt normale Wörter unangetastet', () => {
    expect(normalizeNumberWords('Beine und Rücken')).toBe('Beine und Rücken');
    expect(normalizeNumberWords('Bankdrücken')).toBe('Bankdrücken');
  });
});

describe('Übungs-Matching', () => {
  it('normalisiert Namen einheitlich', () => {
    expect(normalizeName('Bankdrücken')).toBe('bankdruecken');
    expect(normalizeName('T-Bar Row')).toBe('t bar row');
  });

  it('berechnet Levenshtein-Distanzen', () => {
    expect(levenshtein('squat', 'squat')).toBe(0);
    expect(levenshtein('squat', 'squats')).toBe(1);
  });

  it('findet die richtige Übung trotz Schreibweise', () => {
    const candidates = [
      { id: '1', name: 'Bench Press', nameDe: 'Bankdrücken', aliases: ['bankdruecken', 'benchpress'] },
      { id: '2', name: 'Squat', nameDe: 'Kniebeuge', aliases: ['kniebeuge'] },
    ];
    expect(matchExercise('Bankdrücken', candidates)[0].candidate.id).toBe('1');
    expect(matchExercise('bank drücken', candidates)[0].candidate.id).toBe('1');
    expect(matchExercise('Kniebeugen', candidates)[0].candidate.id).toBe('2');
  });

  it('akzeptiert eindeutige Treffer und schlägt unklare nur vor', () => {
    const candidates = [
      { id: '1', name: 'Bench Press', nameDe: 'Bankdrücken', aliases: ['bankdruecken'] },
      { id: '2', name: 'Leg Press', nameDe: 'Beinpresse', aliases: ['beinpresse'] },
    ];
    expect(decideMatch('Bankdrücken', candidates).kind).toBe('accept');
    expect(decideMatch('Nordic Hamstring Curl', candidates).kind).toBe('create');
  });
});

describe('Satz-Erkennung', () => {
  it('3 Sätze mit 100 kg und 10 Wiederholungen', () => {
    const result = parseSegment('mit 100 Kilo und jeweils zehn Wiederholungen, drei Sätze');
    expect(result.sets).toHaveLength(3);
    expect(result.sets[0]).toMatchObject({ weightKg: 100, reps: 10 });
  });

  it('Kurzschreibweise 3x10', () => {
    const result = parseSegment('100 Kilo 3x10');
    expect(result.sets).toHaveLength(3);
    expect(result.sets[0]).toMatchObject({ weightKg: 100, reps: 10 });
  });

  it('vier Sätze, acht Wiederholungen, 140 Kilo – Reihenfolge egal', () => {
    const result = parseSegment('140 Kilo, acht Wiederholungen, vier Sätze');
    expect(result.sets).toHaveLength(4);
    expect(result.sets[0]).toMatchObject({ weightKg: 140, reps: 8 });
  });

  it('drei Sätze à zehn', () => {
    const result = parseSegment('100 kg, drei Sätze à zehn');
    expect(result.sets).toHaveLength(3);
    expect(result.sets[0].reps).toBe(10);
  });

  it('unterschiedliche Sätze nacheinander', () => {
    const result = parseSegment('erst 100 für zehn, dann 110 für acht und danach nochmal 110 für sieben');
    expect(result.sets).toHaveLength(3);
    expect(result.sets[0]).toMatchObject({ weightKg: 100, reps: 10 });
    expect(result.sets[1]).toMatchObject({ weightKg: 110, reps: 8 });
    expect(result.sets[2]).toMatchObject({ weightKg: 110, reps: 7 });
  });

  it('Gewicht wird auf Folgesätze übertragen', () => {
    // "120 Kilo, sechs Wiederholungen. Danach noch zweimal fünf."
    const result = parseSegment('120 Kilo für sechs, danach noch zwei mal fünf');
    expect(result.sets).toHaveLength(3);
    expect(result.sets.map((s) => s.reps)).toEqual([6, 5, 5]);
    expect(result.sets.every((s) => s.weightKg === 120)).toBe(true);
  });

  it('Cardio: 20 Minuten', () => {
    const result = parseSegment('20 Minuten gemacht');
    expect(result.sets).toHaveLength(1);
    expect(result.sets[0].durationSec).toBe(1200);
  });

  it('Cardio: 5 Kilometer', () => {
    const result = parseSegment('5 km gelaufen');
    expect(result.sets[0].distanceM).toBe(5000);
  });

  it('Pfund werden nach Kilogramm umgerechnet', () => {
    const result = parseSegment('225 lbs für 5 Wiederholungen');
    expect(result.sets[0].weightKg).toBeCloseTo(102.06, 1);
  });

  it('markiert fehlende Wiederholungen mit niedriger Konfidenz', () => {
    const result = parseSegment('mit 100 Kilo gemacht');
    expect(result.sets[0].reps).toBeNull();
    expect(result.confidence).toBeLessThan(0.5);
  });
});

describe('Muskelgruppen und Katalog', () => {
  it('erkennt deutsche Muskelgruppen', () => {
    expect(extractMuscleGroups('Muskelgruppe Schultern')).toContain('shoulders');
    expect(extractMuscleGroups('Brust und Trizeps')).toEqual(expect.arrayContaining(['chest', 'triceps']));
  });

  it('findet Katalogübungen im Satz', () => {
    const hits = findKnownExercises('Heute Bankdrücken und danach Rudern', CATALOGUE);
    expect(hits.map((h) => h.name.toLowerCase())).toEqual(expect.arrayContaining(['bankdrücken', 'rudern']));
  });
});

describe('Regelbasierter Parser – deutsche Eingaben', () => {
  it('Beispiel 1: Standardmeldung', () => {
    const result = parseHeuristically(
      'Hab heute drei Sätze Bankdrücken gemacht mit 100 Kilo und jeweils zehn Wiederholungen.',
      context,
    );
    expect(result.intent).toBe('log_workout');
    expect(result.dateExpression).toMatch(/heute/i);
    expect(result.exercises).toHaveLength(1);
    expect(result.exercises[0].sets).toHaveLength(3);
    expect(result.exercises[0].sets[0]).toMatchObject({ weightKg: 100, reps: 10 });
  });

  it('Beispiel 2: Squat mit vier Sätzen', () => {
    const result = parseHeuristically('Beim Squat 140 Kilo, acht Wiederholungen, vier Sätze.', context);
    expect(result.exercises[0].sets).toHaveLength(4);
    expect(result.exercises[0].sets[0]).toMatchObject({ weightKg: 140, reps: 8 });
  });

  it('Beispiel 3: Cardio', () => {
    const result = parseHeuristically('Ich hab heute 20 Minuten auf dem Laufband gemacht.', context);
    expect(result.exercises[0].sets[0].durationSec).toBe(1200);
  });

  it('Beispiel 4: mehrere Übungen in einer Nachricht', () => {
    const result = parseHeuristically(
      'Brust heute: Bankdrücken 100 Kilo 3x10, Schrägbank 80 Kilo 3x8 und Cable Fly 40 Kilo 3x12.',
      context,
    );
    expect(result.exercises.length).toBeGreaterThanOrEqual(3);
    const bench = result.exercises.find((e) => /bank/i.test(e.name));
    expect(bench?.sets).toHaveLength(3);
    expect(bench?.sets[0]).toMatchObject({ weightKg: 100, reps: 10 });
  });

  it('Beispiel 5: freie Formulierung mit wechselndem Gewicht', () => {
    const result = parseHeuristically(
      'Hab heute beim Bankdrücken erst 100 Kilo für zehn, dann 110 für acht und danach nochmal 110 für sieben gemacht.',
      context,
    );
    expect(result.exercises[0].sets).toHaveLength(3);
    expect(result.exercises[0].sets.map((s) => s.reps)).toEqual([10, 8, 7]);
  });

  it('Beispiel 6: Bizeps-Curl', () => {
    const result = parseHeuristically('Beim Bizeps-Curl heute 16 Kilo, zwölf Wiederholungen für drei Sätze.', context);
    expect(result.exercises[0].sets).toHaveLength(3);
    expect(result.exercises[0].sets[0]).toMatchObject({ weightKg: 16, reps: 12 });
  });

  it('fragt nach, wenn Sätze und Wiederholungen fehlen', () => {
    const result = parseHeuristically('Bankdrücken mit 100 Kilo gemacht.', context);
    expect(result.exercises[0].sets[0].weightKg).toBe(100);
    expect(result.exercises[0].sets[0].reps).toBeNull();
    expect(result.clarificationQuestion).toBeTruthy();
    expect(result.confidence).toBeLessThan(0.5);
  });

  it('legt neue Übungen an', () => {
    const result = parseHeuristically('Neue Übung: Cable Lateral Raise. Muskelgruppe Schultern.', context);
    expect(result.intent).toBe('create_exercise');
    expect(result.newExercises[0].name).toMatch(/cable lateral raise/i);
    expect(result.newExercises[0].muscleGroups).toContain('shoulders');
  });

  it('erkennt Gewichtskorrekturen', () => {
    const result = parseHeuristically('Die 120 Kilo waren eigentlich 110.', context);
    expect(result.intent).toBe('correction');
    expect(result.corrections[0]).toMatchObject({ field: 'weight', valueNumber: 110, matchPreviousNumber: 120 });
  });

  it('erkennt das Datum "gestern"', () => {
    const result = parseHeuristically('Gestern Kniebeuge 100 Kilo 3x10', context);
    expect(result.dateExpression).toMatch(/gestern/i);
  });
});

describe('Regelbasierter Parser – englische Eingaben', () => {
  it('bench press with three sets', () => {
    const result = parseHeuristically('Today I did bench press, three sets of ten reps with one hundred kilos.', context);
    expect(result.intent).toBe('log_workout');
    expect(result.exercises[0].sets).toHaveLength(3);
    expect(result.exercises[0].sets[0]).toMatchObject({ weightKg: 100, reps: 10 });
  });

  it('squat 4x8', () => {
    const result = parseHeuristically('Squat 140 kg 4 x 8', context);
    expect(result.exercises[0].sets).toHaveLength(4);
    expect(result.exercises[0].sets[0].reps).toBe(8);
  });

  it('treadmill cardio', () => {
    const result = parseHeuristically('20 minutes on the treadmill today', context);
    expect(result.exercises[0].sets[0].durationSec).toBe(1200);
  });
});
