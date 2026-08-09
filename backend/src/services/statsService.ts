import { ExerciseType } from '@prisma/client';
import {
  bestE1rm,
  estimateOneRepMax,
  mean,
  median,
  percentChange,
  robustTrend,
  setVolume,
  summarizeSets,
  type TrendResult,
} from '../domain/calculations';
import { addDays, daysBetween, periodStart, todayInZone, utcToCivilDate, type Period } from '../lib/dates';
import { prisma } from '../lib/prisma';
import { round } from '../lib/units';

/**
 * All statistics are computed from one flat list of sets. For a single-user
 * data set this is far simpler (and easier to verify) than a pile of SQL
 * aggregates, and it keeps every formula in one testable place.
 *
 * The methodology is documented in docs/CALCULATIONS.md.
 */

export interface SetRow {
  date: Date;
  workoutId: string;
  exerciseId: string;
  exerciseName: string;
  exerciseType: ExerciseType;
  muscleGroups: Array<{ key: string; parentKey: string | null; contribution: number }>;
  weightKg: number | null;
  reps: number | null;
  durationSec: number | null;
  distanceM: number | null;
  isWarmup: boolean;
}

export async function loadSetRows(
  userId: string,
  options: { from?: Date | null; to?: Date | null; exerciseId?: string } = {},
): Promise<SetRow[]> {
  const rows = await prisma().workoutSet.findMany({
    where: {
      deletedAt: null,
      workoutExercise: {
        deletedAt: null,
        ...(options.exerciseId ? { exerciseId: options.exerciseId } : {}),
        workout: {
          userId,
          deletedAt: null,
          ...(options.from || options.to
            ? { date: { ...(options.from ? { gte: options.from } : {}), ...(options.to ? { lte: options.to } : {}) } }
            : {}),
        },
      },
    },
    select: {
      weightKg: true,
      reps: true,
      durationSec: true,
      distanceM: true,
      isWarmup: true,
      createdAt: true,
      workoutExercise: {
        select: {
          workoutId: true,
          exerciseId: true,
          workout: { select: { date: true } },
          exercise: {
            select: {
              name: true,
              nameDe: true,
              type: true,
              muscleGroups: { select: { contribution: true, muscleGroup: { select: { key: true, parentKey: true } } } },
            },
          },
        },
      },
    },
    orderBy: [{ workoutExercise: { workout: { date: 'asc' } } }, { createdAt: 'asc' }],
  });

  return rows.map((row) => ({
    date: row.workoutExercise.workout.date,
    workoutId: row.workoutExercise.workoutId,
    exerciseId: row.workoutExercise.exerciseId,
    exerciseName: row.workoutExercise.exercise.name,
    exerciseType: row.workoutExercise.exercise.type,
    muscleGroups: row.workoutExercise.exercise.muscleGroups.map((mg) => ({
      key: mg.muscleGroup.key,
      parentKey: mg.muscleGroup.parentKey,
      contribution: mg.contribution,
    })),
    weightKg: row.weightKg,
    reps: row.reps,
    durationSec: row.durationSec,
    distanceM: row.distanceM,
    isWarmup: row.isWarmup,
  }));
}

// ---------------------------------------------------------------------------
// Performance metric per set
// ---------------------------------------------------------------------------

/**
 * Single number describing "how strong was this set", used for all trend
 * comparisons. Comparing raw volume across different exercises is meaningless,
 * so every exercise is compared **against itself** using this metric:
 *
 *  - STRENGTH    -> estimated 1RM (weight and reps in one number)
 *  - BODYWEIGHT  -> estimated 1RM if extra weight was used, otherwise reps
 *  - CARDIO      -> average speed in m/s when both values exist, else distance
 *  - DURATION    -> seconds held
 */
export function performanceMetric(row: SetRow): number | null {
  switch (row.exerciseType) {
    case ExerciseType.STRENGTH:
      if (row.weightKg == null || row.reps == null) return null;
      return estimateOneRepMax(row.weightKg, row.reps);
    case ExerciseType.BODYWEIGHT:
      if (row.weightKg != null && row.weightKg > 0 && row.reps != null) return estimateOneRepMax(row.weightKg, row.reps);
      return row.reps ?? null;
    case ExerciseType.CARDIO:
      if (row.distanceM != null && row.durationSec != null && row.durationSec > 0) return round(row.distanceM / row.durationSec, 3);
      return row.distanceM ?? row.durationSec ?? null;
    case ExerciseType.DURATION:
      return row.durationSec ?? null;
    default:
      return null;
  }
}

