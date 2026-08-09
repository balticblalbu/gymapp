import { DataSource, WorkoutStatus } from '@prisma/client';
import { z } from 'zod';
import { civilDateToUtc, utcToCivilDate } from '../lib/dates';
import { badRequest, notFound } from '../lib/errors';
import { prisma } from '../lib/prisma';
import { recomputeRecordsForExercise } from './recordService';

/**
 * Delta synchronisation for the offline capable Android client.
 *
 * Pull:  everything changed after `since`, including tombstones (deletedAt set)
 *        so the client can remove locally cached rows.
 * Push:  client side changes as idempotent upserts. The client generates the
 *        UUIDs, which makes a retried push harmless.
 *
 * Conflict rule: **last write wins based on `updatedAt`**. If the server row is
 * newer than the version the client started from, the client change is rejected
 * and the current server state is returned in `conflicts`, so the app can
 * refresh instead of silently overwriting a change made via Telegram.
 */

export const syncOperationSchema = z.discriminatedUnion('entity', [
  z.object({
    entity: z.literal('workout'),
    op: z.enum(['upsert', 'delete']),
    id: z.string().uuid(),
    baseUpdatedAt: z.string().datetime().nullable().optional(),
    data: z
      .object({
        date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
        title: z.string().nullable().optional(),
        notes: z.string().nullable().optional(),
        status: z.nativeEnum(WorkoutStatus).optional(),
        durationSec: z.number().int().nonnegative().nullable().optional(),
        startedAt: z.string().datetime().nullable().optional(),
        endedAt: z.string().datetime().nullable().optional(),
      })
      .optional(),
  }),
  z.object({
    entity: z.literal('workoutExercise'),
    op: z.enum(['upsert', 'delete']),
    id: z.string().uuid(),
    baseUpdatedAt: z.string().datetime().nullable().optional(),
    data: z
      .object({
        workoutId: z.string().uuid(),
        exerciseId: z.string().uuid(),
        position: z.number().int().nonnegative().optional(),
        notes: z.string().nullable().optional(),
      })
      .optional(),
  }),
  z.object({
    entity: z.literal('set'),
    op: z.enum(['upsert', 'delete']),
    id: z.string().uuid(),
    baseUpdatedAt: z.string().datetime().nullable().optional(),
    data: z
      .object({
        workoutExerciseId: z.string().uuid(),
        setNumber: z.number().int().positive(),
        weightKg: z.number().nonnegative().nullable().optional(),
        reps: z.number().int().nonnegative().nullable().optional(),
        durationSec: z.number().int().nonnegative().nullable().optional(),
        distanceM: z.number().nonnegative().nullable().optional(),
        rpe: z.number().min(1).max(10).nullable().optional(),
        isWarmup: z.boolean().optional(),
        isOneRmTest: z.boolean().optional(),
        notes: z.string().nullable().optional(),
      })
      .optional(),
  }),
]);

export type SyncOperation = z.infer<typeof syncOperationSchema>;

export interface SyncConflict {
  entity: string;
  id: string;
  reason: 'server_newer' | 'not_found' | 'invalid';
  message: string;
}

export interface PushResult {
  applied: number;
  conflicts: SyncConflict[];
  touchedExercises: string[];
}

function isServerNewer(serverUpdatedAt: Date, baseUpdatedAt?: string | null): boolean {
  if (!baseUpdatedAt) return false;
  return serverUpdatedAt.getTime() > new Date(baseUpdatedAt).getTime() + 1;
}

export async function pushChanges(userId: string, operations: SyncOperation[]): Promise<PushResult> {
  const conflicts: SyncConflict[] = [];
  const touchedExercises = new Set<string>();
  let applied = 0;

  for (const operation of operations) {
    try {
      switch (operation.entity) {
        case 'workout':
          await applyWorkoutOp(userId, operation, conflicts, touchedExercises);
          break;
        case 'workoutExercise':
          await applyWorkoutExerciseOp(userId, operation, conflicts, touchedExercises);
          break;
        case 'set':
          await applySetOp(userId, operation, conflicts, touchedExercises);
          break;
      }
      applied += 1;
    } catch (error) {
      conflicts.push({
        entity: operation.entity,
        id: operation.id,
        reason: 'invalid',
        message: (error as Error).message,
      });
    }
  }

  for (const exerciseId of touchedExercises) {
    await recomputeRecordsForExercise(userId, exerciseId);
  }

  return { applied: applied - conflicts.length, conflicts, touchedExercises: [...touchedExercises] };
}

