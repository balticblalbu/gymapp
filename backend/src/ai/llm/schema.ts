import { ExerciseType } from '@prisma/client';
import { z } from 'zod';
import { lbToKg, kmToMeters, milesToMeters, roundWeight } from '../../lib/units';
import type { CorrectionChange, ParsedExercise, ParsedMessage, ParsedSet } from '../types';

/**
 * Wire format between LLM and application.
 *
 * The JSON schema below is handed to the provider (OpenAI structured outputs,
 * `strict: true`), the zod schema validates whatever actually comes back.
 * Never trust the model – every field is re-validated and re-normalised.
 */

export const llmSetSchema = z.object({
  weight: z.number().nullable(),
  weight_unit: z.enum(['kg', 'lb']).nullable(),
  reps: z.number().int().nullable(),
  duration_seconds: z.number().nullable(),
  distance: z.number().nullable(),
  distance_unit: z.enum(['m', 'km', 'mi']).nullable(),
  rpe: z.number().nullable(),
  is_warmup: z.boolean(),
});

export const llmExerciseSchema = z.object({
  name: z.string(),
  exercise_type: z.enum(['strength', 'bodyweight', 'cardio', 'duration', 'unknown']),
  muscle_groups: z.array(z.string()),
  sets: z.array(llmSetSchema),
  notes: z.string().nullable(),
  confidence: z.number(),
});

export const llmCorrectionSchema = z.object({
  scope: z.enum(['last_set', 'last_exercise', 'last_workout', 'specific']),
  exercise_name: z.string().nullable(),
  set_index: z.number().int().nullable(),
  field: z.enum(['weight', 'reps', 'duration', 'distance', 'date', 'muscle_group', 'notes', 'exercise']),
  value_number: z.number().nullable(),
  value_text: z.string().nullable(),
  match_previous_number: z.number().nullable(),
});

export const llmResponseSchema = z.object({
  intent: z.enum(['log_workout', 'create_exercise', 'correction', 'query', 'clarification_answer', 'small_talk', 'unknown']),
  language: z.string(),
  date_expression: z.string().nullable(),
  exercises: z.array(llmExerciseSchema),
  new_exercises: z.array(
    z.object({
      name: z.string(),
      muscle_groups: z.array(z.string()),
      exercise_type: z.enum(['strength', 'bodyweight', 'cardio', 'duration', 'unknown']),
    }),
  ),
  corrections: z.array(llmCorrectionSchema),
  clarification_question: z.string().nullable(),
  confidence: z.number(),
});

export type LlmResponse = z.infer<typeof llmResponseSchema>;

/** JSON schema mirror of the zod schema for provider side structured output. */
export const LLM_JSON_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['intent', 'language', 'date_expression', 'exercises', 'new_exercises', 'corrections', 'clarification_question', 'confidence'],
  properties: {
    intent: {
      type: 'string',
      enum: ['log_workout', 'create_exercise', 'correction', 'query', 'clarification_answer', 'small_talk', 'unknown'],
      description: 'What the user wants to do.',
    },
    language: { type: 'string', description: 'ISO 639-1 code of the message language, e.g. de or en.' },
    date_expression: {
      type: ['string', 'null'],
      description: 'Verbatim date expression from the message ("heute", "letzten Freitag", "am 5. August"). null when no date was mentioned. NEVER compute a date yourself.',
    },
    exercises: {
      type: 'array',
      description: 'Exercises that were performed. One entry per exercise, one array item per performed set.',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['name', 'exercise_type', 'muscle_groups', 'sets', 'notes', 'confidence'],
        properties: {
          name: { type: 'string', description: 'Exercise name exactly as spoken by the user.' },
          exercise_type: { type: 'string', enum: ['strength', 'bodyweight', 'cardio', 'duration', 'unknown'] },
          muscle_groups: {
            type: 'array',
            items: { type: 'string' },
            description: 'English muscle group keys: chest, back, shoulders, biceps, triceps, legs, glutes, hamstrings, quadriceps, calves, core, forearms.',
          },
          sets: {
            type: 'array',
            description: 'Fully expanded list of sets. "3 Sätze mit 100 kg für 10" becomes three identical entries.',
            items: {
              type: 'object',
              additionalProperties: false,
              required: ['weight', 'weight_unit', 'reps', 'duration_seconds', 'distance', 'distance_unit', 'rpe', 'is_warmup'],
              properties: {
                weight: { type: ['number', 'null'] },
                weight_unit: { type: ['string', 'null'], enum: ['kg', 'lb', null] },
                reps: { type: ['integer', 'null'] },
                duration_seconds: { type: ['number', 'null'] },
                distance: { type: ['number', 'null'] },
                distance_unit: { type: ['string', 'null'], enum: ['m', 'km', 'mi', null] },
                rpe: { type: ['number', 'null'] },
                is_warmup: { type: 'boolean' },
              },
            },
          },
          notes: { type: ['string', 'null'] },
          confidence: { type: 'number', description: '0..1 confidence for this exercise.' },
        },
      },
    },
    new_exercises: {
      type: 'array',
      description: 'Exercises the user explicitly wants to create ("Neue Übung: ...").',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['name', 'muscle_groups', 'exercise_type'],
        properties: {
          name: { type: 'string' },
          muscle_groups: { type: 'array', items: { type: 'string' } },
          exercise_type: { type: 'string', enum: ['strength', 'bodyweight', 'cardio', 'duration', 'unknown'] },
        },
      },
    },
    corrections: {
      type: 'array',
      description: 'Requested changes to already stored data ("die 120 Kilo waren 110").',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['scope', 'exercise_name', 'set_index', 'field', 'value_number', 'value_text', 'match_previous_number'],
        properties: {
          scope: { type: 'string', enum: ['last_set', 'last_exercise', 'last_workout', 'specific'] },
          exercise_name: { type: ['string', 'null'] },
          set_index: { type: ['integer', 'null'], description: '1-based set number when the user names a specific set.' },
          field: { type: 'string', enum: ['weight', 'reps', 'duration', 'distance', 'date', 'muscle_group', 'notes', 'exercise'] },
          value_number: { type: ['number', 'null'], description: 'New numeric value (kg, reps, seconds, meters).' },
          value_text: { type: ['string', 'null'] },
          match_previous_number: { type: ['number', 'null'], description: 'Old value the user referred to, if mentioned.' },
        },
      },
    },
    clarification_question: {
      type: ['string', 'null'],
      description: 'Ask back in the user language when data is missing or ambiguous, otherwise null.',
    },
    confidence: { type: 'number', description: 'Overall 0..1 confidence in this interpretation.' },
  },
} as const;