// ---------------------------------------------------------------------------
// Progress per exercise / muscle group
// ---------------------------------------------------------------------------

export interface ExerciseTrend {
  exerciseId: string;
  exerciseName: string;
  trend: TrendResult;
  /** Number of working sets in the current window – the weight of this trend. */
  weight: number;
}

/**
 * Compares two equally long windows per exercise using the median performance
 * metric. The median makes a single lucky set unable to fake a long term
 * improvement, and exercises with too few sets are excluded entirely.
 */
export function exerciseTrends(current: SetRow[], previous: SetRow[]): ExerciseTrend[] {
  const byExercise = new Map<string, { name: string; current: number[]; previous: number[] }>();

  const collect = (rows: SetRow[], bucket: 'current' | 'previous') => {
    for (const row of rows) {
      if (row.isWarmup) continue;
      const metric = performanceMetric(row);
      if (metric == null || metric <= 0) continue;
      const entry = byExercise.get(row.exerciseId) ?? { name: row.exerciseName, current: [], previous: [] };
      entry[bucket].push(metric);
      byExercise.set(row.exerciseId, entry);
    }
  };
  collect(current, 'current');
  collect(previous, 'previous');

  const trends: ExerciseTrend[] = [];
  for (const [exerciseId, entry] of byExercise) {
    const trend = robustTrend(entry.previous, entry.current);
    trends.push({ exerciseId, exerciseName: entry.name, trend, weight: entry.current.length });
  }
  return trends;
}

export interface MuscleGroupProgress {
  key: string;
  changePercent: number | null;
  volumeKg: number;
  sets: number;
  exercises: number;
  reliable: boolean;
}

/**
 * Muscle group progress = set-count weighted average of the per-exercise
 * trends of all exercises that train this muscle group, scaled by their
 * contribution factor. This never compares "bench press kilograms" against
 * "cable fly kilograms".
 */
export function muscleGroupProgress(current: SetRow[], previous: SetRow[]): MuscleGroupProgress[] {
  const trends = new Map(exerciseTrends(current, previous).map((t) => [t.exerciseId, t]));
  const groups = new Map<string, { weighted: number; weight: number; volume: number; sets: number; exercises: Set<string> }>();

  for (const row of current) {
    if (row.isWarmup) continue;
    const volume = setVolume(row);
    for (const mg of row.muscleGroups) {
      const key = mg.parentKey ?? mg.key;
      const entry = groups.get(key) ?? { weighted: 0, weight: 0, volume: 0, sets: 0, exercises: new Set<string>() };
      entry.volume += volume * mg.contribution;
      entry.sets += 1;
      entry.exercises.add(row.exerciseId);

      const trend = trends.get(row.exerciseId);
      if (trend?.trend.reliable && trend.trend.changePercent != null) {
        entry.weighted += trend.trend.changePercent * mg.contribution;
        entry.weight += mg.contribution;
      }
      groups.set(key, entry);
    }
  }

  return [...groups.entries()]
    .map(([key, entry]) => ({
      key,
      changePercent: entry.weight > 0 ? round(entry.weighted / entry.weight, 1) : null,
      volumeKg: round(entry.volume, 1),
      sets: entry.sets,
      exercises: entry.exercises.size,
      reliable: entry.weight > 0,
    }))
    .sort((a, b) => b.volumeKg - a.volumeKg);
}

/** Weighted average of all reliable exercise trends – the "strength" headline. */
export function overallStrengthTrend(current: SetRow[], previous: SetRow[]): number | null {
  const trends = exerciseTrends(current, previous).filter((t) => t.trend.reliable && t.trend.changePercent != null);
  if (trends.length === 0) return null;
  const totalWeight = trends.reduce((sum, t) => sum + t.weight, 0);
  if (totalWeight === 0) return null;
  return round(trends.reduce((sum, t) => sum + (t.trend.changePercent as number) * t.weight, 0) / totalWeight, 1);
}

// ---------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------

export interface DashboardExerciseLine {
  exerciseId: string;
  name: string;
  muscleGroups: string[];
  summary: string;
  volumeKg: number;
  sets: number;
}

