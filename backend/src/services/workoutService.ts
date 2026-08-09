import { DataSource, Prisma, WorkoutStatus } from '@prisma/client';
import { badRequest, notFound } from '../lib/errors';
import { prisma } from '../lib/prisma';
import { recomputeRecordsForExercise, type RecordDelta } from './recordService';

export const workoutInclude = {
  exercises: {
    where: { deletedAt: null },
    orderBy: { position: 'asc' },
    include: {
      exercise: {
        include: { muscleGroups: { include: { muscleGroup: true } } },
      },
      sets: {
        where: { deletedAt: null },
        orderBy: { setNumber: 'asc' },
      },
    },
  },
} satisfies Prisma.WorkoutInclude;

export type WorkoutWithRelations = Prisma.WorkoutGetPayload<{ include: typeof workoutInclude }>;

export async function getWorkout(userId: string, workoutId: string): Promise<WorkoutWithRelations> {
  const workout = await prisma().workout.findFirst({
    where: { id: workoutId, userId, deletedAt: null },
    include: workoutInclude,
  });
  if (!workout) throw notFound('Training');
  return workout;
}

export interface WorkoutFilter {
  from?: Date;
  to?: Date;
  muscleGroupKey?: string;
  exerciseId?: string;
  status?: WorkoutStatus;
  limit?: number;
  offset?: number;
}

export async function listWorkouts(userId: string, filter: WorkoutFilter = {}): Promise<WorkoutWithRelations[]> {
  const where: Prisma.WorkoutWhereInput = { userId, deletedAt: null };

  if (filter.from || filter.to) {
    where.date = {};
    if (filter.from) where.date.gte = filter.from;
    if (filter.to) where.date.lte = filter.to;
  }
  if (filter.status) where.status = filter.status;

  const exerciseFilter: Prisma.WorkoutExerciseWhereInput = { deletedAt: null };
  let hasExerciseFilter = false;
  if (filter.exerciseId) {
    exerciseFilter.exerciseId = filter.exerciseId;
    hasExerciseFilter = true;
  }
  if (filter.muscleGroupKey) {
    exerciseFilter.exercise = { muscleGroups: { some: { muscleGroup: { key: filter.muscleGroupKey } } } };
    hasExerciseFilter = true;
  }
  if (hasExerciseFilter) where.exercises = { some: exerciseFilter };

  return prisma().workout.findMany({
    where,
    include: workoutInclude,
    orderBy: [{ date: 'desc' }, { createdAt: 'desc' }],
    take: filter.limit ?? 50,
    skip: filter.offset ?? 0,
  });
}

/**
 * One workout per calendar day is the model the bot works with: talking about
 * bench press and later about squats on the same day extends the same session.
 */
export async function getOrCreateWorkoutForDate(
  userId: string,
  date: Date,
  source: DataSource = DataSource.MANUAL,
): Promise<WorkoutWithRelations> {
  const existing = await prisma().workout.findFirst({
    where: { userId, date, deletedAt: null },
    include: workoutInclude,
    orderBy: { createdAt: 'asc' },
  });
  if (existing) return existing;

  const created = await prisma().workout.create({
    data: { userId, date, source, status: WorkoutStatus.COMPLETED },
    include: workoutInclude,
  });
  return created;
}

export async function createWorkout(
  userId: string,
  input: { date: Date; title?: string | null; notes?: string | null; status?: WorkoutStatus; startedAt?: Date | null; source?: DataSource },
): Promise<WorkoutWithRelations> {
  return prisma().workout.create({
    data: {
      userId,
      date: input.date,
      title: input.title ?? null,
      notes: input.notes ?? null,
      status: input.status ?? WorkoutStatus.IN_PROGRESS,
      startedAt: input.startedAt ?? new Date(),
      source: input.source ?? DataSource.MANUAL,
    },
    include: workoutInclude,
  });
}

export async function updateWorkout(
  userId: string,
  workoutId: string,
  input: {
    date?: Date;
    title?: string | null;
    notes?: string | null;
    status?: WorkoutStatus;
    startedAt?: Date | null;
    endedAt?: Date | null;
    durationSec?: number | null;
  },
): Promise<WorkoutWithRelations> {
  await getWorkout(userId, workoutId);
  await prisma().workout.update({ where: { id: workoutId }, data: { ...input } });

  // Changing the date shifts every record of that session.
  if (input.date) await recomputeRecordsForWorkout(userId, workoutId);
  return getWorkout(userId, workoutId);
}

