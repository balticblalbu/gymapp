import type { FastifyInstance } from 'fastify';
import { estimateOneRepMax, setVolume } from '../domain/calculations';
import { utcToCivilDate } from '../lib/dates';
import { prisma } from '../lib/prisma';
import { loadSetRows } from '../services/statsService';

/** Escapes a CSV field (RFC 4180). */
function csvField(value: unknown): string {
  if (value === null || value === undefined) return '';
  const text = String(value);
  return /[",\n;]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export default async function exportRoutes(app: FastifyInstance) {
  app.addHook('preHandler', app.authenticate);

  app.get('/', async (request, reply) => {
    const query = request.query as { format?: string };
    const format = query.format === 'csv' ? 'csv' : 'json';
    const userId = request.currentUser.id;
    const stamp = new Date().toISOString().slice(0, 10);

    if (format === 'csv') {
      const rows = await loadSetRows(userId);
      const header = [
        'date',
        'exercise',
        'exercise_type',
        'muscle_groups',
        'set_weight_kg',
        'reps',
        'duration_sec',
        'distance_m',
        'volume_kg',
        'estimated_1rm_kg',
        'is_warmup',
      ];

      const lines = [header.join(',')];
      for (const row of rows) {
        lines.push(
          [
            utcToCivilDate(row.date),
            row.exerciseName,
            row.exerciseType,
            row.muscleGroups.map((mg) => mg.key).join('|'),
            row.weightKg ?? '',
            row.reps ?? '',
            row.durationSec ?? '',
            row.distanceM ?? '',
            setVolume(row) || '',
            row.weightKg && row.reps ? estimateOneRepMax(row.weightKg, row.reps) : '',
            row.isWarmup ? 'true' : 'false',
          ]
            .map(csvField)
            .join(','),
        );
      }

      return reply
        .header('Content-Type', 'text/csv; charset=utf-8')
        .header('Content-Disposition', `attachment; filename="workouts-${stamp}.csv"`)
        .send(lines.join('\n'));
    }

    const [workouts, exercises, records] = await Promise.all([
      prisma().workout.findMany({
        where: { userId, deletedAt: null },
        include: {
          exercises: {
            where: { deletedAt: null },
            orderBy: { position: 'asc' },
            include: {
              exercise: { select: { name: true, nameDe: true, type: true } },
              sets: { where: { deletedAt: null }, orderBy: { setNumber: 'asc' } },
            },
          },
        },
        orderBy: { date: 'asc' },
      }),
      prisma().exercise.findMany({
        where: { deletedAt: null, OR: [{ userId: null }, { userId }] },
        include: { muscleGroups: { include: { muscleGroup: { select: { key: true } } } } },
      }),
      prisma().personalRecord.findMany({ where: { userId }, include: { exercise: { select: { name: true } } } }),
    ]);

    const payload = {
      exportedAt: new Date().toISOString(),
      schemaVersion: 1,
      user: { name: request.currentUser.name, unitSystem: request.currentUser.unitSystem, timezone: request.currentUser.timezone },
      exercises: exercises.map((exercise) => ({
        id: exercise.id,
        name: exercise.name,
        nameDe: exercise.nameDe,
        type: exercise.type,
        muscleGroups: exercise.muscleGroups.map((mg) => mg.muscleGroup.key),
      })),
      workouts: workouts.map((workout) => ({
        id: workout.id,
        date: utcToCivilDate(workout.date),
        title: workout.title,
        notes: workout.notes,
        durationSec: workout.durationSec,
        source: workout.source,
        exercises: workout.exercises.map((link) => ({
          exerciseId: link.exerciseId,
          name: link.exercise.name,
          type: link.exercise.type,
          notes: link.notes,
          sets: link.sets.map((set) => ({
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
          })),
        })),
      })),
      personalRecords: records.map((record) => ({
        exercise: record.exercise.name,
        type: record.type,
        value: record.value,
        previousValue: record.previousValue,
        achievedAt: utcToCivilDate(record.achievedAt),
      })),
    };

    return reply
      .header('Content-Type', 'application/json; charset=utf-8')
      .header('Content-Disposition', `attachment; filename="workouts-${stamp}.json"`)
      .send(payload);
  });
}
