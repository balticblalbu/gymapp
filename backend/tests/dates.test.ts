import { describe, expect, it } from 'vitest';
import { resolveDateExpression } from '../src/ai/dateResolver';
import { civilDateToUtc, daysBetween, periodStart, todayInZone, utcToCivilDate } from '../src/lib/dates';

// Reference: Sunday, 9 August 2026, 20:30 Berlin time.
const NOW = new Date('2026-08-09T18:30:00.000Z');
const options = { timezone: 'Europe/Berlin', now: NOW };

function resolve(expression: string | null) {
  return resolveDateExpression(expression, options).isoDate;
}

describe('Datumsauflösung', () => {
  it('heute / today', () => {
    expect(resolve('heute')).toBe('2026-08-09');
    expect(resolve('today')).toBe('2026-08-09');
  });

  it('ohne Angabe wird heute angenommen', () => {
    const result = resolveDateExpression(null, options);
    expect(result.isoDate).toBe('2026-08-09');
    expect(result.confidence).toBeGreaterThan(0.8);
    expect(result.ambiguous).toBe(false);
  });

  it('gestern und vorgestern', () => {
    expect(resolve('gestern')).toBe('2026-08-08');
    expect(resolve('yesterday')).toBe('2026-08-08');
    expect(resolve('vorgestern')).toBe('2026-08-07');
  });

  it('Wochentage beziehen sich auf die Vergangenheit', () => {
    // 9.8.2026 ist ein Sonntag -> letzter Freitag war der 7.8.
    expect(resolve('am Freitag')).toBe('2026-08-07');
    expect(resolve('am Montag')).toBe('2026-08-03');
    expect(resolve('on Wednesday')).toBe('2026-08-05');
  });

  it('"letzten Freitag" springt eine Woche zurück, wenn heute Freitag wäre', () => {
    const friday = { timezone: 'Europe/Berlin', now: new Date('2026-08-07T10:00:00.000Z') };
    expect(resolveDateExpression('am Freitag', friday).isoDate).toBe('2026-08-07');
    expect(resolveDateExpression('letzten Freitag', friday).isoDate).toBe('2026-07-31');
  });

  it('explizite Datumsangaben', () => {
    expect(resolve('am 5. August')).toBe('2026-08-05');
    expect(resolve('August 5')).toBe('2026-08-05');
    expect(resolve('05.08.2026')).toBe('2026-08-05');
    expect(resolve('2026-08-05')).toBe('2026-08-05');
  });

  it('ein zukünftiges Datum ohne Jahr gehört ins Vorjahr', () => {
    // Der 20. Dezember liegt in der Zukunft -> gemeint ist der letzte Dezember.
    expect(resolve('am 20. Dezember')).toBe('2025-12-20');
  });

  it('relative Angaben in Tagen', () => {
    expect(resolve('vor 3 Tagen')).toBe('2026-08-06');
    expect(resolve('3 days ago')).toBe('2026-08-06');
  });

  it('markiert unklare Zeiträume als mehrdeutig statt zu raten', () => {
    const result = resolveDateExpression('letzte Woche', options);
    expect(result.ambiguous).toBe(true);
    expect(result.isoDate).toBeNull();
    expect(result.reason).toBe('ambiguous_range');
  });

  it('meldet unverständliche Angaben', () => {
    const result = resolveDateExpression('irgendwann mal so', options);
    expect(result.ambiguous).toBe(true);
    expect(result.isoDate).toBeNull();
  });

  it('respektiert die Zeitzone bei Datumsgrenzen', () => {
    // 23:30 UTC ist in Berlin bereits der Folgetag.
    const late = new Date('2026-08-09T23:30:00.000Z');
    expect(resolveDateExpression('heute', { timezone: 'Europe/Berlin', now: late }).isoDate).toBe('2026-08-10');
    expect(resolveDateExpression('heute', { timezone: 'UTC', now: late }).isoDate).toBe('2026-08-09');
  });
});

describe('Datums-Hilfsfunktionen', () => {
  it('konvertiert verlustfrei', () => {
    expect(utcToCivilDate(civilDateToUtc('2026-08-09'))).toBe('2026-08-09');
  });

  it('todayInZone berücksichtigt die Zeitzone', () => {
    const late = new Date('2026-08-09T23:30:00.000Z');
    expect(utcToCivilDate(todayInZone('Europe/Berlin', late))).toBe('2026-08-10');
    expect(utcToCivilDate(todayInZone('America/New_York', late))).toBe('2026-08-09');
  });

  it('daysBetween zählt Kalendertage', () => {
    expect(daysBetween(civilDateToUtc('2026-08-01'), civilDateToUtc('2026-08-09'))).toBe(8);
  });

  it('periodStart liefert inklusive Startdaten', () => {
    const today = civilDateToUtc('2026-08-09');
    expect(utcToCivilDate(periodStart('7d', today) as Date)).toBe('2026-08-03');
    expect(utcToCivilDate(periodStart('30d', today) as Date)).toBe('2026-07-11');
    expect(periodStart('all', today)).toBeNull();
  });
});