export async function deleteWorkout(userId: string, workoutId: string): Promise<void> {
  const workout = await getWorkout(userId, workoutId);
  const exerciseIds = workout.exercises.map((e) => e.exerciseId);
  const now = new Date();

  await prisma().$transaction([
    prisma().workoutSet.updateMany({
      where: { workoutExercise: { workoutId }, deletedAt: null },
      data: { deletedAt: now },
    }),
    prisma().workoutExercise.updateMany({ where: { workoutId, deletedAt: null }, data: { deletedAt: now } }),
    prisma().workout.update({ where: { id: workoutId }, data: { deletedAt: now } }),
  ]);

  for (const exerciseId of new Set(exerciseIds)) {
    await recomputeRecordsForExercise(userId, exerciseId);
  }
}

async function recomputeRecordsForWorkout(userId: string, workoutId: string): Promise<RecordDelta[]> {
  const rows = await prisma().workoutExercise.findMany({
    where: { workoutId, deletedAt: null },
    select: { exerciseId: true },
  });
  const deltas: RecordDelta[] = [];
  for (const exerciseId of new Set(rows.map((r) => r.exerciseId))) {
    deltas.push(...(await recomputeRecordsForExercise(userId, exerciseId)));
  }
  return deltas;
}

// ---------------------------------------------------------------------------
// Workout exercises
// ---------------------------------------------------------------------------

export async function addExerciseToWorkout(
  userId: string,
  workoutId: string,
  exerciseId: string,
  options: { notes?: string | null } = {},
) {
  const workout = await getWorkout(userId, workoutId);
  const exercise = await prisma().exercise.findFirst({
    where: { id: exerciseId, deletedAt: null, OR: [{ userId: null }, { userId }] },
  });
  if (!exercise) throw notFound('Übung');

  const existing = workout.exercises.find((e) => e.exerciseId === exerciseId);
  if (existing) return existing;

  const position = workout.exercises.length;
  return prisma().workoutExercise.create({
    data: { workoutId, exerciseId, position, notes: options.notes ?? null },
  });
}

export async function updateWorkoutExercise(
  userId: string,
  workoutExerciseId: string,
  input: { position?: number; notes?: string | null; exerciseId?: string },
) {
  const link = await findWorkoutExercise(userId, workoutExerciseId);
  const previousExerciseId = link.exerciseId;

  const updated = await prisma().workoutExercise.update({
    where: { id: workoutExerciseId },
    data: { ...input },
  });

  if (input.exerciseId && input.exerciseId !== previousExerciseId) {
    await recomputeRecordsForExercise(userId, previousExerciseId);
    await recomputeRecordsForExercise(userId, input.exerciseId);
  }
  return updated;
}

export async function removeWorkoutExercise(userId: string, workoutExerciseId: string): Promise<void> {
  const link = await findWorkoutExercise(userId, workoutExerciseId);
  const now = new Date();
  await prisma().$transaction([
    prisma().workoutSet.updateMany({ where: { workoutExerciseId, deletedAt: null }, data: { deletedAt: now } }),
    prisma().workoutExercise.update({ where: { id: workoutExerciseId }, data: { deletedAt: now } }),
  ]);
  await recomputeRecordsForExercise(userId, link.exerciseId);
}

async function findWorkoutExercise(userId: string, workoutExerciseId: string) {
  const link = await prisma().workoutExercise.findFirst({
    where: { id: workoutExerciseId, deletedAt: null, workout: { userId, deletedAt: null } },
  });
  if (!link) throw notFound('Trainingsübung');
  return link;
}

// ---------------------------------------------------------------------------
// Sets
// ---------------------------------------------------------------------------

export interface SetInput {
  weightKg?: number | null;
  reps?: number | null;
  durationSec?: number | null;
  distanceM?: number | null;
  rpe?: number | null;
  isWarmup?: boolean;
  isOneRmTest?: boolean;
  notes?: string | null;
  source?: DataSource;
  confidence?: number | null;
  aiParsingResultId?: string | null;
}

