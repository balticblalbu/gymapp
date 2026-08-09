import { ExerciseType } from '@prisma/client';
import { lbToKg, roundWeight } from '../../lib/units';
import { normalizeNumberWords } from '../numberWords';
import type { LLMWorkoutParser, ParseContext, ParsedExercise, ParsedMessage, ParsedSet } from '../types';

/**
 * Rule based workout parser.
 *
 * Purpose:
 *  - works without any API key (LLM_PROVIDER=heuristic), so the whole system is
 *    runnable and testable offline,
 *  - acts as the fallback whenever the LLM call fails,
 *  - serves as the reference implementation the unit tests pin down.
 *
 * It is intentionally conservative: whatever it cannot read confidently is
 * reported as missing so the bot asks instead of inventing numbers.
 */
export class HeuristicWorkoutParser implements LLMWorkoutParser {
  readonly name = 'heuristic';
  readonly model = 'rules-v1';

  async parse(text: string, context: ParseContext): Promise<ParsedMessage> {
    return parseHeuristically(text, context);
  }
}

// ---------------------------------------------------------------------------
// Text preparation
// ---------------------------------------------------------------------------

/** Length preserving fold so that string indices stay comparable. */
export function foldText(value: string): string {
  return value
    .toLowerCase()
    .replace(/ä/g, 'a')
    .replace(/ö/g, 'o')
    .replace(/ü/g, 'u')
    .replace(/ß/g, 's')
    .replace(/[^a-z0-9.,]/g, ' ');
}

export function preprocess(text: string): string {
  let out = text;
  // "zweimal" -> "zwei mal", "dreimal" -> "drei mal"
  out = out.replace(/\b(ein|zwei|drei|vier|fünf|fuenf|sechs|sieben|acht|neun|zehn|elf|zwölf|zwoelf)mal\b/gi, '$1 mal');
  // Spelled numbers -> digits.
  out = normalizeNumberWords(out);
  // "3x10", "3 × 10", "3*10" -> "3 x 10"
  out = out.replace(/(\d)\s*[x×*]\s*(\d)/gi, '$1 x $2');
  // "100kg" -> "100 kg"
  out = out.replace(/(\d)\s*(kg|kilogramm|kilo|lbs|lb|pfund|km|kilometer|m|min|minuten|minute|sek|sekunden|s)\b/gi, '$1 $2');
  return out.replace(/\s+/g, ' ').trim();
}

// ---------------------------------------------------------------------------
// Numeric token scanner
// ---------------------------------------------------------------------------

type TokenUnit = 'weight' | 'weightLb' | 'reps' | 'sets' | 'seconds' | 'meters' | 'rpe' | 'bare';

interface NumberToken {
  value: number;
  unit: TokenUnit;
  index: number;
  /** Word directly in front of the number, used to disambiguate bare numbers. */
  before: string;
  /** Word directly after the number. */
  after: string;
}

const WEIGHT_UNITS = /^(kg|kilo|kilos|kilogramm|kilogramms)$/;
const WEIGHT_LB_UNITS = /^(lb|lbs|pfund|pounds|pound)$/;
const REP_UNITS = /^(wdh|wiederholung|wiederholungen|reps|rep|repetitions|repetition)$/;
const SET_UNITS = /^(satz|saetze|satze|sets|set|durchgang|durchgaenge|durchgange)$/;
const SECOND_UNITS = /^(sek|sekunde|sekunden|s|sec|seconds|second)$/;
const MINUTE_UNITS = /^(min|minute|minuten|minutes)$/;
const METER_UNITS = /^(m|meter|metern|meters)$/;
const KM_UNITS = /^(km|kilometer|kilometern|kilometers)$/;
const REP_CONNECTORS = /^(fuer|für|for|auf|a|à|je|jeweils|mit|times|von)$/;

