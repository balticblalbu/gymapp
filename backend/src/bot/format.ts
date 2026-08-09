import { PersonalRecordType } from '@prisma/client';
import type { RecordDelta } from '../services/recordService';
import type { PipelineResult, SavedExerciseSummary } from '../ai/pipeline';
import { round } from '../lib/units';

/**
 * All bot replies live here so the wording stays consistent and short.
 * Telegram messages use HTML parse mode – every dynamic value is escaped.
 */

export function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function formatNumber(value: number, decimals = 0): string {
  return value.toLocaleString('de-DE', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

const RECORD_LABELS: Record<PersonalRecordType, string> = {
  MAX_WEIGHT: 'Höchstes Gewicht',
  MAX_REPS: 'Meiste Wiederholungen',
  MAX_VOLUME_SET: 'Bestes Satz-Volumen',
  MAX_VOLUME_SESSION: 'Bestes Trainings-Volumen',
  BEST_E1RM: 'Bestes geschätztes 1RM',
  LONGEST_DURATION: 'Längste Dauer',
  LONGEST_DISTANCE: 'Größte Distanz',
};

function recordValue(record: RecordDelta): string {
  switch (record.type) {
    case PersonalRecordType.MAX_REPS:
      return `${formatNumber(record.value)} Wdh`;
    case PersonalRecordType.LONGEST_DURATION:
      return `${formatNumber(Math.round(record.value / 60))} min`;
    case PersonalRecordType.LONGEST_DISTANCE:
      return `${formatNumber(record.value / 1000, 2)} km`;
    default:
      return `${formatNumber(record.value, record.value % 1 === 0 ? 0 : 1)} kg`;
  }
}

export function formatRecords(records: Array<RecordDelta & { exerciseName: string }>): string {
  if (records.length === 0) return '';
  const lines = records.map((record) => {
    const previous = record.previousValue != null ? `\nVorher: ${recordValue({ ...record, value: record.previousValue })}` : '';
    const delta = record.improvementPercent != null ? ` (+${formatNumber(record.improvementPercent, 1)} %)` : '';
    return `🔥 <b>NEUER REKORD</b>\n${escapeHtml(record.exerciseName)}\n${RECORD_LABELS[record.type]}: <b>${recordValue(record)}</b>${previous}${delta}`;
  });
  return `\n\n${lines.join('\n\n')}`;
}

function formatSavedExercise(exercise: SavedExerciseSummary): string {
  const lines = [`<b>${escapeHtml(exercise.exerciseName)}</b>`, exercise.summary];
  if (exercise.volumeKg > 0) lines.push(`Volumen: ${formatNumber(exercise.volumeKg)} kg`);
  if (exercise.progressPercent != null) {
    const sign = exercise.progressPercent >= 0 ? '+' : '';
    lines.push(`vs. letztes Training: ${sign}${formatNumber(exercise.progressPercent, 1)} %`);
  }
  if (exercise.created) lines.push('<i>Neue Übung angelegt</i>');
  return lines.join('\n');
}

export function formatPipelineResult(result: PipelineResult): { text: string; keyboard?: 'confirm'; aiResultId?: string } {
  switch (result.kind) {
    case 'saved': {
      const header = result.exercises.length === 1 ? '✓ Gespeichert' : `✓ ${result.exercises.length} Übungen gespeichert`;
      const body = result.exercises.map(formatSavedExercise).join('\n\n');
      const total =
        result.exercises.length > 1 && result.totalVolumeKg > 0
          ? `\n\nGesamtvolumen: <b>${formatNumber(result.totalVolumeKg)} kg</b>`
          : '';
      return { text: `${header}\n\n${body}${total}${formatRecords(result.records)}` };
    }

    case 'confirm': {
      const lines = result.preview.map((p) => {
        const hint = p.willCreate ? ' <i>(neue Übung)</i>' : '';
        return `<b>${escapeHtml(p.exerciseName)}</b>${hint}\n${p.summary}`;
      });
      return {
        text: `Ich habe Folgendes verstanden (${Math.round(result.confidence * 100)} % sicher):\n\n${lines.join('\n\n')}\n\nDatum: ${result.isoDate}\n\nSoll ich das speichern?`,
        keyboard: 'confirm',
        aiResultId: result.aiResultId,
      };
    }

    case 'clarify':
      return { text: `❓ ${escapeHtml(result.question)}` };

    case 'exercise_created': {
      const groups = result.muscleGroups.length > 0 ? `\nMuskelgruppe: ${result.muscleGroups.join(', ')}` : '';
      return { text: `✓ Neue Übung angelegt\n\n<b>${escapeHtml(result.name)}</b>${groups}` };
    }

    case 'corrected':
      return { text: `✓ Korrigiert\n\n${escapeHtml(result.description)}${formatRecords(result.records)}` };

    case 'query':
      return { text: 'Statistiken siehst du mit /stats, das heutige Training mit /today.' };

    case 'nothing':
    default:
      return { text: escapeHtml((result as { message: string }).message) };
  }
}

export interface DayView {
  date: string;
  exercises: Array<{ name: string; summary: string; volumeKg: number }>;
  volumeKg: number;
  sets: number;
}

export function formatDay(day: DayView, title: string): string {
  if (day.exercises.length === 0) return `${title}\n\nNoch kein Training erfasst.`;
  const body = day.exercises
    .map((e) => `<b>${escapeHtml(e.name)}</b>\n${e.summary}`)
    .join('\n\n');
  return `<b>${title}</b>\n${day.date}\n\n${body}\n\nVolumen: ${formatNumber(day.volumeKg)} kg · ${day.sets} Sätze`;
}

export function formatPercent(value: number | null): string {
  if (value == null) return '–';
  const sign = value >= 0 ? '+' : '';
  return `${sign}${formatNumber(value, 1)} %`;
}

export function formatDuration(seconds: number): string {
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

export { round };
