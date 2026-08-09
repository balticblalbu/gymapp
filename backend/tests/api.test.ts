import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';

/**
 * Integration tests against a real PostgreSQL instance.
 *
 * They cover authentication, CRUD, statistics, personal records, the sync
 * endpoints and the whole Telegram pipeline (with the rule based parser, so no
 * API key is needed).
 *
 * Requirements:
 *   docker compose up -d db
 *   cd backend && npx prisma migrate deploy && npm run seed
 *   npm test
 *
 * Without a reachable database the whole suite is skipped instead of failing,
 * so `npm test` stays useful on a machine without Docker.
 */

process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = process.env.JWT_SECRET ?? 'test-secret-test-secret-test-secret';
process.env.TELEGRAM_ENABLED = 'false';
process.env.LLM_PROVIDER = 'heuristic';
process.env.STT_PROVIDER = 'none';
process.env.LOG_LEVEL = 'silent';
process.env.DATABASE_URL = process.env.DATABASE_URL ?? 'postgresql://gymapp:gymapp@localhost:5432/gymapp?schema=public';

const { prisma, disconnectPrisma } = await import('../src/lib/prisma');

let databaseAvailable = false;
try {
  await prisma().$queryRaw`SELECT 1`;
  const seeded = await prisma().muscleGroup.count();
  databaseAvailable = seeded > 0;
  if (!databaseAvailable) {
    console.warn('\n⚠ Datenbank erreichbar, aber nicht geseedet – bitte `npm run seed` ausführen. Tests werden übersprungen.\n');
  }
} catch {
  console.warn('\n⚠ Keine Datenbank erreichbar – Integrationstests werden übersprungen.\n   Start mit: docker compose up -d db && npx prisma migrate deploy && npm run seed\n');
}

const suite = databaseAvailable ? describe : describe.skip;