function scanNumbers(text: string): NumberToken[] {
  const tokens: NumberToken[] = [];
  const words = text.split(/\s+/);
  const numberPattern = /^(\d+(?:[.,]\d+)?)$/;

  for (let i = 0; i < words.length; i += 1) {
    const word = words[i].replace(/[.,](?=$)/, '');
    const match = numberPattern.exec(word);
    if (!match) continue;

    const value = Number(match[1].replace(',', '.'));
    if (!Number.isFinite(value)) continue;

    const rawAfter = (words[i + 1] ?? '').toLowerCase().replace(/[^a-zà-ÿ]/g, '');
    const rawBefore = (words[i - 1] ?? '').toLowerCase().replace(/[^a-zà-ÿ]/g, '');
    const after = fold(rawAfter);
    const before = fold(rawBefore);

    let unit: TokenUnit = 'bare';
    if (WEIGHT_UNITS.test(after)) unit = 'weight';
    else if (WEIGHT_LB_UNITS.test(after)) unit = 'weightLb';
    else if (REP_UNITS.test(after)) unit = 'reps';
    else if (SET_UNITS.test(after)) unit = 'sets';
    else if (MINUTE_UNITS.test(after)) unit = 'seconds';
    else if (SECOND_UNITS.test(after)) unit = 'seconds';
    else if (KM_UNITS.test(after)) unit = 'meters';
    else if (METER_UNITS.test(after)) unit = 'meters';
    else if (before === 'rpe') unit = 'rpe';
    // "100 für zehn" – the number in front of the connector is the load.
    else if (REP_CONNECTORS.test(after) && !SET_UNITS.test(before)) unit = 'weight';
    else if (REP_CONNECTORS.test(before)) unit = 'reps';
    else if (SET_UNITS.test(before)) unit = 'sets';

    let normalized = value;
    if (unit === 'seconds' && MINUTE_UNITS.test(after)) normalized = value * 60;
    if (unit === 'meters' && KM_UNITS.test(after)) normalized = value * 1000;
    if (unit === 'weightLb') normalized = roundWeight(lbToKg(value));

    tokens.push({ value: normalized, unit: unit === 'weightLb' ? 'weight' : unit, index: i, before, after });
  }

  return tokens;
}

function fold(value: string): string {
  return value.replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss');
}

// ---------------------------------------------------------------------------
// Set construction
// ---------------------------------------------------------------------------

interface SegmentResult {
  sets: ParsedSet[];
  confidence: number;
  type: ExerciseType | null;
}

/**
 * Turns one exercise segment into concrete sets.
 *
 * Handles the common shapes:
 *   "100 kg 3 x 10"            -> 3 × (100/10)
 *   "3 Sätze, 100 Kilo, 10 Wdh" -> 3 × (100/10)
 *   "100 für 10, dann 110 für 8" -> 100/10, 110/8
 *   "20 Minuten"                -> one cardio set
 */