async function applyWorkoutOp(
  userId: string,
  operation: Extract<SyncOperation, { entity: 'workout' }>,
  conflicts: SyncConflict[],
  touched: Set<string>,
): Promise<void> {
  const existing = await prisma().workout.findFirst({ where: { id: operation.id, userId } });

  if (operation.op === 'delete') {
    if (!existing) return;
    const links = await prisma().workoutExercise.findMany({ where: { workoutId: existing.id }, select: { exerciseId: true } });
    links.forEach((l) => touched.add(l.exerciseId));
    const now = new Date();
    await prisma().$transaction([
      prisma().workoutSet.updateMany({ where: { workoutExercise: { workoutId: existing.id } }, data: { deletedAt: now } }),
      prisma().workoutExercise.updateMany({ where: { workoutId: existing.id }, data: { deletedAt: now } }),
      prisma().workout.update({ where: { id: existing.id }, data: { deletedAt: now } }),
    ]);
    return;
  }

  if (!operation.data) throw badRequest('data fehlt für upsert');
  if (existing && isServerNewer(existing.updatedAt, operation.baseUpdatedAt)) {
    conflicts.push({ entity: 'workout', id: operation.id, reason: 'server_newer', message: 'Serverstand ist neuer.' });
    return;
  }

  const payload = {
    date: civilDateToUtc(operation.data.date),
    title: operation.data.title ?? null,
    notes: operation.data.notes ?? null,
    status: operation.data.status ?? WorkoutStatus.COMPLETED,
    durationSec: operation.data.durationSec ?? null,
    startedAt: operation.data.startedAt ? new Date(operation.data.startedAt) : null,
    endedAt: operation.data.endedAt ? new Date(operation.data.endedAt) : null,
    deletedAt: null,
  };

  await prisma().workout.upsert({
    where: { id: operation.id },
    create: { id: operation.id, userId, source: DataSource.MANUAL, ...payload },
    update: payload,
  });
}

async function applyWorkoutExerciseOp(
  userId: string,
  operation: Extract<SyncOperation, { entity: 'workoutExercise' }>,
  conflicts: SyncConflict[],
  touched: Set<string>,
): Promise<void> {
  const existing = await prisma().workoutExercise.findFirst({
    where: { id: operation.id, workout: { userId } },
  });

  if (operation.op === 'delete') {
    if (!existing) return;
    touched.add(existing.exerciseId);
    const now = new Date();
    await prisma().$transaction([
      prisma().workoutSet.updateMany({ where: { workoutExerciseId: existing.id }, data: { deletedAt: now } }),
      prisma().workoutExercise.update({ where: { id: existing.id }, data: { deletedAt: now } }),
    ]);
    return;
  }

  if (!operation.data) throw badRequest('data fehlt für upsert');
  if (existing && isServerNewer(existing.updatedAt, operation.baseUpdatedAt)) {
    conflicts.push({ entity: 'workoutExercise', id: operation.id, reason: 'server_newer', message: 'Serverstand ist neuer.' });
    return;
  }

  const workout = await prisma().workout.findFirst({ where: { id: operation.data.workoutId, userId } });
  if (!workout) throw notFound('Training');

  const exercise = await prisma().exercise.findFirst({
    where: { id: operation.data.exerciseId, deletedAt: null, OR: [{ userId: null }, { userId }] },
  });
  if (!exercise) throw notFound('Übung');
  touched.add(exercise.id);

  const payload = {
    workoutId: operation.data.workoutId,
    exerciseId: operation.data.exerciseId,
    position: operation.data.position ?? 0,
    notes: operation.data.notes ?? null,
    deletedAt: null,
  };

  await prisma().workoutExercise.upsert({
    where: { id: operation.id },
    create: { id: operation.id, ...payload },
    update: payload,
  });
}