function validateSet(input: SetInput): void {
  const hasPayload =
    (input.weightKg ?? 0) > 0 || (input.reps ?? 0) > 0 || (input.durationSec ?? 0) > 0 || (input.distanceM ?? 0) > 0;
  if (!hasPayload) throw badRequest('Ein Satz braucht mindestens Gewicht, Wiederholungen, Dauer oder Distanz.');
  if ((input.reps ?? 0) < 0 || (input.weightKg ?? 0) < 0) throw badRequest('Negative Werte sind nicht erlaubt.');
  if (input.rpe != null && (input.rpe < 1 || input.rpe > 10)) throw badRequest('RPE muss zwischen 1 und 10 liegen.');
}

export async function addSet(userId: string, workoutExerciseId: string, input: SetInput) {
  validateSet(input);
  const link = await findWorkoutExercise(userId, workoutExerciseId);
  const last = await prisma().workoutSet.findFirst({
    where: { workoutExerciseId, deletedAt: null },
    orderBy: { setNumber: 'desc' },
  });

  const created = await prisma().workoutSet.create({
    data: {
      workoutExerciseId,
      setNumber: (last?.setNumber ?? 0) + 1,
      weightKg: input.weightKg ?? null,
      reps: input.reps ?? null,
      durationSec: input.durationSec ?? null,
      distanceM: input.distanceM ?? null,
      rpe: input.rpe ?? null,
      isWarmup: input.isWarmup ?? false,
      isOneRmTest: input.isOneRmTest ?? false,
      notes: input.notes ?? null,
      source: input.source ?? DataSource.MANUAL,
      confidence: input.confidence ?? null,
      aiParsingResultId: input.aiParsingResultId ?? null,
    },
  });

  await recomputeRecordsForExercise(userId, link.exerciseId);
  return created;
}

export async function updateSet(userId: string, setId: string, input: SetInput & { setNumber?: number }) {
  const set = await prisma().workoutSet.findFirst({
    where: { id: setId, deletedAt: null, workoutExercise: { deletedAt: null, workout: { userId, deletedAt: null } } },
    include: { workoutExercise: { select: { exerciseId: true } } },
  });
  if (!set) throw notFound('Satz');

  const merged: SetInput = {
    weightKg: input.weightKg !== undefined ? input.weightKg : set.weightKg,
    reps: input.reps !== undefined ? input.reps : set.reps,
    durationSec: input.durationSec !== undefined ? input.durationSec : set.durationSec,
    distanceM: input.distanceM !== undefined ? input.distanceM : set.distanceM,
    rpe: input.rpe !== undefined ? input.rpe : set.rpe,
  };
  validateSet(merged);

  const updated = await prisma().workoutSet.update({
    where: { id: setId },
    data: {
      ...merged,
      isWarmup: input.isWarmup !== undefined ? input.isWarmup : set.isWarmup,
      isOneRmTest: input.isOneRmTest !== undefined ? input.isOneRmTest : set.isOneRmTest,
      notes: input.notes !== undefined ? input.notes : set.notes,
      setNumber: input.setNumber ?? set.setNumber,
      // A manual edit overrides the AI value, so the confidence badge disappears.
      source: input.source ?? DataSource.MANUAL,
      confidence: input.confidence !== undefined ? input.confidence : null,
    },
  });

  await recomputeRecordsForExercise(userId, set.workoutExercise.exerciseId);
  return updated;
}

export async function deleteSet(userId: string, setId: string): Promise<void> {
  const set = await prisma().workoutSet.findFirst({
    where: { id: setId, deletedAt: null, workoutExercise: { workout: { userId, deletedAt: null } } },
    include: { workoutExercise: { select: { exerciseId: true } } },
  });
  if (!set) throw notFound('Satz');

  await prisma().workoutSet.update({ where: { id: setId }, data: { deletedAt: new Date() } });
  await recomputeRecordsForExercise(userId, set.workoutExercise.exerciseId);
}

/** Renumbers the remaining sets so the UI never shows gaps (1,2,4). */
export async function renumberSets(userId: string, workoutExerciseId: string): Promise<void> {
  await findWorkoutExercise(userId, workoutExerciseId);
  const sets = await prisma().workoutSet.findMany({
    where: { workoutExerciseId, deletedAt: null },
    orderBy: { setNumber: 'asc' },
    select: { id: true },
  });
  await prisma().$transaction(
    sets.map((set, index) => prisma().workoutSet.update({ where: { id: set.id }, data: { setNumber: index + 1 } })),
  );
}