export function parseSegment(segment: string): SegmentResult {
  const text = preprocess(segment);
  const tokens = scanNumbers(text);
  if (tokens.length === 0) return { sets: [], confidence: 0.15, type: null };

  const words = text.split(/\s+/);
  const crossPairs: Array<{ a: number; b: number; index: number }> = [];
  for (let i = 0; i < words.length; i += 1) {
    if (/^(x|mal)$/i.test(words[i])) {
      const a = Number((words[i - 1] ?? '').replace(',', '.'));
      const b = Number((words[i + 1] ?? '').replace(',', '.'));
      if (Number.isFinite(a) && Number.isFinite(b)) crossPairs.push({ a, b, index: i });
    }
  }

  const seconds = tokens.filter((t) => t.unit === 'seconds');
  const meters = tokens.filter((t) => t.unit === 'meters');
  const weights = tokens.filter((t) => t.unit === 'weight');
  const explicitReps = tokens.filter((t) => t.unit === 'reps');
  const explicitSets = tokens.filter((t) => t.unit === 'sets');
  const rpe = tokens.find((t) => t.unit === 'rpe');

  // --- Cardio / duration only -------------------------------------------
  if (weights.length === 0 && explicitReps.length === 0 && (seconds.length > 0 || meters.length > 0)) {
    const setCount = explicitSets[0]?.value ?? 1;
    const set: ParsedSet = {
      weightKg: null,
      reps: null,
      durationSec: seconds[0] ? Math.round(seconds[0].value) : null,
      distanceM: meters[0] ? Math.round(meters[0].value) : null,
      rpe: rpe?.value ?? null,
      isWarmup: false,
    };
    return {
      sets: Array.from({ length: Math.max(1, Math.min(20, Math.round(setCount))) }, () => ({ ...set })),
      confidence: 0.8,
      type: meters.length > 0 || seconds.length > 0 ? ExerciseType.CARDIO : null,
    };
  }

  // --- Sequential "weight for reps" pairs --------------------------------
  const sequential = buildSequentialSets(tokens, crossPairs);
  if (sequential.length > 1) {
    // "3 sets of 10 reps with 100 kilos" mentions the load only at the end –
    // a single unambiguous weight is applied to every set that has none.
    if (weights.length === 1 && sequential.every((s) => s.weightKg == null)) {
      for (const set of sequential) set.weightKg = roundWeight(weights[0].value);
    }
    return { sets: sequential, confidence: 0.8, type: ExerciseType.STRENGTH };
  }

  // --- Single specification with a set count -----------------------------
  const weightKg = weights[0]?.value ?? null;
  let repCount: number | null = explicitReps[0]?.value ?? null;
  let setCount: number | null = explicitSets[0]?.value ?? null;

  if (crossPairs.length > 0) {
    const pair = crossPairs[0];
    // "3 x 10" – the first number is the set count unless it was explicitly
    // labelled otherwise ("10 Wiederholungen x 3 Sätze").
    const aIsSets = explicitSets.some((t) => t.value === pair.a);
    const bIsSets = explicitSets.some((t) => t.value === pair.b);
    const aIsReps = explicitReps.some((t) => t.value === pair.a);
    if (bIsSets || aIsReps) {
      repCount = repCount ?? pair.a;
      setCount = setCount ?? pair.b;
    } else if (aIsSets || !repCount) {
      setCount = setCount ?? pair.a;
      repCount = repCount ?? pair.b;
    }
  }

  if (repCount == null) {
    // A bare number that is neither the weight nor a set count is most likely reps.
    const bare = tokens.find((t) => t.unit === 'bare' && t.value !== weightKg && t.value !== setCount);
    if (bare) repCount = bare.value;
  }

  const total = setCount != null && setCount > 0 ? Math.min(30, Math.round(setCount)) : 1;
  const set: ParsedSet = {
    weightKg: weightKg != null ? roundWeight(weightKg) : null,
    reps: repCount != null && repCount > 0 ? Math.round(repCount) : null,
    durationSec: seconds[0] ? Math.round(seconds[0].value) : null,
    distanceM: meters[0] ? Math.round(meters[0].value) : null,
    rpe: rpe?.value ?? null,
    isWarmup: false,
  };

  let confidence = 0.9;
  if (set.reps == null && set.durationSec == null) confidence = 0.4;
  if (set.weightKg == null && set.durationSec == null && set.distanceM == null) confidence = Math.min(confidence, 0.5);
  if (setCount == null) confidence = Math.min(confidence, 0.75);

  return {
    sets: Array.from({ length: total }, () => ({ ...set })),
    confidence,
    type: set.weightKg != null ? ExerciseType.STRENGTH : set.reps != null ? ExerciseType.BODYWEIGHT : null,
  };
}

/**
 * Detects enumerations like "erst 100 für 10, dann 110 für 8, danach 110 für 7"
 * and "120 für 6, danach 2 x 5" (the weight carries over).
 */
