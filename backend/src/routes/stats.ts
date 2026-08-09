import type { FastifyInstance } from 'fastify';
import { civilDateToUtc, PERIODS, periodStart, todayInZone, type Period } from '../lib/dates';
import { getCalendar, getDashboard, getOverview, loadSetRows, volumeByWeek } from '../services/statsService';
import { listRecords } from '../services/recordService';

function readPeriod(value: unknown, fallback: Period = '30d'): Period {
  return (PERIODS as readonly string[]).includes(String(value)) ? (value as Period) : fallback;
}

export default async function statsRoutes(app: FastifyInstance) {
  app.addHook('preHandler', app.authenticate);

  app.get('/dashboard', async (request) => getDashboard(request.currentUser.id, request.currentUser.timezone));

  app.get('/overview', async (request) => {
    const query = request.query as { period?: string };
    return getOverview(request.currentUser.id, readPeriod(query.period), request.currentUser.timezone);
  });

  app.get('/calendar', async (request) => {
    const query = request.query as { from?: string; to?: string };
    const today = todayInZone(request.currentUser.timezone);
    const from = query.from ? civilDateToUtc(query.from) : (periodStart('1y', today) as Date);
    const to = query.to ? civilDateToUtc(query.to) : today;
    return { days: await getCalendar(request.currentUser.id, from, to) };
  });

  app.get('/volume', async (request) => {
    const query = request.query as { period?: string; granularity?: 'day' | 'week' };
    const period = readPeriod(query.period, '90d');
    const today = todayInZone(request.currentUser.timezone);
    const rows = await loadSetRows(request.currentUser.id, { from: periodStart(period, today), to: today });

    if (query.granularity === 'day') {
      const byDate = new Map<string, { volumeKg: number; sets: number }>();
      for (const row of rows) {
        const key = row.date.toISOString().slice(0, 10);
        const entry = byDate.get(key) ?? { volumeKg: 0, sets: 0 };
        entry.volumeKg += (row.weightKg ?? 0) * (row.reps ?? 0);
        entry.sets += 1;
        byDate.set(key, entry);
      }
      return {
        granularity: 'day',
        points: [...byDate.entries()].sort().map(([date, v]) => ({ date, ...v })),
      };
    }

    return { granularity: 'week', points: volumeByWeek(rows) };
  });

  app.get('/records', async (request) => {
    const records = await listRecords(request.currentUser.id);
    return {
      records: records.map((record) => ({
        id: record.id,
        exerciseId: record.exerciseId,
        exerciseName: record.exercise.name,
        type: record.type,
        value: record.value,
        previousValue: record.previousValue,
        weightKg: record.weightKg,
        reps: record.reps,
        achievedAt: record.achievedAt,
      })),
    };
  });
}
