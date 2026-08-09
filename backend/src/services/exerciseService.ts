import { DataSource, ExerciseType, MuscleRole, Prisma } from '@prisma/client';
import { decideMatch, normalizeName, type ExerciseCandidate, type MatchDecision } from '../ai/exerciseMatcher';
import { badRequest, conflict, notFound } from '../lib/errors';
import { prisma } from '../lib/prisma';

export interface ExerciseInput {
  name: string;
  nameDe?: string | null;
  type?: ExerciseType;
  equipment?: string | null;
  notes?: string | null;
  muscleGroupKeys?: string[];
  source?: DataSource;
}

const exerciseInclude = {
  muscleGroups: { include: { muscleGroup: true } },
  aliases: true,
} satisfies Prisma.ExerciseInclude;

export type ExerciseWithRelations = Prisma.ExerciseGetPayload<{ include: typeof exerciseInclude }>;

/** Global (seeded) exercises plus the user's own ones. */
function visibilityFilter(userId: string): Prisma.ExerciseWhereInput {
  return { deletedAt: null, OR: [{ userId: null }, { userId }] };
}

export async function listExercises(
  userId: string,
  options: { search?: string; muscleGroupKey?: string; type?: ExerciseType; limit?: number } = {},
): Promise<ExerciseWithRelations[]> {
  const where: Prisma.ExerciseWhereInput = { ...visibilityFilter(userId) };

  if (options.search && options.search.trim()) {
    const term = options.search.trim();
    where.AND = [
      {
        OR: [
          { name: { contains: term, mode: 'insensitive' } },
          { nameDe: { contains: term, mode: 'insensitive' } },
          { aliases: { some: { alias: { contains: normalizeName(term) } } } },
        ],
      },
    ];
  }
  if (options.muscleGroupKey) {
    where.muscleGroups = { some: { muscleGroup: { key: options.muscleGroupKey } } };
  }
  if (options.type) where.type = options.type;

  return prisma().exercise.findMany({
    where,
    include: exerciseInclude,
    orderBy: [{ name: 'asc' }],
    take: options.limit ?? 500,
  });
}

export async function getExercise(userId: string, exerciseId: string): Promise<ExerciseWithRelations> {
  const exercise = await prisma().exercise.findFirst({
    where: { id: exerciseId, ...visibilityFilter(userId) },
    include: exerciseInclude,
  });
  if (!exercise) throw notFound('Übung');
  return exercise;
}

export async function getExerciseCandidates(userId: string): Promise<ExerciseCandidate[]> {
  const exercises = await prisma().exercise.findMany({
    where: visibilityFilter(userId),
    select: { id: true, name: true, nameDe: true, aliases: { select: { alias: true } } },
  });
  return exercises.map((e) => ({
    id: e.id,
    name: e.name,
    nameDe: e.nameDe,
    aliases: e.aliases.map((a) => a.alias),
  }));
}

async function resolveMuscleGroupIds(keys: string[]): Promise<string[]> {
  if (keys.length === 0) return [];
  const groups = await prisma().muscleGroup.findMany({ where: { key: { in: keys } }, select: { id: true } });
  return groups.map((g) => g.id);
}

export async function createExercise(userId: string, input: ExerciseInput): Promise<ExerciseWithRelations> {
  const name = input.name.trim();
  if (!name) throw badRequest('Der Name der Übung darf nicht leer sein.');

  const existing = await prisma().exercise.findFirst({
    where: { userId, name: { equals: name, mode: 'insensitive' }, deletedAt: null },
  });
  if (existing) throw conflict('Eine Übung mit diesem Namen existiert bereits.', { exerciseId: existing.id });

  const muscleGroupIds = await resolveMuscleGroupIds(input.muscleGroupKeys ?? []);
  const aliasValues = new Set<string>([normalizeName(name)]);
  if (input.nameDe) aliasValues.add(normalizeName(input.nameDe));

  return prisma().exercise.create({
    data: {
      userId,
      name,
      nameDe: input.nameDe ?? null,
      type: input.type ?? ExerciseType.STRENGTH,
      equipment: input.equipment ?? null,
      notes: input.notes ?? null,
      isCustom: true,
      source: input.source ?? DataSource.MANUAL,
      muscleGroups: {
        create: muscleGroupIds.map((id, index) => ({
          muscleGroupId: id,
          role: index === 0 ? MuscleRole.PRIMARY : MuscleRole.SECONDARY,
          contribution: index === 0 ? 1 : 0.5,
        })),
      },
      aliases: { create: [...aliasValues].filter(Boolean).map((alias) => ({ alias })) },
    },
    include: exerciseInclude,
  });
}