export interface DashboardResponse {
  date: string;
  today: {
    hasWorkout: boolean;
    workoutId: string | null;
    title: string | null;
    exercises: DashboardExerciseLine[];
    volumeKg: number;
    sets: number;
    reps: number;
    durationSec: number | null;
  };
  streakDays: number;
  workoutsThisWeek: number;
  totals: { workouts: number; volumeKg: number; sets: number };
  comparisons: {
    vsLastWorkout: number | null;
    vsLastWeek: number | null;
    vsLastMonth: number | null;
    strengthTrend: number | null;
  };
  muscleGroups: MuscleGroupProgress[];
  recentRecords: Array<{
    exerciseName: string;
    type: string;
    value: number;
    previousValue: number | null;
    improvementPercent: number | null;
    achievedAt: string;
  }>;
}

export async function getDashboard(userId: string, timezone: string): Promise<DashboardResponse> {
  const today = todayInZone(timezone);
  const rows = await loadSetRows(userId);

  const todayRows = rows.filter((r) => r.date.getTime() === today.getTime());
  const todayWorkoutId = todayRows[0]?.workoutId ?? null;

  const byExercise = new Map<string, SetRow[]>();
  for (const row of todayRows) {
    const list = byExercise.get(row.exerciseId) ?? [];
    list.push(row);
    byExercise.set(row.exerciseId, list);
  }

  const exercises: DashboardExerciseLine[] = [...byExercise.entries()].map(([exerciseId, sets]) => {
    const summary = summarizeSets(sets);
    return {
      exerciseId,
      name: sets[0].exerciseName,
      muscleGroups: [...new Set(sets[0].muscleGroups.map((mg) => mg.key))],
      summary: describeSets(sets),
      volumeKg: round(summary.volumeKg, 1),
      sets: summary.sets,
    };
  });

  const todaySummary = summarizeSets(todayRows);
  const workout = todayWorkoutId
    ? await prisma().workout.findUnique({ where: { id: todayWorkoutId }, select: { title: true, durationSec: true } })
    : null;

  // --- comparison windows ------------------------------------------------
  const weekCurrent = rows.filter((r) => daysBetween(r.date, today) < 7 && daysBetween(r.date, today) >= 0);
  const weekPrevious = rows.filter((r) => daysBetween(r.date, today) >= 7 && daysBetween(r.date, today) < 14);
  const monthCurrent = rows.filter((r) => daysBetween(r.date, today) < 30 && daysBetween(r.date, today) >= 0);
  const monthPrevious = rows.filter((r) => daysBetween(r.date, today) >= 30 && daysBetween(r.date, today) < 60);

  const workoutDates = [...new Set(rows.map((r) => r.date.getTime()))].sort((a, b) => b - a);
  const lastWorkoutBeforeToday = workoutDates.find((t) => t < today.getTime()) ?? null;
  const previousWorkoutRows = lastWorkoutBeforeToday ? rows.filter((r) => r.date.getTime() === lastWorkoutBeforeToday) : [];

  const recordRows = await prisma().personalRecord.findMany({
    where: { userId },
    include: { exercise: { select: { name: true } } },
    orderBy: { achievedAt: 'desc' },
    take: 5,
  });

  return {
    date: utcToCivilDate(today),
    today: {
      hasWorkout: todayRows.length > 0,
      workoutId: todayWorkoutId,
      title: workout?.title ?? null,
      exercises,
      volumeKg: round(todaySummary.volumeKg, 1),
      sets: todaySummary.sets,
      reps: todaySummary.reps,
      durationSec: workout?.durationSec ?? (todaySummary.durationSec || null),
    },
    streakDays: computeStreak(workoutDates.map((t) => new Date(t)), today),
    workoutsThisWeek: new Set(weekCurrent.map((r) => r.date.getTime())).size,
    totals: {
      workouts: workoutDates.length,
      volumeKg: round(summarizeSets(rows).volumeKg, 1),
      sets: rows.length,
    },
    comparisons: {
      vsLastWorkout: percentChange(summarizeSets(previousWorkoutRows).volumeKg || null, summarizeSets(todayRows).volumeKg || null),
      vsLastWeek: percentChange(summarizeSets(weekPrevious).volumeKg || null, summarizeSets(weekCurrent).volumeKg || null),
      vsLastMonth: percentChange(summarizeSets(monthPrevious).volumeKg || null, summarizeSets(monthCurrent).volumeKg || null),
      strengthTrend: overallStrengthTrend(monthCurrent, monthPrevious),
    },
    muscleGroups: muscleGroupProgress(monthCurrent, monthPrevious),
    recentRecords: recordRows.map((r) => ({
      exerciseName: r.exercise.name,
      type: r.type,
      value: r.value,
      previousValue: r.previousValue,
      improvementPercent: r.previousValue ? round(((r.value - r.previousValue) / r.previousValue) * 100, 1) : null,
      achievedAt: utcToCivilDate(r.achievedAt),
    })),
  };
}

