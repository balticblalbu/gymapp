import { AiIntent, AiResultStatus, DataSource, ExerciseType, type User } from '@prisma/client';
import { getConfig } from '../config/env';
import { setVolume, summarizeSets } from '../domain/calculations';
import { todayInZone, utcToCivilDate } from '../lib/dates';
import { log } from '../lib/logger';
import { prisma } from '../lib/prisma';
import { round } from '../lib/units';
import { getExerciseCandidates, resolveExercise } from '../services/exerciseService';
import { recomputeRecordsForExercise, type RecordDelta } from '../services/recordService';
import { addExerciseToWorkout, getOrCreateWorkoutForDate } from '../services/workoutService';
import {
  loadContext,
  saveContext,
  summarizeEntries,
  summarizeHistory,
  type ConversationSnapshot,
  type LoggedEntry,
} from '../services/conversationService';
import { resolveDateExpression } from './dateResolver';
import { getWorkoutParser } from './llm';
import type { ParsedExercise, ParsedMessage, ParseContext } from './types';

const logger = log('ai:pipeline');

// ---------------------------------------------------------------------------
// Result types
// ---------------------------------------------------------------------------

export interface SavedExerciseSummary {
  exerciseName: string;
  summary: string;
  volumeKg: number;
  sets: number;
  created: boolean;
  matchScore: number;
  /** Volume change compared to the previous session of the same exercise. */
  progressPercent: number | null;
}

export type PipelineResult =
  | {
      kind: 'saved';
      isoDate: string;
      exercises: SavedExerciseSummary[];
      records: Array<RecordDelta & { exerciseName: string }>;
      totalVolumeKg: number;
      aiResultId: string;
    }
  | {
      kind: 'confirm';
      aiResultId: string;
      isoDate: string;
      preview: Array<{ exerciseName: string; summary: string; matchScore: number; willCreate: boolean }>;
      confidence: number;
    }
  | { kind: 'clarify'; question: string; aiResultId: string }
  | { kind: 'exercise_created'; name: string; muscleGroups: string[]; exerciseId: string }
  | { kind: 'corrected'; description: string; records: Array<RecordDelta & { exerciseName: string }> }
  | { kind: 'query'; text: string }
  | { kind: 'nothing'; message: string };