export async function updateExercise(
  userId: string,
  exerciseId: string,
  input: Partial<ExerciseInput>,
): Promise<ExerciseWithRelations> {
  const existing = await prisma().exercise.findFirst({ where: { id: exerciseId, deletedAt: null } });
  if (!existing) throw notFound('Übung');
  // Seeded global exercises are shared, so editing creates a personal copy instead.
  if (existing.userId !== userId) {
    return createExercise(userId, {
      name: input.name ?? existing.name,
      nameDe: input.nameDe ?? existing.nameDe,
      type: input.type ?? existing.type,
      equipment: input.equipment ?? existing.equipment,
      notes: input.notes ?? existing.notes,
      muscleGroupKeys: input.muscleGroupKeys,
    });
  }

  const data: Prisma.ExerciseUpdateInput = {};
  if (input.name !== undefined) data.name = input.name.trim();
  if (input.nameDe !== undefined) data.nameDe = input.nameDe;
  if (input.type !== undefined) data.type = input.type;
  if (input.equipment !== undefined) data.equipment = input.equipment;
  if (input.notes !== undefined) data.notes = input.notes;

  if (input.muscleGroupKeys) {
    const muscleGroupIds = await resolveMuscleGroupIds(input.muscleGroupKeys);
    await prisma().exerciseMuscleGroup.deleteMany({ where: { exerciseId } });
    data.muscleGroups = {
      create: muscleGroupIds.map((id, index) => ({
        muscleGroupId: id,
        role: index === 0 ? MuscleRole.PRIMARY : MuscleRole.SECONDARY,
        contribution: index === 0 ? 1 : 0.5,
      })),
    };
  }

  return prisma().exercise.update({ where: { id: exerciseId }, data, include: exerciseInclude });
}

export async function deleteExercise(userId: string, exerciseId: string): Promise<void> {
  const existing = await prisma().exercise.findFirst({ where: { id: exerciseId, userId, deletedAt: null } });
  if (!existing) throw notFound('Übung');
  await prisma().exercise.update({ where: { id: exerciseId }, data: { deletedAt: new Date() } });
}

export interface ResolvedExercise {
  exercise: ExerciseWithRelations;
  decision: MatchDecision['kind'];
  score: number;
  alternatives: Array<{ id: string; name: string; score: number }>;
  created: boolean;
}

/**
 * Maps a spoken exercise name onto the catalogue.
 *
 * `autoCreate` is used by the Telegram pipeline: when nothing matches closely
 * enough the exercise is created on the fly (marked as AI-created), so a
 * workout is never lost – the user can rename it in the app afterwards.
 */
export async function resolveExercise(
  userId: string,
  spokenName: string,
  options: { muscleGroupKeys?: string[]; type?: ExerciseType | null; autoCreate?: boolean; source?: DataSource } = {},
): Promise<ResolvedExercise | null> {
  const candidates = await getExerciseCandidates(userId);
  const decision = decideMatch(spokenName, candidates);
  const alternatives = decision.alternatives.map((a) => ({ id: a.candidate.id, name: a.candidate.name, score: a.score }));

  if ((decision.kind === 'accept' || decision.kind === 'suggest') && decision.match) {
    const exercise = await getExercise(userId, decision.match.candidate.id);
    return { exercise, decision: decision.kind, score: decision.match.score, alternatives, created: false };
  }

  if (!options.autoCreate) return null;

  const created = await createExercise(userId, {
    name: titleCase(spokenName),
    type: options.type ?? ExerciseType.STRENGTH,
    muscleGroupKeys: options.muscleGroupKeys ?? [],
    source: options.source ?? DataSource.TELEGRAM_VOICE,
  });
  return { exercise: created, decision: 'create', score: 0, alternatives, created: true };
}

function titleCase(value: string): string {
  return value
    .trim()
    .split(/\s+/)
    .map((word) => (word.length > 2 ? word[0].toUpperCase() + word.slice(1).toLowerCase() : word))
    .join(' ');
}

export async function listMuscleGroups() {
  return prisma().muscleGroup.findMany({ orderBy: { sortOrder: 'asc' } });
}