/**
 * Active training streak in days.
 *
 * A rest day does not break the streak – a gap of more than 3 days does.
 * The streak counts the days from the start of the uninterrupted active period
 * up to the most recent workout.
 */
export const STREAK_MAX_GAP_DAYS = 3;

export function computeStreak(workoutDates: Date[], today: Date): number {
  if (workoutDates.length === 0) return 0;
  const sorted = [...new Set(workoutDates.map((d) => d.getTime()))].sort((a, b) => b - a).map((t) => new Date(t));

  const mostRecent = sorted[0];
  if (daysBetween(mostRecent, today) > STREAK_MAX_GAP_DAYS) return 0;

  let start = mostRecent;
  for (let i = 1; i < sorted.length; i += 1) {
    const gap = daysBetween(sorted[i], start);
    if (gap > STREAK_MAX_GAP_DAYS) break;
    start = sorted[i];
  }
  return daysBetween(start, mostRecent) + 1;
}

/** "100 kg × 10 × 3" / "110 kg × 8, 110 kg × 7" / "20 min" */
export function describeSets(sets: SetRow[]): string {
  const groups: Array<{ label: string; count: number }> = [];
  for (const set of sets) {
    const label = describeSet(set);
    const last = groups[groups.length - 1];
    if (last && last.label === label) last.count += 1;
    else groups.push({ label, count: 1 });
  }
  return groups.map((g) => (g.count > 1 ? `${g.label} × ${g.count}` : g.label)).join(', ');
}

function describeSet(set: SetRow): string {
  const parts: string[] = [];
  if (set.weightKg != null && set.weightKg > 0) parts.push(`${round(set.weightKg, 2)} kg`);
  if (set.reps != null && set.reps > 0) parts.push(`${set.reps}`);
  if (parts.length > 0) return parts.join(' × ');
  if (set.durationSec != null && set.durationSec > 0) {
    const minutes = Math.round(set.durationSec / 60);
    return minutes >= 1 ? `${minutes} min` : `${set.durationSec} s`;
  }
  if (set.distanceM != null && set.distanceM > 0) return `${round(set.distanceM / 1000, 2)} km`;
  return '–';
}

// ---------------------------------------------------------------------------
// Overview (all-time / period statistics)
// ---------------------------------------------------------------------------

export interface OverviewResponse {
  period: Period;
  from: string | null;
  to: string;
  workouts: number;
  workoutsPerWeek: number;
  volumeKg: number;
  avgVolumePerWorkout: number;
  avgWeightKg: number;
  avgReps: number;
  totalSets: number;
  totalReps: number;
  durationSec: number;
  newRecords: number;
  strengthTrend: number | null;
  volumeTrend: number | null;
  muscleGroups: MuscleGroupProgress[];
  strongest: MuscleGroupProgress[];
  weakest: MuscleGroupProgress[];
  volumeSeries: Array<{ date: string; volumeKg: number; sets: number }>;
}

export async function getOverview(userId: string, period: Period, timezone: string): Promise<OverviewResponse> {
  const today = todayInZone(timezone);
  const from = periodStart(period, today);
  const rows = await loadSetRows(userId, { from, to: today });

  let previousRows: SetRow[] = [];
  if (from) {
    const length = daysBetween(from, today) + 1;
    previousRows = await loadSetRows(userId, { from: addDays(from, -length), to: addDays(from, -1) });
  }

  const summary = summarizeSets(rows);
  const workoutDates = [...new Set(rows.map((r) => r.date.getTime()))];
  const spanDays = from ? daysBetween(from, today) + 1 : Math.max(1, daysBetween(new Date(Math.min(...workoutDates, today.getTime())), today) + 1);

  const weights = rows.filter((r) => !r.isWarmup && (r.weightKg ?? 0) > 0).map((r) => r.weightKg as number);
  const reps = rows.filter((r) => !r.isWarmup && (r.reps ?? 0) > 0).map((r) => r.reps as number);

  const groups = muscleGroupProgress(rows, previousRows);
  const ranked = groups.filter((g) => g.changePercent != null).sort((a, b) => (b.changePercent as number) - (a.changePercent as number));

  const newRecords = await prisma().personalRecord.count({
    where: { userId, ...(from ? { achievedAt: { gte: from } } : {}) },
  });

  return {
    period,
    from: from ? utcToCivilDate(from) : null,
    to: utcToCivilDate(today),
    workouts: workoutDates.length,
    workoutsPerWeek: round((workoutDates.length / Math.max(1, spanDays)) * 7, 2),
    volumeKg: round(summary.volumeKg, 1),
    avgVolumePerWorkout: workoutDates.length > 0 ? round(summary.volumeKg / workoutDates.length, 1) : 0,
    avgWeightKg: round(mean(weights), 1),
    avgReps: round(mean(reps), 1),
    totalSets: summary.sets,
    totalReps: summary.reps,
    durationSec: summary.durationSec,
    newRecords,
    strengthTrend: overallStrengthTrend(rows, previousRows),
    volumeTrend: percentChange(summarizeSets(previousRows).volumeKg || null, summary.volumeKg || null),
    muscleGroups: groups,
    strongest: ranked.slice(0, 3),
    weakest: ranked.slice(-3).reverse(),
    volumeSeries: volumeByDate(rows),
  };
}