// ---------------------------------------------------------------------------
// Normalisation
// ---------------------------------------------------------------------------

const TYPE_MAP: Record<string, ExerciseType | null> = {
  strength: ExerciseType.STRENGTH,
  bodyweight: ExerciseType.BODYWEIGHT,
  cardio: ExerciseType.CARDIO,
  duration: ExerciseType.DURATION,
  unknown: null,
};

function normalizeSet(set: z.infer<typeof llmSetSchema>): ParsedSet {
  let weightKg: number | null = null;
  if (set.weight != null && Number.isFinite(set.weight) && set.weight > 0) {
    weightKg = roundWeight(set.weight_unit === 'lb' ? lbToKg(set.weight) : set.weight);
  }

  let distanceM: number | null = null;
  if (set.distance != null && Number.isFinite(set.distance) && set.distance > 0) {
    if (set.distance_unit === 'km') distanceM = kmToMeters(set.distance);
    else if (set.distance_unit === 'mi') distanceM = milesToMeters(set.distance);
    else distanceM = set.distance;
    distanceM = Math.round(distanceM);
  }

  const reps = set.reps != null && set.reps > 0 ? Math.round(set.reps) : null;
  const durationSec = set.duration_seconds != null && set.duration_seconds > 0 ? Math.round(set.duration_seconds) : null;
  const rpe = set.rpe != null && set.rpe > 0 && set.rpe <= 10 ? set.rpe : null;

  return { weightKg, reps, durationSec, distanceM, rpe, isWarmup: set.is_warmup === true };
}

function clampConfidence(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}

/** Converts a validated LLM payload into the internal representation. */
export function toParsedMessage(payload: LlmResponse, meta: { provider: string; model: string; latencyMs?: number; raw?: unknown }): ParsedMessage {
  const exercises: ParsedExercise[] = payload.exercises
    .filter((e) => e.name.trim().length > 0)
    .map((e) => ({
      name: e.name.trim(),
      type: TYPE_MAP[e.exercise_type] ?? null,
      muscleGroups: e.muscle_groups.map((m) => m.trim().toLowerCase()).filter(Boolean),
      sets: e.sets.map(normalizeSet),
      notes: e.notes?.trim() || null,
      confidence: clampConfidence(e.confidence),
    }));

  const corrections: CorrectionChange[] = payload.corrections.map((c) => ({
    scope: c.scope,
    exerciseName: c.exercise_name,
    setIndex: c.set_index,
    field: c.field,
    valueNumber: c.value_number,
    valueText: c.value_text,
    matchPreviousNumber: c.match_previous_number,
  }));

  return {
    intent: payload.intent,
    language: payload.language,
    dateExpression: payload.date_expression,
    exercises,
    newExercises: payload.new_exercises
      .filter((e) => e.name.trim().length > 0)
      .map((e) => ({
        name: e.name.trim(),
        muscleGroups: e.muscle_groups.map((m) => m.trim().toLowerCase()).filter(Boolean),
        type: TYPE_MAP[e.exercise_type] ?? null,
      })),
    corrections,
    clarificationQuestion: payload.clarification_question?.trim() || null,
    confidence: clampConfidence(payload.confidence),
    provider: meta.provider,
    model: meta.model,
    latencyMs: meta.latencyMs,
    raw: meta.raw,
  };
}