async function applySetOp(
  userId: string,
  operation: Extract<SyncOperation, { entity: 'set' }>,
  conflicts: SyncConflict[],
  touched: Set<string>,
): Promise<void> {
  const existing = await prisma().workoutSet.findFirst({
    where: { id: operation.id, workoutExercise: { workout: { userId } } },
    include: { workoutExercise: { select: { exerciseId: true } } },
  });

  if (operation.op === 'delete') {
    if (!existing) return;
    touched.add(existing.workoutExercise.exerciseId);
    await prisma().workoutSet.update({ where: { id: existing.id }, data: { deletedAt: new Date() } });
    return;
  }

  if (!operation.data) throw badRequest('data fehlt für upsert');
  if (existing && isServerNewer(existing.updatedAt, operation.baseUpdatedAt)) {
    conflicts.push({ entity: 'set', id: operation.id, reason: 'server_newer', message: 'Serverstand ist neuer.' });
    return;
  }

  const link = await prisma().workoutExercise.findFirst({
    where: { id: operation.data.workoutExerciseId, workout: { userId } },
  });
  if (!link) throw notFound('Trainingsübung');
  touched.add(link.exerciseId);

  const payload = {
    workoutExerciseId: operation.data.workoutExerciseId,
    setNumber: operation.data.setNumber,
    weightKg: operation.data.weightKg ?? null,
    reps: operation.data.reps ?? null,
    durationSec: operation.data.durationSec ?? null,
    distanceM: operation.data.distanceM ?? null,
    rpe: operation.data.rpe ?? null,
    isWarmup: operation.data.isWarmup ?? false,
    isOneRmTest: operation.data.isOneRmTest ?? false,
    notes: operation.data.notes ?? null,
    deletedAt: null,
  };

  await prisma().workoutSet.upsert({
    where: { id: operation.id },
    create: { id: operation.id, source: DataSource.MANUAL, ...payload },
    update: payload,
  });
}

// ---------------------------------------------------------------------------
// Pull
// ---------------------------------------------------------------------------

export async function pullChanges(userId: string, since: Date | null) {
  const changedAfter = since ? { gt: since } : undefined;

  const exercises = await prisma().exercise.findMany({
    where: {
      OR: [{ userId: null }, { userId }],
      ...(changedAfter ? { updatedAt: changedAfter } : {}),
    },
    include: { muscleGroups: { include: { muscleGroup: true } }, aliases: true },
  });

  const workouts = await prisma().workout.findMany({
    where: {
      userId,
      ...(changedAfter
        ? {
            OR: [
              { updatedAt: changedAfter },
              { exercises: { some: { updatedAt: changedAfter } } },
              { exercises: { some: { sets: { some: { updatedAt: changedAfter } } } } },
            ],
          }
        : {}),
    },
    include: {
      exercises: {
        include: { sets: true, exercise: { select: { name: true, nameDe: true, type: true } } },
        orderBy: { position: 'asc' },
      },
    },
    orderBy: { date: 'desc' },
  });

  const records = await prisma().personalRecord.findMany({
    where: { userId, ...(changedAfter ? { updatedAt: changedAfter } : {}) },
  });

  return {
    serverTime: new Date().toISOString(),
    exercises: exercises.map((exercise) => ({
      id: exercise.id,
      name: exercise.name,
      nameDe: exercise.nameDe,
      type: exercise.type,
      equipment: exercise.equipment,
      notes: exercise.notes,
      isGlobal: exercise.userId === null,
      isCustom: exercise.isCustom,
      muscleGroups: exercise.muscleGroups.map((mg) => mg.muscleGroup.key),
      updatedAt: exercise.updatedAt,
      deletedAt: exercise.deletedAt,
    })),
    workouts: workouts.map((workout) => ({
      id: workout.id,
      date: utcToCivilDate(workout.date),
      title: workout.title,
      notes: workout.notes,
      status: workout.status,
      startedAt: workout.startedAt,
      endedAt: workout.endedAt,
      durationSec: workout.durationSec,
      source: workout.source,
      updatedAt: workout.updatedAt,
      deletedAt: workout.deletedAt,
      exercises: workout.exercises.map((link) => ({
        id: link.id,
        workoutId: link.workoutId,
        exerciseId: link.exerciseId,
        name: link.exercise.name,
        type: link.exercise.type,
        position: link.position,
        notes: link.notes,
        updatedAt: link.updatedAt,
        deletedAt: link.deletedAt,
        sets: link.sets.map((set) => ({
          id: set.id,
          workoutExerciseId: set.workoutExerciseId,
          setNumber: set.setNumber,
          weightKg: set.weightKg,
          reps: set.reps,
          durationSec: set.durationSec,
          distanceM: set.distanceM,
          rpe: set.rpe,
          isWarmup: set.isWarmup,
          isOneRmTest: set.isOneRmTest,
          notes: set.notes,
          source: set.source,
          confidence: set.confidence,
          updatedAt: set.updatedAt,
          deletedAt: set.deletedAt,
        })),
      })),
    })),
    records: records.map((record) => ({
      id: record.id,
      exerciseId: record.exerciseId,
      type: record.type,
      value: record.value,
      previousValue: record.previousValue,
      weightKg: record.weightKg,
      reps: record.reps,
      achievedAt: record.achievedAt,
      updatedAt: record.updatedAt,
    })),
  };
}
