import { PersonalRecordType, type PersonalRecord } from '@prisma/client';
import { estimateOneRepMax, setVolume } from '../domain/calculations';
import { prisma } from '../lib/prisma';
import { round } from '../lib/units';

/**
 * Personal records are derived data. Instead of incrementally patching them
 * (which drifts as soon as the user edits or deletes a set), the full history
 * of one exercise is replayed chronologically and the record table is rebuilt.
 * That keeps records correct after every edit, at negligible cost for a
 * single-user data set.
 */

interface ReplaySet {
  id: string;
  date: Date;
  workoutId: string;
  weightKg: number | null;
  reps: number | null;
  durationSec: number | null;
  distanceM: number | null;
  isWarmup: boolean;
}

export interface RecordDelta {
  type: PersonalRecordType;
  value: number;
  previousValue: number | null;
  improvementPercent: number | null;
  weightKg: number | null;
  reps: number | null;
  achievedAt: Date;
}

async function loadSets(userId: string, exerciseId: string): Promise<ReplaySet[]> {
  const rows = await prisma().workoutSet.findMany({
    where: {
      deletedAt: null,
      workoutExercise: {
        deletedAt: null,
        exerciseId,
        workout: { userId, deletedAt: null },
      },
    },
    select: {
      id: true,
      weightKg: true,
      reps: true,
      durationSec: true,
      distanceM: true,
      isWarmup: true,
      createdAt: true,
      workoutExercise: { select: { workoutId: true, workout: { select: { date: true } } } },
    },
    orderBy: [{ workoutExercise: { workout: { date: 'asc' } } }, { createdAt: 'asc' }],
  });

  return rows.map((row) => ({
    id: row.id,
    date: row.workoutExercise.workout.date,
    workoutId: row.workoutExercise.workoutId,
    weightKg: row.weightKg,
    reps: row.reps,
    durationSec: row.durationSec,
    distanceM: row.distanceM,
    isWarmup: row.isWarmup,
  }));
}

interface RecordCandidate {
  value: number;
  weightKg: number | null;
  reps: number | null;
  setId: string | null;
  date: Date;
}

function candidatesFor(type: PersonalRecordType, sets: ReplaySet[]): RecordCandidate[] {
  const working = sets.filter((s) => !s.isWarmup);

  switch (type) {
    case PersonalRecordType.MAX_WEIGHT:
      return working
        .filter((s) => s.weightKg != null && s.weightKg > 0 && (s.reps ?? 0) > 0)
        .map((s) => ({ value: s.weightKg as number, weightKg: s.weightKg, reps: s.reps, setId: s.id, date: s.date }));

    case PersonalRecordType.MAX_REPS:
      return working
        .filter((s) => (s.reps ?? 0) > 0)
        .map((s) => ({ value: s.reps as number, weightKg: s.weightKg, reps: s.reps, setId: s.id, date: s.date }));

    case PersonalRecordType.MAX_VOLUME_SET:
      return working
        .map((s) => ({ value: setVolume(s), weightKg: s.weightKg, reps: s.reps, setId: s.id, date: s.date }))
        .filter((c) => c.value > 0);

    case PersonalRecordType.BEST_E1RM:
      return working
        .filter((s) => s.weightKg != null && s.weightKg > 0 && (s.reps ?? 0) > 0)
        .map((s) => ({
          value: estimateOneRepMax(s.weightKg as number, s.reps as number),
          weightKg: s.weightKg,
          reps: s.reps,
          setId: s.id,
          date: s.date,
        }))
        .filter((c) => c.value > 0);

    case PersonalRecordType.LONGEST_DURATION:
      return working
        .filter((s) => (s.durationSec ?? 0) > 0)
        .map((s) => ({ value: s.durationSec as number, weightKg: null, reps: null, setId: s.id, date: s.date }));

    case PersonalRecordType.LONGEST_DISTANCE:
      return working
        .filter((s) => (s.distanceM ?? 0) > 0)
        .map((s) => ({ value: s.distanceM as number, weightKg: null, reps: null, setId: s.id, date: s.date }));

    case PersonalRecordType.MAX_VOLUME_SESSION: {
      const byWorkout = new Map<string, { value: number; date: Date }>();
      for (const set of working) {
        const entry = byWorkout.get(set.workoutId) ?? { value: 0, date: set.date };
        entry.value += setVolume(set);
        byWorkout.set(set.workoutId, entry);
      }
      return [...byWorkout.values()]
        .filter((e) => e.value > 0)
        .sort((a, b) => a.date.getTime() - b.date.getTime())
        .map((e) => ({ value: round(e.value, 1), weightKg: null, reps: null, setId: null, date: e.date }));
    }

    default:
      return [];
  }
}

