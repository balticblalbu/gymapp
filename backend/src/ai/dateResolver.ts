import { DateTime } from 'luxon';
import { civilDateToUtc } from '../lib/dates';
import { normalizeWord } from './numberWords';

/**
 * Deterministic date resolution.
 *
 * The LLM is *not* trusted to compute dates – it only extracts the verbatim
 * date expression ("letzten Freitag", "am 5. August"). Resolving that string
 * happens here, in the user's timezone, with unit tests. This removes a whole
 * class of "the model thinks today is 2024" bugs.
 */

export interface DateResolution {
  /** ISO calendar date "YYYY-MM-DD", or null when it could not be resolved. */
  isoDate: string | null;
  /** UTC-midnight Date for database storage. */
  date: Date | null;
  confidence: number;
  ambiguous: boolean;
  /** Machine readable reason, used to build the clarification question. */
  reason?: 'empty' | 'unparsable' | 'ambiguous_range' | 'future';
  matchedExpression?: string;
}

/**
 * Luxon weekday numbers (Monday = 1 … Sunday = 7).
 *
 * Two letter abbreviations (mo, di, mi, do, so) are deliberately NOT listed:
 * they collide with ordinary German words ("so", "mit", "do") and would turn
 * "irgendwann mal so" into a Sunday.
 */
const WEEKDAYS: Record<string, number> = {
  montag: 1, monday: 1, mon: 1,
  dienstag: 2, tuesday: 2, tue: 2, tues: 2,
  mittwoch: 3, wednesday: 3, wed: 3,
  donnerstag: 4, thursday: 4, thu: 4, thur: 4, thurs: 4,
  freitag: 5, friday: 5, fri: 5,
  samstag: 6, sonnabend: 6, saturday: 6, sat: 6,
  sonntag: 7, sunday: 7, sun: 7,
};

const MONTHS: Record<string, number> = {
  januar: 1, january: 1, jan: 1,
  februar: 2, february: 2, feb: 2,
  maerz: 3, march: 3, mar: 3, mrz: 3,
  april: 4, apr: 4,
  mai: 5, may: 5,
  juni: 6, june: 6, jun: 6,
  juli: 7, july: 7, jul: 7,
  august: 8, aug: 8,
  september: 9, sept: 9, sep: 9,
  oktober: 10, october: 10, okt: 10, oct: 10,
  november: 11, nov: 11,
  dezember: 12, december: 12, dez: 12, dec: 12,
};

export interface ResolveOptions {
  timezone: string;
  now?: Date;
}

function today(options: ResolveOptions): DateTime {
  const base = DateTime.fromJSDate(options.now ?? new Date()).setZone(options.timezone);
  return (base.isValid ? base : DateTime.fromJSDate(options.now ?? new Date()).setZone('utc')).startOf('day');
}

function result(dt: DateTime | null, confidence: number, extra: Partial<DateResolution> = {}): DateResolution {
  if (!dt || !dt.isValid) {
    return { isoDate: null, date: null, confidence: 0, ambiguous: true, ...extra };
  }
  const iso = dt.toISODate() as string;
  return { isoDate: iso, date: civilDateToUtc(iso), confidence, ambiguous: false, ...extra };
}

/**
 * Resolves a natural language date expression. Returns today with high
 * confidence when nothing was said – logging "Bankdrücken 100 kg" without a
 * date obviously means today.
 */