function volumeByDate(rows: SetRow[]): Array<{ date: string; volumeKg: number; sets: number }> {
  const byDate = new Map<number, { volumeKg: number; sets: number }>();
  for (const row of rows) {
    const key = row.date.getTime();
    const entry = byDate.get(key) ?? { volumeKg: 0, sets: 0 };
    entry.volumeKg += setVolume(row);
    entry.sets += 1;
    byDate.set(key, entry);
  }
  return [...byDate.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([time, entry]) => ({ date: utcToCivilDate(new Date(time)), volumeKg: round(entry.volumeKg, 1), sets: entry.sets }));
}

/** Weekly volume buckets for the bar chart. */
export function volumeByWeek(rows: SetRow[]): Array<{ weekStart: string; volumeKg: number; sets: number; workouts: number }> {
  const byWeek = new Map<number, { volumeKg: number; sets: number; days: Set<number> }>();
  for (const row of rows) {
    const start = new Date(row.date);
    const weekday = (start.getUTCDay() + 6) % 7; // Monday = 0
    start.setUTCDate(start.getUTCDate() - weekday);
    const key = start.getTime();
    const entry = byWeek.get(key) ?? { volumeKg: 0, sets: 0, days: new Set<number>() };
    entry.volumeKg += setVolume(row);
    entry.sets += 1;
    entry.days.add(row.date.getTime());
    byWeek.set(key, entry);
  }
  return [...byWeek.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([time, entry]) => ({
      weekStart: utcToCivilDate(new Date(time)),
      volumeKg: round(entry.volumeKg, 1),
      sets: entry.sets,
      workouts: entry.days.size,
    }));
}

// ---------------------------------------------------------------------------
// Per exercise statistics
// ---------------------------------------------------------------------------

export interface ExerciseStatsPoint {
  date: string;
  maxWeightKg: number | null;
  totalVolumeKg: number;
  bestE1rm: number | null;
  totalReps: number;
  sets: number;
  avgWeightKg: number | null;
}

export interface ExerciseStatsResponse {
  exerciseId: string;
  name: string;
  type: ExerciseType;
  period: Period;
  personalBestKg: number | null;
  bestReps: { reps: number; weightKg: number | null } | null;
  bestVolumeKg: number | null;
  bestE1rmKg: number | null;
  hasMeasuredOneRm: boolean;
  avgWeightKg: number | null;
  avgReps: number | null;
  totalSets: number;
  totalVolumeKg: number;
  sessions: number;
  frequencyPerWeek: number;
  progressPercent: number | null;
  series: ExerciseStatsPoint[];
}