const ALL_TYPES: PersonalRecordType[] = [
  PersonalRecordType.MAX_WEIGHT,
  PersonalRecordType.MAX_REPS,
  PersonalRecordType.MAX_VOLUME_SET,
  PersonalRecordType.MAX_VOLUME_SESSION,
  PersonalRecordType.BEST_E1RM,
  PersonalRecordType.LONGEST_DURATION,
  PersonalRecordType.LONGEST_DISTANCE,
];

/**
 * Rebuilds all records of one exercise and returns the records that are new
 * compared to the state before the call (used for the "🔥 NEW PR" message).
 */
export async function recomputeRecordsForExercise(userId: string, exerciseId: string): Promise<RecordDelta[]> {
  const before = await prisma().personalRecord.findMany({ where: { userId, exerciseId } });
  const beforeBest = new Map<PersonalRecordType, number>();
  for (const record of before) {
    const current = beforeBest.get(record.type) ?? 0;
    if (record.value > current) beforeBest.set(record.type, record.value);
  }

  const sets = await loadSets(userId, exerciseId);
  const rows: Array<Omit<PersonalRecord, 'id' | 'createdAt' | 'updatedAt'>> = [];

  for (const type of ALL_TYPES) {
    let best = 0;
    for (const candidate of candidatesFor(type, sets)) {
      if (candidate.value > best + 1e-9) {
        rows.push({
          userId,
          exerciseId,
          type,
          value: round(candidate.value, 2),
          previousValue: best > 0 ? round(best, 2) : null,
          weightKg: candidate.weightKg,
          reps: candidate.reps,
          achievedAt: candidate.date,
          workoutSetId: candidate.setId,
        });
        best = candidate.value;
      }
    }
  }

  await prisma().$transaction([
    prisma().personalRecord.deleteMany({ where: { userId, exerciseId } }),
    prisma().personalRecord.createMany({ data: rows }),
  ]);

  const deltas: RecordDelta[] = [];
  const afterBest = new Map<PersonalRecordType, (typeof rows)[number]>();
  for (const row of rows) {
    const current = afterBest.get(row.type);
    if (!current || row.value > current.value) afterBest.set(row.type, row);
  }

  for (const [type, row] of afterBest) {
    const previous = beforeBest.get(type) ?? 0;
    if (row.value > previous + 1e-9) {
      deltas.push({
        type,
        value: row.value,
        previousValue: previous > 0 ? round(previous, 2) : null,
        improvementPercent: previous > 0 ? round(((row.value - previous) / previous) * 100, 1) : null,
        weightKg: row.weightKg,
        reps: row.reps,
        achievedAt: row.achievedAt,
      });
    }
  }

  return deltas;
}

/** Current best record per type for one exercise. */
export async function getRecordsForExercise(userId: string, exerciseId: string) {
  const records = await prisma().personalRecord.findMany({
    where: { userId, exerciseId },
    orderBy: [{ type: 'asc' }, { value: 'desc' }],
  });
  const best = new Map<PersonalRecordType, (typeof records)[number]>();
  for (const record of records) {
    const current = best.get(record.type);
    if (!current || record.value > current.value) best.set(record.type, record);
  }
  return [...best.values()];
}

/** All current records of the user, newest first – used by the app overview. */
export async function listRecords(userId: string, limit = 100) {
  const records = await prisma().personalRecord.findMany({
    where: { userId, exercise: { deletedAt: null } },
    include: { exercise: { select: { id: true, name: true, nameDe: true, type: true } } },
    orderBy: [{ achievedAt: 'desc' }],
    take: 500,
  });

  const best = new Map<string, (typeof records)[number]>();
  for (const record of records) {
    const key = `${record.exerciseId}:${record.type}`;
    const current = best.get(key);
    if (!current || record.value > current.value) best.set(key, record);
  }
  return [...best.values()].sort((a, b) => b.achievedAt.getTime() - a.achievedAt.getTime()).slice(0, limit);
}