function buildSequentialSets(tokens: NumberToken[], crossPairs: Array<{ a: number; b: number; index: number }>): ParsedSet[] {
  const sets: ParsedSet[] = [];
  let currentWeight: number | null = null;
  let pendingMultiplier: number | null = null;

  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];

    if (token.unit === 'weight') {
      currentWeight = roundWeight(token.value);
      continue;
    }

    if (token.unit === 'sets') {
      pendingMultiplier = Math.round(token.value);
      continue;
    }

    if (token.unit === 'reps' || (token.unit === 'bare' && REP_CONNECTORS.test(token.before))) {
      const repeat = pendingMultiplier ?? 1;
      pendingMultiplier = null;
      for (let r = 0; r < Math.min(30, Math.max(1, repeat)); r += 1) {
        sets.push({ weightKg: currentWeight, reps: Math.round(token.value), durationSec: null, distanceM: null, rpe: null, isWarmup: false });
      }
      continue;
    }

    if (token.unit === 'bare') {
      // Part of an "a x b" pair: a sets of b reps at the current weight.
      const pair = crossPairs.find((p) => p.a === token.value && tokens[i + 1]?.value === p.b);
      if (pair) {
        for (let r = 0; r < Math.min(30, Math.max(1, Math.round(pair.a))); r += 1) {
          sets.push({ weightKg: currentWeight, reps: Math.round(pair.b), durationSec: null, distanceM: null, rpe: null, isWarmup: false });
        }
        i += 1;
      }
    }
  }

  return sets;
}

// ---------------------------------------------------------------------------
// Message level parsing
// ---------------------------------------------------------------------------