export function resolveDateExpression(expression: string | null | undefined, options: ResolveOptions): DateResolution {
  const ref = today(options);
  const raw = (expression ?? '').trim();

  if (!raw) return result(ref, 0.9, { reason: 'empty', matchedExpression: 'today' });

  const text = normalizeSentence(raw);

  // 1) Explicit ISO date -------------------------------------------------
  const isoMatch = text.match(/\b(\d{4})-(\d{2})-(\d{2})\b/);
  if (isoMatch) {
    const dt = DateTime.fromISO(isoMatch[0], { zone: options.timezone });
    return result(dt, 1, { matchedExpression: isoMatch[0] });
  }

  // 2) Numeric German date: 05.08.2026 / 5.8. / 5.8.26 --------------------
  const numericMatch = text.match(/\b(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{2,4})?/);
  if (numericMatch) {
    const day = Number(numericMatch[1]);
    const month = Number(numericMatch[2]);
    const year = numericMatch[3] ? normalizeYear(Number(numericMatch[3])) : inferYear(ref, month, day);
    const dt = DateTime.fromObject({ year, month, day }, { zone: options.timezone });
    if (dt.isValid) return result(dt, 0.95, { matchedExpression: numericMatch[0].trim() });
  }

  // 3) Day + month name: "5. august", "august 5", "am 5ten august" --------
  const monthName = findMonth(text);
  if (monthName) {
    const dayMatch = text.match(/\b(\d{1,2})\s*(?:\.|ten|te|st|nd|rd|th)?\b/);
    if (dayMatch) {
      const day = Number(dayMatch[1]);
      const yearMatch = text.match(/\b(20\d{2})\b/);
      const year = yearMatch ? Number(yearMatch[1]) : inferYear(ref, monthName.month, day);
      const dt = DateTime.fromObject({ year, month: monthName.month, day }, { zone: options.timezone });
      if (dt.isValid) return result(dt, 0.95, { matchedExpression: `${day}. ${monthName.name}` });
    }
  }

  // 4) Relative day keywords ---------------------------------------------
  if (/\b(heute|today|gerade eben|just now|jetzt)\b/.test(text)) {
    return result(ref, 1, { matchedExpression: 'today' });
  }
  if (/\b(vorgestern|day before yesterday)\b/.test(text)) {
    return result(ref.minus({ days: 2 }), 1, { matchedExpression: 'day before yesterday' });
  }
  if (/\b(gestern|yesterday)\b/.test(text)) {
    return result(ref.minus({ days: 1 }), 1, { matchedExpression: 'yesterday' });
  }
  if (/\b(morgen|tomorrow)\b/.test(text) && !/\bmorgens?\b/.test(text)) {
    return result(ref.plus({ days: 1 }), 0.8, { matchedExpression: 'tomorrow', reason: 'future' });
  }

  // 5) "vor 3 tagen" / "3 days ago" / "vor einer woche" -------------------
  const agoMatch = text.match(/\bvor\s+(\d+)\s*(tag|tagen|woche|wochen|monat|monaten)\b/) ??
    text.match(/\b(\d+)\s*(day|days|week|weeks|month|months)\s+ago\b/);
  if (agoMatch) {
    const amount = Number(agoMatch[1]);
    const unit = agoMatch[2];
    const dt = subtractUnit(ref, amount, unit);
    if (dt) return result(dt, /woche|week|monat|month/.test(unit) ? 0.6 : 0.95, { matchedExpression: agoMatch[0] });
  }
  if (/\bvor\s+(einer|einem)\s+(woche|monat)\b/.test(text)) {
    const isWeek = /woche/.test(text);
    return result(isWeek ? ref.minus({ weeks: 1 }) : ref.minus({ months: 1 }), 0.6, { matchedExpression: 'a week/month ago' });
  }

  // 6) Weekday names ------------------------------------------------------
  const weekday = findWeekday(text);
  if (weekday) {
    const isLast = /\b(letzten|letzte|letzter|vergangenen|vorigen|last|past)\b/.test(text);
    const dt = resolveWeekday(ref, weekday.weekday, isLast);
    return result(dt, 0.9, { matchedExpression: weekday.name });
  }

  // 7) Vague ranges are explicitly ambiguous -> the bot will ask ----------
  if (/\b(letzte woche|last week|letzten monat|last month|neulich|kuerzlich|recently|irgendwann)\b/.test(text)) {
    return { isoDate: null, date: null, confidence: 0.2, ambiguous: true, reason: 'ambiguous_range', matchedExpression: text };
  }

  return { isoDate: null, date: null, confidence: 0, ambiguous: true, reason: 'unparsable', matchedExpression: raw };
}

function normalizeSentence(text: string): string {
  return text
    .toLowerCase()
    .replace(/ß/g, 'ss')
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue');
}

function findWeekday(text: string): { weekday: number; name: string } | null {
  for (const [name, weekday] of Object.entries(WEEKDAYS)) {
    const pattern = new RegExp(`\\b${name}\\b`);
    if (pattern.test(text)) return { weekday, name };
  }
  return null;
}

function findMonth(text: string): { month: number; name: string } | null {
  for (const [name, month] of Object.entries(MONTHS)) {
    const pattern = new RegExp(`\\b${name}\\b`);
    if (pattern.test(text)) return { month, name };
  }
  return null;
}

/**
 * "am montag" -> the most recent Monday (today counts).
 * "letzten montag" -> the most recent Monday strictly before today.
 */
function resolveWeekday(ref: DateTime, weekday: number, isLast: boolean): DateTime {
  let diff = ref.weekday - weekday;
  if (diff < 0) diff += 7;
  if (isLast && diff === 0) diff = 7;
  return ref.minus({ days: diff });
}

function subtractUnit(ref: DateTime, amount: number, unit: string): DateTime | null {
  if (/tag|day/.test(unit)) return ref.minus({ days: amount });
  if (/woche|week/.test(unit)) return ref.minus({ weeks: amount });
  if (/monat|month/.test(unit)) return ref.minus({ months: amount });
  return null;
}

function normalizeYear(year: number): number {
  if (year >= 1000) return year;
  return year < 70 ? 2000 + year : 1900 + year;
}

/**
 * A bare "5. August" refers to the most recent occurrence – if that date is in
 * the future, it belongs to the previous year.
 */
function inferYear(ref: DateTime, month: number, day: number): number {
  const candidate = DateTime.fromObject({ year: ref.year, month, day }, { zone: ref.zone });
  if (candidate.isValid && candidate > ref.plus({ days: 1 })) return ref.year - 1;
  return ref.year;
}