suite('API-Integration', () => {
  let app: FastifyInstance;
  let accessToken: string;
  let userId: string;
  const email = `test-${Date.now()}@example.com`;

  const auth = () => ({ authorization: `Bearer ${accessToken}` });

  beforeAll(async () => {
    const { buildServer } = await import('../src/server');
    app = await buildServer();
    await app.ready();

    const response = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { email, password: 'sicheres-passwort', name: 'Testnutzer', timezone: 'Europe/Berlin' },
    });
    expect(response.statusCode).toBe(201);
    const body = response.json();
    accessToken = body.accessToken;
    userId = body.user.id;
  });

  afterAll(async () => {
    if (userId) await prisma().user.delete({ where: { id: userId } }).catch(() => undefined);
    await app?.close();
    await disconnectPrisma();
  });

  // --- Authentifizierung -------------------------------------------------
  describe('Authentifizierung', () => {
    it('lehnt unauthentifizierte Anfragen ab', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/workouts' });
      expect(response.statusCode).toBe(401);
    });

    it('lehnt falsche Passwörter ab', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { email, password: 'falsch' },
      });
      expect(response.statusCode).toBe(401);
    });

    it('meldet an und liefert das Profil', async () => {
      const login = await app.inject({ method: 'POST', url: '/api/auth/login', payload: { email, password: 'sicheres-passwort' } });
      expect(login.statusCode).toBe(200);

      const me = await app.inject({ method: 'GET', url: '/api/auth/me', headers: { authorization: `Bearer ${login.json().accessToken}` } });
      expect(me.json().user.email).toBe(email);
    });

    it('erneuert Tokens und entwertet den alten Refresh-Token', async () => {
      const login = await app.inject({ method: 'POST', url: '/api/auth/login', payload: { email, password: 'sicheres-passwort' } });
      const refreshToken = login.json().refreshToken;

      const first = await app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });
      expect(first.statusCode).toBe(200);

      const second = await app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });
      expect(second.statusCode).toBe(401);
    });

    it('validiert Eingaben', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { email: 'keine-email', password: 'kurz', name: '' },
      });
      expect(response.statusCode).toBe(400);
    });
  });

  // --- Übungen -----------------------------------------------------------
  describe('Übungen', () => {
    it('liefert den geseedeten Katalog', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/exercises', headers: auth() });
      const exercises = response.json().exercises;
      expect(exercises.length).toBeGreaterThan(20);
      expect(exercises.some((e: { name: string }) => e.name === 'Bench Press')).toBe(true);
    });

    it('durchsucht Übungen', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/exercises?search=Bank', headers: auth() });
      expect(response.json().exercises.length).toBeGreaterThan(0);
    });

    it('legt eigene Übungen an, ändert und löscht sie', async () => {
      const created = await app.inject({
        method: 'POST',
        url: '/api/exercises',
        headers: auth(),
        payload: { name: 'Testübung Kabelzug', muscleGroupKeys: ['shoulders'] },
      });
      expect(created.statusCode).toBe(201);
      const id = created.json().exercise.id;
      expect(created.json().exercise.muscleGroups[0].key).toBe('shoulders');

      const patched = await app.inject({
        method: 'PATCH',
        url: `/api/exercises/${id}`,
        headers: auth(),
        payload: { name: 'Testübung umbenannt' },
      });
      expect(patched.json().exercise.name).toBe('Testübung umbenannt');

      const deleted = await app.inject({ method: 'DELETE', url: `/api/exercises/${id}`, headers: auth() });
      expect(deleted.statusCode).toBe(204);
    });

    it('verhindert doppelte Namen', async () => {
      await app.inject({ method: 'POST', url: '/api/exercises', headers: auth(), payload: { name: 'Doppelt' } });
      const second = await app.inject({ method: 'POST', url: '/api/exercises', headers: auth(), payload: { name: 'Doppelt' } });
      expect(second.statusCode).toBe(409);
    });
  });

  // --- Trainings ---------------------------------------------------------
  describe('Trainings, Sätze und Rekorde', () => {
    let workoutId: string;
    let workoutExerciseId: string;
    let benchPressId: string;

    it('legt ein Training mit Sätzen an', async () => {
      const exercises = await app.inject({ method: 'GET', url: '/api/exercises?search=Bench Press', headers: auth() });
      benchPressId = exercises.json().exercises.find((e: { name: string }) => e.name === 'Bench Press').id;

      const workout = await app.inject({
        method: 'POST',
        url: '/api/workouts',
        headers: auth(),
        payload: { date: '2026-08-01', title: 'Push A' },
      });
      expect(workout.statusCode).toBe(201);
      workoutId = workout.json().workout.id;

      const linked = await app.inject({
        method: 'POST',
        url: `/api/workouts/${workoutId}/exercises`,
        headers: auth(),
        payload: { exerciseId: benchPressId },
      });
      workoutExerciseId = linked.json().workout.exercises[0].id;

      for (const set of [
        { weightKg: 100, reps: 10 },
        { weightKg: 100, reps: 9 },
        { weightKg: 100, reps: 8 },
      ]) {
        const response = await app.inject({
          method: 'POST',
          url: `/api/workouts/exercises/${workoutExerciseId}/sets`,
          headers: auth(),
          payload: set,
        });
        expect(response.statusCode).toBe(201);
      }

      const detail = await app.inject({ method: 'GET', url: `/api/workouts/${workoutId}`, headers: auth() });
      const body = detail.json().workout;
      expect(body.exercises[0].sets).toHaveLength(3);
      // 100×10 + 100×9 + 100×8 = 2700
      expect(body.volumeKg).toBe(2700);
      expect(body.totalReps).toBe(27);
    });

    it('lehnt leere Sätze ab', async () => {
      const response = await app.inject({
        method: 'POST',
        url: `/api/workouts/exercises/${workoutExerciseId}/sets`,
        headers: auth(),
        payload: { notes: 'nichts' },
      });
      expect(response.statusCode).toBe(400);
    });

    it('erzeugt persönliche Rekorde automatisch', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/stats/records', headers: auth() });
      const records = response.json().records;
      const maxWeight = records.find((r: { type: string }) => r.type === 'MAX_WEIGHT');
      expect(maxWeight.value).toBe(100);
    });

    it('aktualisiert Rekorde nach dem Bearbeiten eines Satzes', async () => {
      const detail = await app.inject({ method: 'GET', url: `/api/workouts/${workoutId}`, headers: auth() });
      const setId = detail.json().workout.exercises[0].sets[0].id;

      await app.inject({ method: 'PATCH', url: `/api/workouts/sets/${setId}`, headers: auth(), payload: { weightKg: 140, reps: 1 } });

      const records = await app.inject({ method: 'GET', url: '/api/stats/records', headers: auth() });
      const maxWeight = records.json().records.find((r: { type: string }) => r.type === 'MAX_WEIGHT');
      expect(maxWeight.value).toBe(140);
    });

    it('liefert Übungsstatistiken inklusive Zeitreihe', async () => {
      const response = await app.inject({
        method: 'GET',
        url: `/api/exercises/${benchPressId}/stats?period=all`,
        headers: auth(),
      });
      const stats = response.json().stats;
      expect(stats.personalBestKg).toBe(140);
      expect(stats.series.length).toBeGreaterThan(0);
      expect(stats.totalSets).toBe(3);
    });

    it('filtert die Historie nach Muskelgruppe', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/workouts?muscleGroup=chest', headers: auth() });
      expect(response.json().workouts.length).toBeGreaterThan(0);

      const empty = await app.inject({ method: 'GET', url: '/api/workouts?muscleGroup=calves', headers: auth() });
      expect(empty.json().workouts).toHaveLength(0);
    });

    it('löscht Sätze und passt das Volumen an', async () => {
      const detail = await app.inject({ method: 'GET', url: `/api/workouts/${workoutId}`, headers: auth() });
      const setId = detail.json().workout.exercises[0].sets[2].id;

      await app.inject({ method: 'DELETE', url: `/api/workouts/sets/${setId}`, headers: auth() });

      const after = await app.inject({ method: 'GET', url: `/api/workouts/${workoutId}`, headers: auth() });
      expect(after.json().workout.exercises[0].sets).toHaveLength(2);
    });

    it('verweigert Zugriff auf fremde Trainings', async () => {
      const other = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { email: `other-${Date.now()}@example.com`, password: 'sicheres-passwort', name: 'Fremd' },
      });
      const otherToken = other.json().accessToken;

      const response = await app.inject({
        method: 'GET',
        url: `/api/workouts/${workoutId}`,
        headers: { authorization: `Bearer ${otherToken}` },
      });
      expect(response.statusCode).toBe(404);

      await prisma().user.delete({ where: { id: other.json().user.id } }).catch(() => undefined);
    });
  });

  // --- Statistiken -------------------------------------------------------
  describe('Statistiken', () => {
    it('liefert das Dashboard', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/stats/dashboard', headers: auth() });
      const body = response.json();
      expect(body).toHaveProperty('today');
      expect(body).toHaveProperty('comparisons');
      expect(body).toHaveProperty('muscleGroups');
    });

    it('liefert die Gesamtübersicht', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/stats/overview?period=all', headers: auth() });
      expect(response.json().workouts).toBeGreaterThan(0);
    });

    it('liefert den Kalender', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/stats/calendar?from=2026-01-01&to=2026-12-31', headers: auth() });
      expect(Array.isArray(response.json().days)).toBe(true);
    });
  });

  // --- Telegram-Pipeline --------------------------------------------------
  describe('Telegram-Pipeline (regelbasierter Parser)', () => {
    it('speichert ein Training aus einer Textnachricht', async () => {
      const { processMessage } = await import('../src/ai/pipeline');
      const user = await prisma().user.findUniqueOrThrow({ where: { id: userId } });

      const result = await processMessage({
        user,
        chatId: BigInt(4711),
        text: 'Gestern Kniebeuge 120 Kilo, 3 Sätze mit 8 Wiederholungen',
        source: 'TELEGRAM_TEXT',
      });

      expect(result.kind).toBe('saved');
      if (result.kind === 'saved') {
        expect(result.exercises[0].exerciseName).toBe('Squat');
        // 120 × 8 × 3 = 2880
        expect(result.totalVolumeKg).toBe(2880);
      }
    });

    it('fragt nach, wenn Sätze und Wiederholungen fehlen', async () => {
      const { processMessage } = await import('../src/ai/pipeline');
      const user = await prisma().user.findUniqueOrThrow({ where: { id: userId } });

      const result = await processMessage({
        user,
        chatId: BigInt(4712),
        text: 'Latzug mit 70 Kilo gemacht',
        source: 'TELEGRAM_TEXT',
      });
      expect(result.kind).toBe('clarify');
    });

    it('vervollständigt die Rückfrage aus dem Kontext', async () => {
      const { processMessage } = await import('../src/ai/pipeline');
      const user = await prisma().user.findUniqueOrThrow({ where: { id: userId } });
      const chatId = BigInt(4713);

      const first = await processMessage({ user, chatId, text: 'Bankdrücken mit 100 Kilo', source: 'TELEGRAM_TEXT' });
      expect(first.kind).toBe('clarify');

      const second = await processMessage({ user, chatId, text: 'drei mal zehn', source: 'TELEGRAM_TEXT' });
      expect(second.kind).toBe('saved');
      if (second.kind === 'saved') {
        expect(second.exercises[0].sets).toBe(3);
        expect(second.exercises[0].volumeKg).toBe(3000);
      }
    });

    it('legt neue Übungen per Nachricht an', async () => {
      const { processMessage } = await import('../src/ai/pipeline');
      const user = await prisma().user.findUniqueOrThrow({ where: { id: userId } });

      const result = await processMessage({
        user,
        chatId: BigInt(4714),
        text: 'Neue Übung: Cable Lateral Raise. Muskelgruppe Schultern.',
        source: 'TELEGRAM_TEXT',
      });
      expect(result.kind).toBe('exercise_created');

      const exercises = await app.inject({ method: 'GET', url: '/api/exercises?search=Cable Lateral', headers: auth() });
      expect(exercises.json().exercises.length).toBeGreaterThan(0);
    });

    it('speichert den Audit-Trail jeder Interpretation', async () => {
      const results = await prisma().aiParsingResult.findMany({ where: { userId } });
      expect(results.length).toBeGreaterThan(0);
      expect(results[0].inputText).toBeTruthy();
      expect(results[0].structured).toBeTruthy();
    });
  });

  // --- Account-Verknüpfung ------------------------------------------------
  describe('Telegram-Verknüpfung', () => {
    it('erzeugt einen Link-Code und löst ihn ein', async () => {
      const response = await app.inject({ method: 'POST', url: '/api/telegram/link-code', headers: auth() });
      const code = response.json().code;
      expect(code).toMatch(/^[A-Z2-9]{6}$/);

      const { redeemLinkCode } = await import('../src/services/linkService');
      const account = await redeemLinkCode(code, { telegramUserId: BigInt(999123), chatId: BigInt(999123) });
      expect(account.userId).toBe(userId);

      const status = await app.inject({ method: 'GET', url: '/api/telegram/status', headers: auth() });
      expect(status.json().linked).toBe(true);
    });

    it('lehnt bereits verwendete Codes ab', async () => {
      const response = await app.inject({ method: 'POST', url: '/api/telegram/link-code', headers: auth() });
      const code = response.json().code;

      const { redeemLinkCode } = await import('../src/services/linkService');
      await redeemLinkCode(code, { telegramUserId: BigInt(999123), chatId: BigInt(999123) });
      await expect(redeemLinkCode(code, { telegramUserId: BigInt(999123), chatId: BigInt(999123) })).rejects.toThrow();
    });
  });

  // --- Sync ---------------------------------------------------------------
  describe('Synchronisation', () => {
    it('liefert Änderungen seit einem Zeitpunkt', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/sync', headers: auth() });
      const body = response.json();
      expect(body.workouts.length).toBeGreaterThan(0);
      expect(body.exercises.length).toBeGreaterThan(0);
      expect(body).toHaveProperty('serverTime');
    });

    it('übernimmt clientseitig erzeugte Einträge', async () => {
      const workoutId = crypto.randomUUID();
      const exercises = await app.inject({ method: 'GET', url: '/api/exercises?search=Squat', headers: auth() });
      const exerciseId = exercises.json().exercises.find((e: { name: string }) => e.name === 'Squat').id;
      const linkId = crypto.randomUUID();
      const setId = crypto.randomUUID();

      const response = await app.inject({
        method: 'POST',
        url: '/api/sync',
        headers: auth(),
        payload: {
          operations: [
            { entity: 'workout', op: 'upsert', id: workoutId, data: { date: '2026-07-15', title: 'Offline erfasst' } },
            { entity: 'workoutExercise', op: 'upsert', id: linkId, data: { workoutId, exerciseId, position: 0 } },
            { entity: 'set', op: 'upsert', id: setId, data: { workoutExerciseId: linkId, setNumber: 1, weightKg: 150, reps: 5 } },
          ],
        },
      });
      expect(response.json().conflicts).toHaveLength(0);

      const detail = await app.inject({ method: 'GET', url: `/api/workouts/${workoutId}`, headers: auth() });
      expect(detail.json().workout.title).toBe('Offline erfasst');
      expect(detail.json().workout.volumeKg).toBe(750);
    });

    it('meldet einen Konflikt, wenn der Server neuer ist', async () => {
      const workouts = await app.inject({ method: 'GET', url: '/api/workouts?limit=1', headers: auth() });
      const workout = workouts.json().workouts[0];

      const response = await app.inject({
        method: 'POST',
        url: '/api/sync',
        headers: auth(),
        payload: {
          operations: [
            {
              entity: 'workout',
              op: 'upsert',
              id: workout.id,
              baseUpdatedAt: '2020-01-01T00:00:00.000Z',
              data: { date: workout.date, title: 'Veralteter Client' },
            },
          ],
        },
      });
      expect(response.json().conflicts[0].reason).toBe('server_newer');
    });
  });

  // --- Export -------------------------------------------------------------
  describe('Export', () => {
    it('exportiert als JSON', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/export?format=json', headers: auth() });
      expect(response.statusCode).toBe(200);
      expect(response.json().workouts.length).toBeGreaterThan(0);
    });

    it('exportiert als CSV', async () => {
      const response = await app.inject({ method: 'GET', url: '/api/export?format=csv', headers: auth() });
      expect(response.headers['content-type']).toContain('text/csv');
      expect(response.body.split('\n')[0]).toContain('exercise');
    });
  });
});
