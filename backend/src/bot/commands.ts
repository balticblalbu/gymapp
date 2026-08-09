import type { User } from '@prisma/client';
import { todayInZone, utcToCivilDate } from '../lib/dates';
import { prisma } from '../lib/prisma';
import { getDashboard, getOverview, loadSetRows, describeSets } from '../services/statsService';
import { listExercises } from '../services/exerciseService';
import { formatDay, formatNumber, formatPercent, escapeHtml, type DayView } from './format';
import { summarizeSets } from '../domain/calculations';
import { round } from '../lib/units';

export const HELP_TEXT = `<b>AI Workout Tracker</b>

Schick mir einfach eine <b>Sprachnachricht</b> nach dem Training – ich verstehe normale Sätze:

• „Heute Bankdrücken 100 Kilo, drei Sätze mit zehn Wiederholungen."
• „Beim Squat 140 Kilo, acht Wiederholungen, vier Sätze."
• „20 Minuten auf dem Laufband."
• „Neue Übung: Cable Lateral Raise, Muskelgruppe Schultern."
• „Die 120 Kilo waren eigentlich 110." (Korrektur)

Text funktioniert genauso.

<b>Befehle</b>
/today – Training von heute
/history – letzte Trainings
/stats – Statistik-Überblick
/exercises – Übungen im Katalog
/link CODE – Telegram mit der App verbinden
/cancel – aktuellen Vorschlag verwerfen
/help – diese Hilfe`;

export const START_TEXT = `👋 Willkommen beim <b>AI Workout Tracker</b>.

Damit ich deine Trainingsdaten zuordnen kann, verbinde diesen Chat einmalig mit deinem App-Account:

1. App öffnen → <b>Einstellungen</b> → <b>Telegram verbinden</b>
2. Den angezeigten Code hier senden: <code>/link ABC123</code>

Danach kannst du mir einfach Sprachnachrichten schicken. /help zeigt Beispiele.`;

async function buildDayView(user: User, date: Date): Promise<DayView> {
  const rows = await loadSetRows(user.id, { from: date, to: date });
  const byExercise = new Map<string, typeof rows>();
  for (const row of rows) {
    const list = byExercise.get(row.exerciseId) ?? [];
    list.push(row);
    byExercise.set(row.exerciseId, list);
  }
  const summary = summarizeSets(rows);

  return {
    date: utcToCivilDate(date),
    exercises: [...byExercise.values()].map((sets) => ({
      name: sets[0].exerciseName,
      summary: describeSets(sets),
      volumeKg: round(summarizeSets(sets).volumeKg, 1),
    })),
    volumeKg: round(summary.volumeKg, 1),
    sets: summary.sets,
  };
}

export async function todayCommand(user: User): Promise<string> {
  const view = await buildDayView(user, todayInZone(user.timezone));
  return formatDay(view, 'Training heute');
}

export async function historyCommand(user: User, limit = 5): Promise<string> {
  const workouts = await prisma().workout.findMany({
    where: { userId: user.id, deletedAt: null },
    orderBy: { date: 'desc' },
    take: limit,
    select: { date: true },
  });
  if (workouts.length === 0) return 'Noch keine Trainings erfasst.';

  const blocks: string[] = [];
  for (const workout of workouts) {
    const view = await buildDayView(user, workout.date);
    if (view.exercises.length === 0) continue;
    const lines = view.exercises.map((e) => `• ${escapeHtml(e.name)} – ${e.summary}`).join('\n');
    blocks.push(`<b>${view.date}</b>\n${lines}\nVolumen: ${formatNumber(view.volumeKg)} kg`);
  }
  return blocks.length > 0 ? `<b>Letzte Trainings</b>\n\n${blocks.join('\n\n')}` : 'Noch keine Trainings erfasst.';
}

export async function statsCommand(user: User): Promise<string> {
  const [dashboard, overview] = await Promise.all([
    getDashboard(user.id, user.timezone),
    getOverview(user.id, '30d', user.timezone),
  ]);

  const muscles = overview.muscleGroups
    .slice(0, 6)
    .map((group) => `${MUSCLE_LABELS[group.key] ?? group.key}: ${formatPercent(group.changePercent)}`)
    .join('\n');

  return `<b>Statistik – letzte 30 Tage</b>

Trainings: ${overview.workouts} (${formatNumber(overview.workoutsPerWeek, 1)} / Woche)
Volumen: ${formatNumber(overview.volumeKg)} kg
Sätze: ${overview.totalSets} · Wiederholungen: ${overview.totalReps}
Ø Gewicht: ${formatNumber(overview.avgWeightKg, 1)} kg
Neue Rekorde: ${overview.newRecords}

Kraftentwicklung: ${formatPercent(overview.strengthTrend)}
Volumen vs. Vorperiode: ${formatPercent(overview.volumeTrend)}
Aktuelle Serie: ${dashboard.streakDays} Tage

<b>Muskelgruppen</b>
${muscles || '–'}`;
}

export async function exercisesCommand(user: User): Promise<string> {
  const exercises = await listExercises(user.id, { limit: 200 });
  if (exercises.length === 0) return 'Der Übungskatalog ist leer.';

  const byGroup = new Map<string, string[]>();
  for (const exercise of exercises) {
    const key = exercise.muscleGroups[0]?.muscleGroup.key ?? 'other';
    const list = byGroup.get(key) ?? [];
    list.push(exercise.nameDe ? `${exercise.name} (${exercise.nameDe})` : exercise.name);
    byGroup.set(key, list);
  }

  const blocks = [...byGroup.entries()].map(
    ([key, names]) => `<b>${MUSCLE_LABELS[key] ?? key}</b>\n${names.map((n) => `• ${escapeHtml(n)}`).join('\n')}`,
  );
  return `<b>Übungen (${exercises.length})</b>\n\n${blocks.join('\n\n')}`;
}

export const MUSCLE_LABELS: Record<string, string> = {
  chest: 'Brust',
  back: 'Rücken',
  shoulders: 'Schultern',
  biceps: 'Bizeps',
  triceps: 'Trizeps',
  arms: 'Arme',
  legs: 'Beine',
  glutes: 'Gesäß',
  hamstrings: 'Beinbeuger',
  quadriceps: 'Quadrizeps',
  calves: 'Waden',
  core: 'Rumpf',
  forearms: 'Unterarme',
  other: 'Sonstige',
};
