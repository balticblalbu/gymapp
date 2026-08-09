import { DateTime } from 'luxon';

/**
 * Calendar days are stored as `@db.Date`. Postgres/Prisma hand those back as a
 * JS Date at UTC midnight, so all conversions go through these helpers to avoid
 * the classic "workout jumped one day" timezone bug.
 */

/** "2026-08-09" -> Date(2026-08-09T00:00:00Z) */
export function civilDateToUtc(isoDate: string): Date {
  const dt = DateTime.fromISO(isoDate, { zone: 'utc' });
  if (!dt.isValid) throw new Error(`Invalid ISO date: ${isoDate}`);
  return dt.startOf('day').toJSDate();
}

/** Date(2026-08-09T00:00:00Z) -> "2026-08-09" */
export function utcToCivilDate(date: Date): string {
  return DateTime.fromJSDate(date, { zone: 'utc' }).toISODate() as string;
}

/** Today's calendar date in the user's timezone, as a UTC-midnight Date. */
export function todayInZone(timezone: string, now: Date = new Date()): Date {
  const zone = DateTime.fromJSDate(now).setZone(timezone);
  const iso = (zone.isValid ? zone : DateTime.fromJSDate(now).setZone('utc')).toISODate() as string;
  return civilDateToUtc(iso);
}

export function addDays(date: Date, days: number): Date {
  return DateTime.fromJSDate(date, { zone: 'utc' }).plus({ days }).toJSDate();
}

export function startOfWeekUtc(date: Date): Date {
  return DateTime.fromJSDate(date, { zone: 'utc' }).startOf('week').toJSDate();
}

export function startOfMonthUtc(date: Date): Date {
  return DateTime.fromJSDate(date, { zone: 'utc' }).startOf('month').toJSDate();
}

export function isValidTimezone(tz: string): boolean {
  return DateTime.now().setZone(tz).isValid;
}

export function daysBetween(a: Date, b: Date): number {
  const diff = DateTime.fromJSDate(b, { zone: 'utc' }).diff(DateTime.fromJSDate(a, { zone: 'utc' }), 'days').days;
  return Math.round(diff);
}

/** Human readable, locale aware day label used in bot replies. */
export function formatDayLabel(date: Date, locale: string): string {
  return DateTime.fromJSDate(date, { zone: 'utc' })
    .setLocale(locale)
    .toLocaleString({ weekday: 'long', day: 'numeric', month: 'long' });
}

export const PERIODS = ['7d', '30d', '90d', '6m', '1y', 'all'] as const;
export type Period = (typeof PERIODS)[number];

/** Inclusive start date of a period relative to `reference`; null = all time. */
export function periodStart(period: Period, reference: Date): Date | null {
  const ref = DateTime.fromJSDate(reference, { zone: 'utc' });
  switch (period) {
    case '7d':
      return ref.minus({ days: 6 }).toJSDate();
    case '30d':
      return ref.minus({ days: 29 }).toJSDate();
    case '90d':
      return ref.minus({ days: 89 }).toJSDate();
    case '6m':
      return ref.minus({ months: 6 }).plus({ days: 1 }).toJSDate();
    case '1y':
      return ref.minus({ years: 1 }).plus({ days: 1 }).toJSDate();
    case 'all':
      return null;
  }
}

/** Length of a period in days, used to build the comparison window. */
export function periodLengthDays(period: Period, reference: Date): number | null {
  const start = periodStart(period, reference);
  if (!start) return null;
  return daysBetween(start, reference) + 1;
}
