import { ExerciseType } from '@prisma/client';

/** Normalised, provider independent result of parsing one user message. */
export type ParsedIntent =
  | 'log_workout'
  | 'create_exercise'
  | 'correction'
  | 'query'
  | 'clarification_answer'
  | 'small_talk'
  | 'unknown';

export interface ParsedSet {
  weightKg?: number | null;
  reps?: number | null;
  durationSec?: number | null;
  distanceM?: number | null;
  rpe?: number | null;
  isWarmup?: boolean;
}

export interface ParsedExercise {
  /** Raw name as spoken; resolved against the catalogue later. */
  name: string;
  type?: ExerciseType | null;
  muscleGroups?: string[];
  sets: ParsedSet[];
  notes?: string | null;
  confidence: number;
}

export type CorrectionField = 'weight' | 'reps' | 'duration' | 'distance' | 'date' | 'muscle_group' | 'notes' | 'exercise';

export interface CorrectionChange {
  scope: 'last_set' | 'last_exercise' | 'last_workout' | 'specific';
  exerciseName?: string | null;
  /** 1-based index of the set inside the exercise. */
  setIndex?: number | null;
  field: CorrectionField;
  valueNumber?: number | null;
  valueText?: string | null;
  /** e.g. "the 120 kg were actually 110" -> matchPreviousNumber = 120 */
  matchPreviousNumber?: number | null;
}

export interface ParsedNewExercise {
  name: string;
  muscleGroups: string[];
  type?: ExerciseType | null;
}

export interface ParsedMessage {
  intent: ParsedIntent;
  language?: string;
  dateExpression?: string | null;
  exercises: ParsedExercise[];
  newExercises: ParsedNewExercise[];
  corrections: CorrectionChange[];
  /** Set when the model itself wants to ask something back. */
  clarificationQuestion?: string | null;
  confidence: number;
  /** Raw provider payload, stored for the audit trail. */
  raw?: unknown;
  provider: string;
  model: string;
  latencyMs?: number;
}

export interface ParseContext {
  /** Today in the user's timezone, ISO calendar date. */
  todayIso: string;
  timezone: string;
  locale: string;
  /** Known exercise names to bias the model towards the existing catalogue. */
  knownExercises: string[];
  /** Condensed transcript of the last few turns for follow-up questions. */
  conversationSummary?: string | null;
  /** Description of the last logged entries, needed for corrections. */
  recentEntriesSummary?: string | null;
}

/**
 * Swappable LLM parser. Implementations must never throw for "I don't know" –
 * they return `intent: 'unknown'` with a low confidence instead.
 */
export interface LLMWorkoutParser {
  readonly name: string;
  readonly model: string;
  parse(text: string, context: ParseContext): Promise<ParsedMessage>;
}

/** Swappable speech-to-text provider. */
export interface TranscriptionResult {
  text: string;
  language?: string;
  provider: string;
  model: string;
  latencyMs?: number;
}

export interface SpeechToTextProvider {
  readonly name: string;
  readonly model: string;
  transcribe(audio: Buffer, options: { filename: string; mimeType?: string; languageHint?: string }): Promise<TranscriptionResult>;
}
