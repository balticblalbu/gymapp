import type { FastifyInstance } from 'fastify';
import { ExerciseType } from '@prisma/client';
import { z } from 'zod';
import { PERIODS, type Period } from '../lib/dates';
import {
  createExercise,
  deleteExercise,
  getExercise,
  listExercises,
  listMuscleGroups,
  updateExercise,
  type ExerciseWithRelations,
} from '../services/exerciseService';
import { getExerciseStats } from '../services/statsService';
import { getRecordsForExercise } from '../services/recordService';
import { parseBody } from './auth';

const exerciseBody = z.object({
  name: z.string().min(1),
  nameDe: z.string().nullable().optional(),
  type: z.nativeEnum(ExerciseType).optional(),
  equipment: z.string().nullable().optional(),
  notes: z.string().nullable().optional(),
  muscleGroupKeys: z.array(z.string()).optional(),
});

export function serializeExercise(exercise: ExerciseWithRelations) {
  return {
    id: exercise.id,
    name: exercise.name,
    nameDe: exercise.nameDe,
    type: exercise.type,
    equipment: exercise.equipment,
    notes: exercise.notes,
    isCustom: exercise.isCustom,
    isGlobal: exercise.userId === null,
    muscleGroups: exercise.muscleGroups.map((mg) => ({
      key: mg.muscleGroup.key,
      nameDe: mg.muscleGroup.nameDe,
      nameEn: mg.muscleGroup.nameEn,
      role: mg.role,
      contribution: mg.contribution,
    })),
    aliases: exercise.aliases.map((a) => a.alias),
    createdAt: exercise.createdAt,
    updatedAt: exercise.updatedAt,
  };
}

export default async function exerciseRoutes(app: FastifyInstance) {
  app.addHook('preHandler', app.authenticate);

  app.get('/', async (request) => {
    const query = request.query as { search?: string; muscleGroup?: string; type?: ExerciseType; limit?: string };
    const exercises = await listExercises(request.currentUser.id, {
      search: query.search,
      muscleGroupKey: query.muscleGroup,
      type: query.type,
      limit: query.limit ? Number(query.limit) : undefined,
    });
    return { exercises: exercises.map(serializeExercise) };
  });

  app.post('/', async (request, reply) => {
    const body = parseBody(exerciseBody, request.body);
    const exercise = await createExercise(request.currentUser.id, body);
    return reply.code(201).send({ exercise: serializeExercise(exercise) });
  });

  app.get('/:id', async (request) => {
    const { id } = request.params as { id: string };
    return { exercise: serializeExercise(await getExercise(request.currentUser.id, id)) };
  });

  app.patch('/:id', async (request) => {
    const { id } = request.params as { id: string };
    const body = parseBody(exerciseBody.partial(), request.body);
    const exercise = await updateExercise(request.currentUser.id, id, body);
    return { exercise: serializeExercise(exercise) };
  });

  app.delete('/:id', async (request, reply) => {
    const { id } = request.params as { id: string };
    await deleteExercise(request.currentUser.id, id);
    return reply.code(204).send();
  });

  app.get('/:id/stats', async (request) => {
    const { id } = request.params as { id: string };
    const query = request.query as { period?: string };
    const period: Period = (PERIODS as readonly string[]).includes(query.period ?? '') ? (query.period as Period) : '90d';

    const [stats, records] = await Promise.all([
      getExerciseStats(request.currentUser.id, id, period, request.currentUser.timezone),
      getRecordsForExercise(request.currentUser.id, id),
    ]);

    return {
      stats,
      records: records.map((r) => ({
        type: r.type,
        value: r.value,
        previousValue: r.previousValue,
        weightKg: r.weightKg,
        reps: r.reps,
        achievedAt: r.achievedAt,
      })),
    };
  });
}

export async function muscleGroupRoutes(app: FastifyInstance) {
  app.addHook('preHandler', app.authenticate);
  app.get('/', async () => ({ muscleGroups: await listMuscleGroups() }));
}