export async function getExerciseStats(
  userId: string,
  exerciseId: string,
  period: Period,
  timezone: string,
): Promise<ExerciseStatsResponse> {
  const today = todayInZone(timezone);
  const from = periodStart(period, today);
  const rows = await loadSetRows(userId, { exerciseId, from, to: today });
  const allRows = await loadSetRows(userId, { exerciseId });

  let previousRows: SetRow[] = [];
  if (from) {
    const length = daysBetween(from, today) + 1;
    previousRows = allRows.filter((r) => r.date < from && r.date >= addDays(from, -length));
  }

  const exercise = await prisma().exercise.findFirst({
    where: { id: exerciseId, deletedAt: null, OR: [{ userId: null }, { userId }] },
    select: { name: true, type: true },
  });

  const working = rows.filter((r) => !r.isWarmup);
  const weights = working.filter((r) => (r.weightKg ?? 0) > 0).map((r) => r.weightKg as number);
  const repsList = working.filter((r) => (r.reps ?? 0) > 0).map((r) => r.reps as number);

  const bestRepsRow = working.reduce<SetRow | null>((best, row) => {
    if ((row.reps ?? 0) <= 0) return best;
    if (!best || (row.reps as number) > (best.reps as number)) return row;
    return best;
  }, null);

  const measuredOneRm = await prisma().workoutSet.count({
    where: {
      deletedAt: null,
      isOneRmTest: true,
      workoutExercise: { exerciseId, deletedAt: null, workout: { userId, deletedAt: null } },
    },
  });

  const byDate = new Map<number, SetRow[]>();
  for (const row of rows) {
    const list = byDate.get(row.date.getTime()) ?? [];
    list.push(row);
    byDate.set(row.date.getTime(), list);
  }

  const series: ExerciseStatsPoint[] = [...byDate.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([time, sets]) => {
      const summary = summarizeSets(sets);
      const setWeights = sets.filter((s) => (s.weightKg ?? 0) > 0).map((s) => s.weightKg as number);
      return {
        date: utcToCivilDate(new Date(time)),
        maxWeightKg: setWeights.length ? round(Math.max(...setWeights), 2) : null,
        totalVolumeKg: round(summary.volumeKg, 1),
        bestE1rm: bestE1rm(sets).value || null,
        totalReps: summary.reps,
        sets: summary.sets,
        avgWeightKg: setWeights.length ? round(mean(setWeights), 1) : null,
      };
    });

  const spanDays = from ? daysBetween(from, today) + 1 : Math.max(1, daysBetween(allRows[0]?.date ?? today, today) + 1);
  const trend = robustTrend(
    previousRows.filter((r) => !r.isWarmup).map(performanceMetric).filter((v): v is number => v != null),
    working.map(performanceMetric).filter((v): v is number => v != null),
  );

  return {
    exerciseId,
    name: exercise?.name ?? 'Übung',
    type: exercise?.type ?? ExerciseType.STRENGTH,
    period,
    personalBestKg: weights.length ? round(Math.max(...weights), 2) : null,
    bestReps: bestRepsRow ? { reps: bestRepsRow.reps as number, weightKg: bestRepsRow.weightKg } : null,
    bestVolumeKg: series.length ? round(Math.max(...series.map((p) => p.totalVolumeKg)), 1) : null,
    bestE1rmKg: bestE1rm(working).value || null,
    hasMeasuredOneRm: measuredOneRm > 0,
    avgWeightKg: weights.length ? round(mean(weights), 1) : null,
    avgReps: repsList.length ? round(median(repsList), 1) : null,
    totalSets: working.length,
    totalVolumeKg: round(summarizeSets(working).volumeKg, 1),
    sessions: byDate.size,
    frequencyPerWeek: round((byDate.size / Math.max(1, spanDays)) * 7, 2),
    progressPercent: trend.changePercent,
    series,
  };
}

// ---------------------------------------------------------------------------
// Calendar
// ---------------------------------------------------------------------------

export interface CalendarDay {
  date: string;
  workoutId: string;
  volumeKg: number;
  sets: number;
  exercises: number;
  muscleGroups: string[];
  records: number;
}

export async function getCalendar(userId: string, from: Date, to: Date): Promise<CalendarDay[]> {
  const rows = await loadSetRows(userId, { from, to });
  const records = await prisma().personalRecord.findMany({
    where: { userId, achievedAt: { gte: from, lte: to } },
    select: { achievedAt: true },
  });

  const recordsByDate = new Map<number, number>();
  for (const record of records) {
    recordsByDate.set(record.achievedAt.getTime(), (recordsByDate.get(record.achievedAt.getTime()) ?? 0) + 1);
  }

  const byDate = new Map<number, SetRow[]>();
  for (const row of rows) {
    const list = byDate.get(row.date.getTime()) ?? [];
    list.push(row);
    byDate.set(row.date.getTime(), list);
  }

  return [...byDate.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([time, sets]) => {
      const summary = summarizeSets(sets);
      return {
        date: utcToCivilDate(new Date(time)),
        workoutId: sets[0].workoutId,
        volumeKg: round(summary.volumeKg, 1),
        sets: summary.sets,
        exercises: new Set(sets.map((s) => s.exerciseId)).size,
        muscleGroups: [...new Set(sets.flatMap((s) => s.muscleGroups.map((mg) => mg.parentKey ?? mg.key)))],
        records: recordsByDate.get(time) ?? 0,
      };
    });
}
