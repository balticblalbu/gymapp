import type { FastifyInstance } from 'fastify';
import { WorkoutStatus } from '@prisma/client';
import { z } from 'zod';
import { civilDateToUtc, utcToCivilDate } from '../lib/dates';
import { summarizeSets } from '../domain/calculations';
import { round } from '../lib/units';
import {
  addExerciseToWorkout,
  addSet,
  createWorkout,
  deleteSet,
  deleteWorkout,
  getWorkout,
  listWorkouts,
  removeWorkoutExercise,
  renumberSets,
  updateSet,
  updateWorkout,
  updateWorkoutExercise,
  type WorkoutWithRelations,
} from '../services/workoutService';
import { parseBody } from './auth';

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Datum muss im Format YYYY-MM-DD sein.');

const setBody = z.object({
  weightKg: z.number().nonnegative().nullable().optional(),
  reps: z.number().int().nonnegative().nullable().optional(),
  durationSec: z.number().int().nonnegative().nullable().optional(),
  distanceM: z.number().nonnegative().nullable().optional(),
  rpe: z.number().min(1).max(10).nullable().optional(),
  isWarmup: z.boolean().optional(),
  isOneRmTest: z.boolean().optional(),
  notes: z.string().nullable().optional(),
});

export function serializeWorkout(workout: WorkoutWithRelations) {
  const allSets = workout.exercises.flatMap((e) => e.sets);
  const summary = summarizeSets(allSets);

  return {
    id: workout.id,
    date: utcToCivilDate(workout.date),
    title: workout.title,
    notes: workout.notes,
    status: workout.status,
    startedAt: workout.startedAt,
    endedAt: workout.endedAt,
    durationSec: workout.durationSec,
    source: workout.source,
    volumeKg: round(summary.volumeKg, 1),
    totalSets: summary.sets,
    totalReps: summary.reps,
    createdAt: workout.createdAt,
    updatedAt: workout.updatedAt,
    exercises: workout.exercises.map((link) => {
      const linkSummary = summarizeSets(link.sets);
      return {
        id: link.id,
        exerciseId: link.exerciseId,
        name: link.exercise.name,
        nameDe: link.exercise.nameDe,
        type: link.exercise.type,
        position: link.position,
        notes: link.notes,
        muscleGroups: link.exercise.muscleGroups.map((mg) => mg.muscleGroup.key),
        volumeKg: round(linkSummary.volumeKg, 1),
        sets: link.sets.map((set) => ({
          id: set.id,
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
        })),
      };
    }),
  };
}

export default async function workoutRoutes(app: FastifyInstance) {
  app.addHook('preHandler', app.authenticate);

  app.get('/', async (request) => {
    const query = request.query as {
      from?: string;
      to?: string;
      muscleGroup?: string;
      exerciseId?: string;
      status?: WorkoutStatus;
      limit?: string;
      offset?: string;
    };

    const workouts = await listWorkouts(request.currentUser.id, {
      from: query.from ? civilDateToUtc(query.from) : undefined,
      to: query.to ? civilDateToUtc(query.to) : undefined,
      muscleGroupKey: query.muscleGroup,
      exerciseId: query.exerciseId,
      status: query.status,
      limit: query.limit ? Math.min(200, Number(query.limit)) : undefined,
      offset: query.offset ? Number(query.offset) : undefined,
    });
    return { workouts: workouts.map(serializeWorkout) };
  });

  app.post('/', async (request, reply) => {
    const body = parseBody(
      z.object({
        date: isoDate,
        title: z.string().nullable().optional(),
        notes: z.string().nullable().optional(),
        status: z.nativeEnum(WorkoutStatus).optional(),
      }),
      request.body,
    );
    const workout = await createWorkout(request.currentUser.id, { ...body, date: civilDateToUtc(body.date) });
    return reply.code(201).send({ workout: serializeWorkout(workout) });
  });

  app.get('/:id', async (request) => {
    const { id } = request.params as { id: string };
    return { workout: serializeWorkout(await getWorkout(request.currentUser.id, id)) };
  });

  app.patch('/:id', async (request) => {
    const { id } = request.params as { id: string };
    const body = parseBody(
      z.object({
        date: isoDate.optional(),
        title: z.string().nullable().optional(),
        notes: z.string().nullable().optional(),
        status: z.nativeEnum(WorkoutStatus).optional(),
        startedAt: z.string().datetime().nullable().optional(),
        endedAt: z.string().datetime().nullable().optional(),
        durationSec: z.number().int().nonnegative().nullable().optional(),
      }),
      request.body,
    );

    const workout = await updateWorkout(request.currentUser.id, id, {
      ...body,
      date: body.date ? civilDateToUtc(body.date) : undefined,
      startedAt: body.startedAt ? new Date(body.startedAt) : body.startedAt === null ? null : undefined,
      endedAt: body.endedAt ? new Date(body.endedAt) : body.endedAt === null ? null : undefined,
    });
    return { workout: serializeWorkout(workout) };
  });

  app.delete('/:id', async (request, reply) => {
    const { id } = request.params as { id: string };
    await deleteWorkout(request.currentUser.id, id);
    return reply.code(204).send();
  });

  // --- exercises inside a workout ---------------------------------------
  app.post('/:id/exercises', async (request, reply) => {
    const { id } = request.params as { id: string };
    const body = parseBody(z.object({ exerciseId: z.string().uuid(), notes: z.string().nullable().optional() }), request.body);
    await addExerciseToWorkout(request.currentUser.id, id, body.exerciseId, { notes: body.notes });
    return reply.code(201).send({ workout: serializeWorkout(await getWorkout(request.currentUser.id, id)) });
  });

  app.patch('/exercises/:workoutExerciseId', async (request) => {
    const { workoutExerciseId } = request.params as { workoutExerciseId: string };
    const body = parseBody(
      z.object({
        position: z.number().int().nonnegative().optional(),
        notes: z.string().nullable().optional(),
        exerciseId: z.string().uuid().optional(),
      }),
      request.body,
    );
    const link = await updateWorkoutExercise(request.currentUser.id, workoutExerciseId, body);
    return { workout: serializeWorkout(await getWorkout(request.currentUser.id, link.workoutId)) };
  });

  app.delete('/exercises/:workoutExerciseId', async (request, reply) => {
    const { workoutExerciseId } = request.params as { workoutExerciseId: string };
    await removeWorkoutExercise(request.currentUser.id, workoutExerciseId);
    return reply.code(204).send();
  });

  // --- sets --------------------------------------------------------------
  app.post('/exercises/:workoutExerciseId/sets', async (request, reply) => {
    const { workoutExerciseId } = request.params as { workoutExerciseId: string };
    const body = parseBody(setBody, request.body);
    const set = await addSet(request.currentUser.id, workoutExerciseId, body);
    return reply.code(201).send({ set });
  });

  app.patch('/sets/:setId', async (request) => {
    const { setId } = request.params as { setId: string };
    const body = parseBody(setBody.extend({ setNumber: z.number().int().positive().optional() }), request.body);
    return { set: await updateSet(request.currentUser.id, setId, body) };
  });

  app.delete('/sets/:setId', async (request, reply) => {
    const { setId } = request.params as { setId: string };
    await deleteSet(request.currentUser.id, setId);
    return reply.code(204).send();
  });

  app.post('/exercises/:workoutExerciseId/renumber', async (request, reply) => {
    const { workoutExerciseId } = request.params as { workoutExerciseId: string };
    await renumberSets(request.currentUser.id, workoutExerciseId);
    return reply.code(204).send();
  });
}
