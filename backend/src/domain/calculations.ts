import { ExerciseType } from '@prisma/client';
import { round } from '../lib/units';

/**
 * Pure, dependency-free workout mathematics. Everything in here is unit tested
 * (tests/calculations.test.ts) and documented in docs/CALCULATIONS.md.
 */

export interface SetLike {
  weightKg?: number | null;
  reps?: number | null;
  durationSec?: number | null;
  distanceM?: number | null;
  isWarmup?: boolean;
  isOneRmTest?: boolean;
}

// ---------------------------------------------------------------------------
// Volume
// ---------------------------------------------------------------------------

/**
 * Volume load of a single set: `weight × reps`.
 *
 * Only sets that have both a weight and a rep count contribute to kilogram
 * volume. Body-weight exercises without additional load have no meaningful
 * kg-volume – they are counted through `totalReps` instead, so that a set of
 * 20 push-ups never silently inflates the tonnage of a bench press session.
 */
export function setVolume(set: SetLike): number {
  if (set.weightKg == null || set.reps == null) return 0;
  if (set.weightKg <= 0 || set.reps <= 0) return 0;
  return set.weightKg * set.reps;
}

export interface VolumeSummary {
  volumeKg: number;
  sets: number;
  reps: number;
  workingSets: number;
  durationSec: number;
  distanceM: number;
}

export function summarizeSets(sets: SetLike[], includeWarmups = true): VolumeSummary {
  const relevant = includeWarmups ? sets : sets.filter((s) => !s.isWarmup);
  return relevant.reduce<VolumeSummary>(
    (acc, set) => {
      acc.volumeKg += setVolume(set);
      acc.sets += 1;
      acc.reps += set.reps ?? 0;
      acc.workingSets += set.isWarmup ? 0 : 1;
      acc.durationSec += set.durationSec ?? 0;
      acc.distanceM += set.distanceM ?? 0;
      return acc;
    },
    { volumeKg: 0, sets: 0, reps: 0, workingSets: 0, durationSec: 0, distanceM: 0 },
  );
}

// ---------------------------------------------------------------------------
// One rep max
// ---------------------------------------------------------------------------

/**
 * Estimated one rep maximum using the **Epley formula**:
 *
 *     e1RM = weight × (1 + reps / 30)
 *
 * A single rep returns the weight itself. The estimate loses accuracy above
 * roughly 12 repetitions, therefore rep counts are clamped to 12 for the
 * estimate – this is deliberately conservative and documented in the app.
 */
export const E1RM_REP_CAP = 12;

export function estimateOneRepMax(weightKg: number, reps: number): number {
  if (!Number.isFinite(weightKg) || !Number.isFinite(reps)) return 0;
  if (weightKg <= 0 || reps <= 0) return 0;
  if (reps === 1) return round(weightKg, 2);
  const effectiveReps = Math.min(reps, E1RM_REP_CAP);
  return round(weightKg * (1 + effectiveReps / 30), 2);
}

/** True when the estimate had to clamp the rep count (shown as a hint in the UI). */
export function isE1rmExtrapolated(reps: number): boolean {
  return reps > E1RM_REP_CAP;
}

export function bestE1rm(sets: SetLike[]): { value: number; set: SetLike | null } {
  let best = 0;
  let bestSet: SetLike | null = null;
  for (const set of sets) {
    if (set.weightKg == null || set.reps == null) continue;
    const value = estimateOneRepMax(set.weightKg, set.reps);
    if (value > best) {
      best = value;
      bestSet = set;
    }
  }
  return { value: round(best, 2), set: bestSet };
}

// ---------------------------------------------------------------------------
// Statistics helpers
// ---------------------------------------------------------------------------

export function mean(values: number[]): number {
  if (values.length === 0) return 0;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

export function median(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

/** Simple trailing moving average; window is clamped to the available data. */
export function movingAverage(values: number[], window: number): number[] {
  if (window <= 1) return [...values];
  return values.map((_, i) => {
    const from = Math.max(0, i - window + 1);
    return mean(values.slice(from, i + 1));
  });
}

/**
 * Percentage change between two values. Returns `null` when there is no
 * meaningful baseline (0 or missing previous value) instead of `Infinity`,
 * so the UI can render "–" rather than a nonsense "+∞ %".
 */
export function percentChange(previous: number | null | undefined, current: number | null | undefined): number | null {
  if (previous == null || current == null) return null;
  if (previous === 0) return null;
  return round(((current - previous) / Math.abs(previous)) * 100, 1);
}

/**
 * Robust trend between two sample windows.
 *
 * Raw averages are extremely sensitive to a single outlier session, so the
 * comparison uses the **median** of each window and additionally requires a
 * minimum sample size. Below that size the trend is reported as `null`
 * ("not enough data") rather than a confident looking number.
 */
export interface TrendResult {
  changePercent: number | null;
  previous: number | null;
  current: number | null;
  sampleSize: number;
  reliable: boolean;
}

export const MIN_TREND_SAMPLES = 2;

export function robustTrend(previousWindow: number[], currentWindow: number[]): TrendResult {
  const previous = previousWindow.length ? round(median(previousWindow), 2) : null;
  const current = currentWindow.length ? round(median(currentWindow), 2) : null;
  const sampleSize = Math.min(previousWindow.length, currentWindow.length);
  const reliable = previousWindow.length >= MIN_TREND_SAMPLES && currentWindow.length >= MIN_TREND_SAMPLES;
  return {
    changePercent: reliable ? percentChange(previous, current) : null,
    previous,
    current,
    sampleSize,
    reliable,
  };
}

// ---------------------------------------------------------------------------
// Exercise type helpers
// ---------------------------------------------------------------------------

export function isWeightBased(type: ExerciseType): boolean {
  return type === ExerciseType.STRENGTH || type === ExerciseType.BODYWEIGHT;
}

export function supportsOneRepMax(type: ExerciseType): boolean {
  return type === ExerciseType.STRENGTH;
}

/** Formats "100 kg × 10 × 3" style summaries used by bot + app. */
export function formatSetGroup(weightKg: number | null | undefined, reps: number | null | undefined, count: number): string {
  const parts: string[] = [];
  if (weightKg != null && weightKg > 0) parts.push(`${round(weightKg, 2)} kg`);
  if (reps != null && reps > 0) parts.push(`${reps}`);
  if (count > 1) parts.push(`${count}`);
  return parts.join(' × ');
}

/**
 * Groups consecutive identical sets so that "100/10, 100/10, 100/10" is shown
 * as "100 kg × 10 × 3" while "100/10, 110/8" stays expanded.
 */
export interface SetGroup {
  weightKg: number | null;
  reps: number | null;
  durationSec: number | null;
  distanceM: number | null;
  count: number;
}

export function groupSets(sets: SetLike[]): SetGroup[] {
  const groups: SetGroup[] = [];
  for (const set of sets) {
    const last = groups[groups.length - 1];
    if (
      last &&
      last.weightKg === (set.weightKg ?? null) &&
      last.reps === (set.reps ?? null) &&
      last.durationSec === (set.durationSec ?? null) &&
      last.distanceM === (set.distanceM ?? null)
    ) {
      last.count += 1;
    } else {
      groups.push({
        weightKg: set.weightKg ?? null,
        reps: set.reps ?? null,
        durationSec: set.durationSec ?? null,
        distanceM: set.distanceM ?? null,
        count: 1,
      });
    }
  }
  return groups;
}