const DATE_PATTERNS: RegExp[] = [
  /\b(vorgestern|day before yesterday)\b/i,
  /\b(gestern|yesterday)\b/i,
  /\b(heute|today)\b/i,
  /\b(letzte[nr]?\s+\w+tag)\b/i,
  /\b(am\s+\d{1,2}\.\s*\w+)\b/i,
  /\b(\d{4}-\d{2}-\d{2})\b/,
  /\b(\d{1,2}\.\d{1,2}\.\d{0,4})\b/,
  /\b(letzte\s+woche|last\s+week)\b/i,
  /\b(vor\s+\d+\s+(?:tagen|wochen))\b/i,
  /\b(\d+\s+days?\s+ago)\b/i,
  /\b(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b/i,
];

export function extractDateExpression(text: string): string | null {
  for (const pattern of DATE_PATTERNS) {
    const match = pattern.exec(text);
    if (match) return match[0];
  }
  return null;
}

const MUSCLE_KEYWORDS: Record<string, string> = {
  brust: 'chest', chest: 'chest', pecs: 'chest',
  ruecken: 'back', rucken: 'back', back: 'back', lat: 'back',
  schulter: 'shoulders', schultern: 'shoulders', shoulders: 'shoulders', delts: 'shoulders',
  bizeps: 'biceps', biceps: 'biceps',
  trizeps: 'triceps', triceps: 'triceps',
  beine: 'legs', bein: 'legs', legs: 'legs',
  po: 'glutes', gesaess: 'glutes', glutes: 'glutes',
  beinbeuger: 'hamstrings', hamstrings: 'hamstrings',
  quadrizeps: 'quadriceps', quads: 'quadriceps', quadriceps: 'quadriceps',
  waden: 'calves', calves: 'calves',
  bauch: 'core', core: 'core', rumpf: 'core', abs: 'core',
  unterarme: 'forearms', forearms: 'forearms',
  arme: 'biceps', arms: 'biceps',
};

export function extractMuscleGroups(text: string): string[] {
  const folded = foldText(text);
  const found = new Set<string>();
  for (const [keyword, key] of Object.entries(MUSCLE_KEYWORDS)) {
    if (new RegExp(`\\b${keyword}\\b`).test(folded)) found.add(key);
  }
  return [...found];
}

const NEW_EXERCISE_PATTERN = /\b(neue\s+ue?bung|neue\s+übung|new\s+exercise)\b\s*[:,-]?\s*([^.,;]+)/i;

interface ExerciseHit {
  name: string;
  start: number;
  end: number;
}

/** Finds catalogue exercise names inside the message, longest match wins. */
export function findKnownExercises(text: string, knownExercises: string[]): ExerciseHit[] {
  const folded = foldText(text);
  const hits: ExerciseHit[] = [];

  const sorted = [...knownExercises].sort((a, b) => b.length - a.length);
  for (const name of sorted) {
    const needle = foldText(name).trim();
    if (needle.length < 3) continue;
    let from = 0;
    for (;;) {
      const index = folded.indexOf(needle, from);
      if (index === -1) break;
      const before = index === 0 ? ' ' : folded[index - 1];
      const after = index + needle.length >= folded.length ? ' ' : folded[index + needle.length];
      const isBoundary = !/[a-z0-9]/.test(before) && !/[a-z0-9]/.test(after);
      const overlaps = hits.some((h) => index < h.end && index + needle.length > h.start);
      if (isBoundary && !overlaps) hits.push({ name, start: index, end: index + needle.length });
      from = index + needle.length;
    }
  }

  return hits.sort((a, b) => a.start - b.start);
}

const FILLER_WORDS = new Set([
  'ich', 'hab', 'habe', 'heute', 'gestern', 'gemacht', 'beim', 'bei', 'am', 'und', 'dann', 'danach', 'noch', 'auch',
  'mit', 'fuer', 'für', 'war', 'waren', 'das', 'der', 'die', 'den', 'ein', 'eine', 'erst', 'erstmal', 'nochmal',
  'i', 'did', 'today', 'yesterday', 'then', 'and', 'with', 'for', 'my', 'the', 'a', 'on', 'of', 'some', 'went',
]);

/** Fallback: the words before the first number are probably the exercise name. */
function guessExerciseName(segment: string): string | null {
  const cleaned = segment.replace(/[.,;:]/g, ' ');
  const words = cleaned.split(/\s+/).filter(Boolean);
  const collected: string[] = [];
  for (const word of words) {
    if (/\d/.test(word)) break;
    const folded = foldText(word).trim();
    if (!folded || FILLER_WORDS.has(folded)) continue;
    collected.push(word);
  }
  const name = collected.join(' ').trim();
  return name.length >= 3 ? name : null;
}

export function parseHeuristically(input: string, context: ParseContext): ParsedMessage {
  const text = input.trim();
  const base: ParsedMessage = {
    intent: 'unknown',
    language: /[äöüß]|\b(hab|habe|ich|und|mit|heute|gestern|saetze|sätze|wiederholungen)\b/i.test(text) ? 'de' : 'en',
    dateExpression: extractDateExpression(text),
    exercises: [],
    newExercises: [],
    corrections: [],
    clarificationQuestion: null,
    confidence: 0,
    provider: 'heuristic',
    model: 'rules-v1',
  };

  // --- Explicit exercise creation ---------------------------------------
  const newExerciseMatch = NEW_EXERCISE_PATTERN.exec(text);
  if (newExerciseMatch) {
    const remainder = text.slice(newExerciseMatch.index + newExerciseMatch[0].length);
    const rawName = newExerciseMatch[2].trim();
    const muscleGroups = extractMuscleGroups(`${remainder} ${rawName}`);
    // Strip a trailing muscle-group phrase from the name ("Cable Flys, Brust").
    const name = rawName.replace(/\b(muskelgruppe|muscle\s+group)\b.*$/i, '').trim();
    if (name) {
      base.intent = 'create_exercise';
      base.newExercises = [{ name, muscleGroups, type: null }];
      base.confidence = muscleGroups.length > 0 ? 0.85 : 0.6;
      return base;
    }
  }

  // --- Corrections -------------------------------------------------------
  const correction = detectCorrection(text);
  if (correction) {
    base.intent = 'correction';
    base.corrections = [correction];
    base.confidence = 0.6;
    return base;
  }

  // --- Workout logging ---------------------------------------------------
  const hits = findKnownExercises(text, context.knownExercises);
  const segments: Array<{ name: string; text: string }> = [];

  if (hits.length > 0) {
    for (let i = 0; i < hits.length; i += 1) {
      const from = hits[i].end;
      const to = i + 1 < hits.length ? hits[i + 1].start : text.length;
      let segment = text.slice(from, to);
      // Details are often stated before the exercise name ("drei Sätze
      // Bankdrücken mit 100 Kilo", "20 Minuten auf dem Laufband"), so the
      // text in front of the first hit belongs to the first segment.
      if (i === 0 && hits[0].start > 0) segment = `${text.slice(0, hits[0].start)} ${segment}`;
      segments.push({ name: hits[i].name, text: segment });
    }
  } else {
    // No catalogue hit: split on separators and guess a name per chunk.
    const chunks = text.split(/[.;]|\bund danach\b|\bdanach\b|\bund dann\b|\bthen\b|\band then\b/i).filter((c) => /\d/.test(c));
    for (const chunk of chunks) {
      const guessed = guessExerciseName(chunk);
      if (guessed) segments.push({ name: guessed, text: chunk });
    }
  }

  const exercises: ParsedExercise[] = [];
  for (const segment of segments) {
    const result = parseSegment(segment.text);
    if (result.sets.length === 0) continue;
    exercises.push({
      name: segment.name,
      type: result.type,
      muscleGroups: extractMuscleGroups(segment.name),
      sets: result.sets,
      notes: null,
      confidence: result.confidence,
    });
  }

  if (exercises.length === 0) {
    base.intent = /\?|wie viel|how much|zeig|show|stats|statistik/i.test(text) ? 'query' : 'unknown';
    base.confidence = 0.1;
    return base;
  }

  base.intent = 'log_workout';
  base.exercises = exercises;
  base.confidence = Math.min(...exercises.map((e) => e.confidence));

  const incomplete = exercises.filter((e) => e.sets.some((s) => s.reps == null && s.durationSec == null && s.distanceM == null));
  if (incomplete.length > 0) {
    base.clarificationQuestion =
      base.language === 'de'
        ? `${incomplete[0].name} erkannt. Wie viele Sätze und Wiederholungen waren das?`
        : `Understood ${incomplete[0].name}. How many sets and reps was that?`;
  }

  return base;
}

const CORRECTION_PATTERNS = [
  /\b(die|das|der)?\s*(\d+(?:[.,]\d+)?)\s*(?:kg|kilo)\s*(?:waren|war|sind|ist|should be|were)\s*(?:eigentlich|actually|nur|just)?\s*(\d+(?:[.,]\d+)?)/i,
  /\b(?:beim|im)?\s*(?:letzten|last)\s*(?:satz|set)\s*(?:waren es|war|were|waren)?\s*(?:nur|only)?\s*(\d+)\s*(?:wiederholungen|wdh|reps)?/i,
];

function detectCorrection(text: string): import('../types').CorrectionChange | null {
  const weightMatch = CORRECTION_PATTERNS[0].exec(text);
  if (weightMatch) {
    return {
      scope: 'last_exercise',
      field: 'weight',
      valueNumber: Number(weightMatch[3].replace(',', '.')),
      matchPreviousNumber: Number(weightMatch[2].replace(',', '.')),
      exerciseName: null,
      setIndex: null,
      valueText: null,
    };
  }

  const repMatch = CORRECTION_PATTERNS[1].exec(text);
  if (repMatch && /korrektur|eigentlich|nur|actually|only|falsch|wrong|waren es/i.test(text)) {
    return {
      scope: 'last_set',
      field: 'reps',
      valueNumber: Number(repMatch[1]),
      matchPreviousNumber: null,
      exerciseName: null,
      setIndex: null,
      valueText: null,
    };
  }

  return null;
}