export interface PipelineInput {
  user: User;
  chatId: bigint;
  text: string;
  source: DataSource;
  telegramMessageId?: string | null;
  /** Ask for confirmation even when the model is confident. */
  forceConfirmation?: boolean;
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

export async function processMessage(input: PipelineInput): Promise<PipelineResult> {
  const config = getConfig();
  const context = await loadContext(input.user.id, input.chatId);
  const parseContext = await buildParseContext(input.user, context);

  const parser = getWorkoutParser();
  let parsed = await parser.parse(input.text, parseContext);

  // A short answer like "drei mal zehn" only makes sense together with the
  // exercise from the previous question.
  if (context.awaitingClarification) {
    parsed = mergeClarification(context.awaitingClarification.parsed, parsed);
  }

  const dateResolution = resolveDateExpression(parsed.dateExpression, { timezone: input.user.timezone });
  const resolvedDate = dateResolution.date ?? todayInZone(input.user.timezone);

  const aiResult = await prisma().aiParsingResult.create({
    data: {
      telegramMessageId: input.telegramMessageId ?? null,
      userId: input.user.id,
      provider: parsed.provider,
      model: parsed.model,
      intent: toDbIntent(parsed.intent),
      status: AiResultStatus.PENDING_CONFIRMATION,
      inputText: input.text,
      rawResponse: (parsed.raw ?? null) as never,
      structured: JSON.parse(JSON.stringify(parsed)) as never,
      confidence: parsed.confidence,
      resolvedDate,
      clarification: parsed.clarificationQuestion ?? null,
      latencyMs: parsed.latencyMs ?? null,
    },
  });

  const snapshot: ConversationSnapshot = {
    ...context,
    history: [...context.history, { role: 'user', text: input.text }],
    awaitingClarification: null,
  };

  try {
    const result = await route({ input, parsed, aiResult, resolvedDate, dateResolution, snapshot, config });
    await saveContext(input.user.id, input.chatId, snapshot);
    return result;
  } catch (error) {
    logger.error({ err: (error as Error).message, aiResultId: aiResult.id }, 'Pipeline failed');
    await prisma().aiParsingResult.update({
      where: { id: aiResult.id },
      data: { status: AiResultStatus.FAILED, error: (error as Error).message },
    });
    throw error;
  }
}

interface RouteArgs {
  input: PipelineInput;
  parsed: ParsedMessage;
  aiResult: { id: string };
  resolvedDate: Date;
  dateResolution: ReturnType<typeof resolveDateExpression>;
  snapshot: ConversationSnapshot;
  config: ReturnType<typeof getConfig>;
}

async function route(args: RouteArgs): Promise<PipelineResult> {
  const { parsed, input } = args;

  if (parsed.intent === 'create_exercise' && parsed.newExercises.length > 0 && parsed.exercises.length === 0) {
    return createExerciseOnly(args);
  }

  if (parsed.intent === 'correction' && parsed.corrections.length > 0) {
    return applyCorrection(args);
  }

  if (parsed.exercises.length === 0) {
    await setStatus(args.aiResult.id, AiResultStatus.NEEDS_CLARIFICATION);
    if (parsed.clarificationQuestion) {
      return { kind: 'clarify', question: parsed.clarificationQuestion, aiResultId: args.aiResult.id };
    }
    if (parsed.intent === 'query') {
      return { kind: 'query', text: input.text };
    }
    return {
      kind: 'nothing',
      message:
        'Ich konnte darin kein Training erkennen. Sag z. B.: „Heute Bankdrücken 100 Kilo, 3 Sätze mit 10 Wiederholungen."',
    };
  }

  // The date itself is unclear ("letzte Woche beim Training") -> ask.
  if (args.dateResolution.ambiguous && args.dateResolution.reason === 'ambiguous_range') {
    await setStatus(args.aiResult.id, AiResultStatus.NEEDS_CLARIFICATION);
    await stashClarification(args, 'Auf welchen Tag genau bezieht sich das? (z. B. „Montag" oder „5. August")');
    return {
      kind: 'clarify',
      question: 'Auf welchen Tag genau bezieht sich das? (z. B. „Montag" oder „5. August")',
      aiResultId: args.aiResult.id,
    };
  }

  const completeness = assessCompleteness(parsed.exercises);
  const confidence = Math.min(parsed.confidence, completeness.confidence);

  // Missing sets/reps -> ask instead of storing half data.
  if (!completeness.complete && confidence < args.config.AI_CONFIRM_THRESHOLD) {
    const question =
      parsed.clarificationQuestion ??
      `${completeness.firstIncomplete ?? 'Übung'} erkannt. Wie viele Sätze und Wiederholungen waren das?`;
    await setStatus(args.aiResult.id, AiResultStatus.NEEDS_CLARIFICATION);
    await stashClarification(args, question);
    return { kind: 'clarify', question, aiResultId: args.aiResult.id };
  }

  if (args.input.forceConfirmation || confidence < args.config.AI_AUTOSAVE_THRESHOLD) {
    return buildConfirmation(args, confidence);
  }

  return persist(args);
}

async function setStatus(aiResultId: string, status: AiResultStatus, error?: string): Promise<void> {
  await prisma().aiParsingResult.update({ where: { id: aiResultId }, data: { status, ...(error ? { error } : {}) } });
}

async function stashClarification(args: RouteArgs, question: string): Promise<void> {
  args.snapshot.awaitingClarification = { aiResultId: args.aiResult.id, parsed: args.parsed, question };
  args.snapshot.history.push({ role: 'bot', text: question });
}

// ---------------------------------------------------------------------------
// Confirmation flow
// ---------------------------------------------------------------------------

async function buildConfirmation(args: RouteArgs, confidence: number): Promise<PipelineResult> {
  const candidates = await getExerciseCandidates(args.input.user.id);
  const preview: Array<{ exerciseName: string; summary: string; matchScore: number; willCreate: boolean }> = [];

  for (const exercise of args.parsed.exercises) {
    const match = await resolveExercise(args.input.user.id, exercise.name, { autoCreate: false });
    preview.push({
      exerciseName: match?.exercise.name ?? exercise.name,
      summary: describeParsedSets(exercise),
      matchScore: match?.score ?? 0,
      willCreate: !match,
    });
  }
  void candidates;

  args.snapshot.pending = {
    aiResultId: args.aiResult.id,
    parsed: args.parsed,
    isoDate: utcToCivilDate(args.resolvedDate),
    transcript: args.input.text,
  };
  await setStatus(args.aiResult.id, AiResultStatus.PENDING_CONFIRMATION);

  return {
    kind: 'confirm',
    aiResultId: args.aiResult.id,
    isoDate: utcToCivilDate(args.resolvedDate),
    preview,
    confidence: round(confidence, 2),
  };
}

/** Called when the user taps [Speichern] on a pending proposal. */
export async function confirmPending(user: User, chatId: bigint, aiResultId: string): Promise<PipelineResult> {
  const context = await loadContext(user.id, chatId);
  const pending = context.pending;
  if (!pending || pending.aiResultId !== aiResultId) {
    const stored = await prisma().aiParsingResult.findFirst({ where: { id: aiResultId, userId: user.id } });
    if (!stored || !stored.structured) {
      return { kind: 'nothing', message: 'Dieser Vorschlag ist nicht mehr verfügbar. Bitte schick die Nachricht erneut.' };
    }
    const parsed = stored.structured as unknown as ParsedMessage;
    return persist({
      input: { user, chatId, text: stored.inputText, source: DataSource.TELEGRAM_TEXT },
      parsed,
      aiResult: { id: stored.id },
      resolvedDate: stored.resolvedDate ?? todayInZone(user.timezone),
      dateResolution: resolveDateExpression(parsed.dateExpression, { timezone: user.timezone }),
      snapshot: context,
      config: getConfig(),
    });
  }

  const snapshot: ConversationSnapshot = { ...context, pending: null };
  const result = await persist({
    input: { user, chatId, text: pending.transcript, source: DataSource.TELEGRAM_TEXT },
    parsed: pending.parsed,
    aiResult: { id: pending.aiResultId },
    resolvedDate: new Date(`${pending.isoDate}T00:00:00.000Z`),
    dateResolution: resolveDateExpression(pending.parsed.dateExpression, { timezone: user.timezone }),
    snapshot,
    config: getConfig(),
  });
  await saveContext(user.id, chatId, snapshot);
  return result;
}

export async function cancelPending(user: User, chatId: bigint, aiResultId: string): Promise<void> {
  const context = await loadContext(user.id, chatId);
  await saveContext(user.id, chatId, { ...context, pending: null, awaitingClarification: null });
  await prisma()
    .aiParsingResult.update({ where: { id: aiResultId }, data: { status: AiResultStatus.REJECTED } })
    .catch(() => undefined);
}

// ---------------------------------------------------------------------------
// Persisting
// ---------------------------------------------------------------------------

async function persist(args: RouteArgs): Promise<PipelineResult> {
  const { input, parsed, resolvedDate } = args;
  const workout = await getOrCreateWorkoutForDate(input.user.id, resolvedDate, input.source);

  const summaries: SavedExerciseSummary[] = [];
  const records: Array<RecordDelta & { exerciseName: string }> = [];
  const entries: LoggedEntry[] = [];
  let totalVolume = 0;

  for (const exercise of parsed.exercises) {
    if (exercise.sets.length === 0) continue;

    const resolved = await resolveExercise(input.user.id, exercise.name, {
      autoCreate: true,
      muscleGroupKeys: exercise.muscleGroups ?? [],
      type: exercise.type ?? inferType(exercise),
      source: input.source,
    });
    if (!resolved) continue;

    const previousVolume = await previousSessionVolume(input.user.id, resolved.exercise.id, resolvedDate);
    const link = await addExerciseToWorkout(input.user.id, workout.id, resolved.exercise.id);

    const lastSet = await prisma().workoutSet.findFirst({
      where: { workoutExerciseId: link.id, deletedAt: null },
      orderBy: { setNumber: 'desc' },
      select: { setNumber: true },
    });
    let setNumber = lastSet?.setNumber ?? 0;

    const createdIds: string[] = [];
    for (const set of exercise.sets) {
      setNumber += 1;
      const created = await prisma().workoutSet.create({
        data: {
          workoutExerciseId: link.id,
          setNumber,
          weightKg: set.weightKg ?? null,
          reps: set.reps ?? null,
          durationSec: set.durationSec ?? null,
          distanceM: set.distanceM ?? null,
          rpe: set.rpe ?? null,
          isWarmup: set.isWarmup ?? false,
          source: input.source,
          confidence: exercise.confidence,
          aiParsingResultId: args.aiResult.id,
        },
      });
      createdIds.push(created.id);
    }

    const volume = exercise.sets.reduce((sum, set) => sum + setVolume(set), 0);
    totalVolume += volume;

    const deltas = await recomputeRecordsForExercise(input.user.id, resolved.exercise.id);
    records.push(...deltas.map((d) => ({ ...d, exerciseName: resolved.exercise.name })));

    summaries.push({
      exerciseName: resolved.exercise.name,
      summary: describeParsedSets(exercise),
      volumeKg: round(volume, 1),
      sets: exercise.sets.length,
      created: resolved.created,
      matchScore: resolved.score,
      progressPercent: previousVolume > 0 ? round(((volume - previousVolume) / previousVolume) * 100, 1) : null,
    });

    entries.push({
      workoutId: workout.id,
      workoutExerciseId: link.id,
      exerciseId: resolved.exercise.id,
      exerciseName: resolved.exercise.name,
      date: utcToCivilDate(resolvedDate),
      setIds: createdIds,
    });
  }

  if (summaries.length === 0) {
    await setStatus(args.aiResult.id, AiResultStatus.REJECTED);
    return { kind: 'nothing', message: 'Es gab nichts zu speichern.' };
  }

  await setStatus(args.aiResult.id, AiResultStatus.APPLIED);
  args.snapshot.lastEntries = [...(args.snapshot.lastEntries ?? []), ...entries];
  args.snapshot.pending = null;
  args.snapshot.history.push({ role: 'bot', text: summaries.map((s) => `${s.exerciseName}: ${s.summary}`).join('; ') });

  return {
    kind: 'saved',
    isoDate: utcToCivilDate(resolvedDate),
    exercises: summaries,
    records,
    totalVolumeKg: round(totalVolume, 1),
    aiResultId: args.aiResult.id,
  };
}

async function previousSessionVolume(userId: string, exerciseId: string, beforeDate: Date): Promise<number> {
  const previous = await prisma().workoutSet.findMany({
    where: {
      deletedAt: null,
      workoutExercise: { exerciseId, deletedAt: null, workout: { userId, deletedAt: null, date: { lt: beforeDate } } },
    },
    select: { weightKg: true, reps: true, workoutExercise: { select: { workout: { select: { date: true } } } } },
    orderBy: { workoutExercise: { workout: { date: 'desc' } } },
    take: 60,
  });
  if (previous.length === 0) return 0;

  const latestDate = previous[0].workoutExercise.workout.date.getTime();
  return previous
    .filter((s) => s.workoutExercise.workout.date.getTime() === latestDate)
    .reduce((sum, s) => sum + setVolume({ weightKg: s.weightKg, reps: s.reps }), 0);
}

// ---------------------------------------------------------------------------
// Exercise creation / corrections
// ---------------------------------------------------------------------------

async function createExerciseOnly(args: RouteArgs): Promise<PipelineResult> {
  const definition = args.parsed.newExercises[0];
  const resolved = await resolveExercise(args.input.user.id, definition.name, {
    autoCreate: true,
    muscleGroupKeys: definition.muscleGroups,
    type: definition.type ?? ExerciseType.STRENGTH,
    source: args.input.source,
  });

  await setStatus(args.aiResult.id, AiResultStatus.APPLIED);
  return {
    kind: 'exercise_created',
    name: resolved?.exercise.name ?? definition.name,
    muscleGroups: definition.muscleGroups,
    exerciseId: resolved?.exercise.id ?? '',
  };
}

/**
 * Applies a correction to the most recently stored data.
 * Ambiguous corrections are rejected with a question instead of guessing.
 */
async function applyCorrection(args: RouteArgs): Promise<PipelineResult> {
  const entries = args.snapshot.lastEntries ?? [];
  if (entries.length === 0) {
    await setStatus(args.aiResult.id, AiResultStatus.NEEDS_CLARIFICATION);
    return {
      kind: 'clarify',
      question: 'Ich weiß nicht, auf welchen Eintrag sich das bezieht. Was genau soll ich ändern?',
      aiResultId: args.aiResult.id,
    };
  }

  const change = args.parsed.corrections[0];
  const target = change.exerciseName
    ? entries.filter((e) => e.exerciseName.toLowerCase().includes(change.exerciseName!.toLowerCase())).slice(-1)[0] ??
      entries[entries.length - 1]
    : entries[entries.length - 1];

  const sets = await prisma().workoutSet.findMany({
    where: { id: { in: target.setIds }, deletedAt: null },
    orderBy: { setNumber: 'asc' },
  });
  if (sets.length === 0) {
    await setStatus(args.aiResult.id, AiResultStatus.NEEDS_CLARIFICATION);
    return { kind: 'clarify', question: 'Der zugehörige Eintrag existiert nicht mehr.', aiResultId: args.aiResult.id };
  }

  let affected = sets;
  if (change.matchPreviousNumber != null && change.field === 'weight') {
    const matching = sets.filter((s) => s.weightKg != null && Math.abs(s.weightKg - change.matchPreviousNumber!) < 0.01);
    if (matching.length > 0) affected = matching;
  } else if (change.setIndex != null) {
    const byIndex = sets.filter((s) => s.setNumber === change.setIndex);
    if (byIndex.length > 0) affected = byIndex;
  } else if (change.scope === 'last_set') {
    affected = [sets[sets.length - 1]];
  }

  if (change.valueNumber == null && change.valueText == null) {
    await setStatus(args.aiResult.id, AiResultStatus.NEEDS_CLARIFICATION);
    return { kind: 'clarify', question: 'Auf welchen Wert soll ich das ändern?', aiResultId: args.aiResult.id };
  }

  const data: Record<string, number | null> = {};
  switch (change.field) {
    case 'weight':
      data.weightKg = change.valueNumber ?? null;
      break;
    case 'reps':
      data.reps = change.valueNumber != null ? Math.round(change.valueNumber) : null;
      break;
    case 'duration':
      data.durationSec = change.valueNumber != null ? Math.round(change.valueNumber) : null;
      break;
    case 'distance':
      data.distanceM = change.valueNumber != null ? Math.round(change.valueNumber) : null;
      break;
    default:
      await setStatus(args.aiResult.id, AiResultStatus.NEEDS_CLARIFICATION);
      return {
        kind: 'clarify',
        question: 'Diese Korrektur kann ich per Telegram nicht anwenden – bitte ändere sie in der App.',
        aiResultId: args.aiResult.id,
      };
  }

  await prisma().workoutSet.updateMany({
    where: { id: { in: affected.map((s) => s.id) } },
    data: { ...data, source: DataSource.TELEGRAM_TEXT },
  });

  const deltas = await recomputeRecordsForExercise(args.input.user.id, target.exerciseId);
  await setStatus(args.aiResult.id, AiResultStatus.APPLIED);

  const fieldLabel = { weight: 'Gewicht', reps: 'Wiederholungen', duration: 'Dauer', distance: 'Distanz' }[
    change.field as 'weight' | 'reps' | 'duration' | 'distance'
  ];
  const unit = change.field === 'weight' ? ' kg' : '';

  return {
    kind: 'corrected',
    description: `${target.exerciseName}: ${fieldLabel} auf ${change.valueNumber}${unit} geändert (${affected.length} ${
      affected.length === 1 ? 'Satz' : 'Sätze'
    }).`,
    records: deltas.map((d) => ({ ...d, exerciseName: target.exerciseName })),
  };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function buildParseContext(user: User, snapshot: ConversationSnapshot): Promise<ParseContext> {
  const candidates = await getExerciseCandidates(user.id);
  const names = new Set<string>();
  for (const candidate of candidates) {
    names.add(candidate.name);
    if (candidate.nameDe) names.add(candidate.nameDe);
    for (const alias of candidate.aliases ?? []) names.add(alias);
  }

  return {
    todayIso: utcToCivilDate(todayInZone(user.timezone)),
    timezone: user.timezone,
    locale: user.locale,
    knownExercises: [...names],
    conversationSummary: summarizeHistory(snapshot),
    recentEntriesSummary: summarizeEntries(snapshot),
  };
}

/** Fills a follow-up answer with the exercise context of the pending question. */
export function mergeClarification(pending: ParsedMessage, incoming: ParsedMessage): ParsedMessage {
  if (incoming.exercises.length === 0 && pending.exercises.length === 0) return incoming;

  // The answer contains complete exercises on its own – trust it.
  if (incoming.exercises.length > 0 && incoming.exercises.every((e) => e.name && e.sets.some((s) => s.reps != null || s.durationSec != null))) {
    const named = incoming.exercises.map((exercise, index) => ({
      ...exercise,
      name: exercise.name || pending.exercises[index]?.name || pending.exercises[0]?.name || exercise.name,
    }));
    return { ...incoming, intent: 'log_workout', exercises: named, confidence: Math.max(incoming.confidence, 0.8) };
  }

  // The answer only carries numbers ("drei mal zehn") -> apply them to the
  // exercise we asked about, keeping its weight.
  const base = pending.exercises[0];
  if (!base) return incoming;

  const answerSets = incoming.exercises[0]?.sets ?? [];
  if (answerSets.length === 0) return incoming;

  const baseWeight = base.sets.find((s) => s.weightKg != null)?.weightKg ?? null;
  const merged: ParsedExercise = {
    ...base,
    sets: answerSets.map((set) => ({ ...set, weightKg: set.weightKg ?? baseWeight })),
    confidence: Math.max(0.8, incoming.confidence),
  };

  return {
    ...incoming,
    intent: 'log_workout',
    dateExpression: incoming.dateExpression ?? pending.dateExpression,
    exercises: [merged],
    confidence: Math.max(0.8, incoming.confidence),
  };
}

function assessCompleteness(exercises: ParsedExercise[]): {
  complete: boolean;
  confidence: number;
  firstIncomplete: string | null;
} {
  let complete = true;
  let firstIncomplete: string | null = null;
  let confidence = 1;

  for (const exercise of exercises) {
    for (const set of exercise.sets) {
      const hasLoad = (set.weightKg ?? 0) > 0;
      const hasReps = (set.reps ?? 0) > 0;
      const hasTime = (set.durationSec ?? 0) > 0;
      const hasDistance = (set.distanceM ?? 0) > 0;

      if (!hasReps && !hasTime && !hasDistance) {
        complete = false;
        firstIncomplete = firstIncomplete ?? exercise.name;
        confidence = Math.min(confidence, 0.35);
      } else if (!hasLoad && !hasTime && !hasDistance) {
        confidence = Math.min(confidence, 0.7);
      }
    }
    confidence = Math.min(confidence, exercise.confidence);
  }

  return { complete, confidence, firstIncomplete };
}

function inferType(exercise: ParsedExercise): ExerciseType {
  const hasWeight = exercise.sets.some((s) => (s.weightKg ?? 0) > 0);
  const hasDistance = exercise.sets.some((s) => (s.distanceM ?? 0) > 0);
  const hasDuration = exercise.sets.some((s) => (s.durationSec ?? 0) > 0);
  const hasReps = exercise.sets.some((s) => (s.reps ?? 0) > 0);

  if (hasWeight) return ExerciseType.STRENGTH;
  if (hasDistance) return ExerciseType.CARDIO;
  if (hasDuration && !hasReps) return ExerciseType.DURATION;
  if (hasReps) return ExerciseType.BODYWEIGHT;
  return ExerciseType.STRENGTH;
}

export function describeParsedSets(exercise: ParsedExercise): string {
  const summary = summarizeSets(exercise.sets);
  void summary;

  const groups: Array<{ label: string; count: number }> = [];
  for (const set of exercise.sets) {
    const label = describeParsedSet(set);
    const last = groups[groups.length - 1];
    if (last && last.label === label) last.count += 1;
    else groups.push({ label, count: 1 });
  }
  return groups.map((g) => (g.count > 1 ? `${g.label} × ${g.count}` : g.label)).join(', ');
}

function describeParsedSet(set: { weightKg?: number | null; reps?: number | null; durationSec?: number | null; distanceM?: number | null }): string {
  const parts: string[] = [];
  if (set.weightKg != null && set.weightKg > 0) parts.push(`${round(set.weightKg, 2)} kg`);
  if (set.reps != null && set.reps > 0) parts.push(`${set.reps}`);
  if (parts.length > 0) return parts.join(' × ');
  if (set.durationSec != null && set.durationSec > 0) {
    const minutes = Math.round(set.durationSec / 60);
    return minutes >= 1 ? `${minutes} min` : `${set.durationSec} s`;
  }
  if (set.distanceM != null && set.distanceM > 0) return `${round(set.distanceM / 1000, 2)} km`;
  return '?';
}

function toDbIntent(intent: ParsedMessage['intent']): AiIntent {
  switch (intent) {
    case 'log_workout':
      return AiIntent.LOG_WORKOUT;
    case 'create_exercise':
      return AiIntent.CREATE_EXERCISE;
    case 'correction':
      return AiIntent.CORRECTION;
    case 'query':
      return AiIntent.QUERY;
    case 'clarification_answer':
      return AiIntent.CLARIFICATION_ANSWER;
    case 'small_talk':
      return AiIntent.SMALL_TALK;
    default:
      return AiIntent.UNKNOWN;
  }
}
