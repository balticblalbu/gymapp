import Fastify, { type FastifyBaseLogger, type FastifyInstance } from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import jwt from '@fastify/jwt';
import rateLimit from '@fastify/rate-limit';
import { ZodError } from 'zod';
import { Prisma } from '@prisma/client';
import { getConfig } from './config/env';
import { AppError } from './lib/errors';
import { logger } from './lib/logger';
import { prisma } from './lib/prisma';
import authPlugin from './plugins/auth';
import authRoutes from './routes/auth';
import exerciseRoutes, { muscleGroupRoutes } from './routes/exercises';
import exportRoutes from './routes/export';
import statsRoutes from './routes/stats';
import syncRoutes from './routes/sync';
import aiRoutes from './routes/ai';
import telegramRoutes, { telegramWebhookRoute } from './routes/telegram';
import workoutRoutes from './routes/workouts';

export async function buildServer(): Promise<FastifyInstance> {
  const config = getConfig();

  const app = Fastify({
    loggerInstance: logger() as FastifyBaseLogger,
    trustProxy: true,
    bodyLimit: 2 * 1024 * 1024,
    // BigInt (Telegram ids) is not valid JSON – serialise it as a string.
    serializerOpts: { rounding: 'trunc' },
  });

  app.setSerializerCompiler(() => (data) => JSON.stringify(data, (_key, value) => (typeof value === 'bigint' ? value.toString() : value)));

  await app.register(helmet, { contentSecurityPolicy: false });
  await app.register(cors, {
    origin: config.corsOrigins,
    credentials: true,
    methods: ['GET', 'POST', 'PATCH', 'PUT', 'DELETE', 'OPTIONS'],
  });
  await app.register(rateLimit, {
    max: config.RATE_LIMIT_MAX,
    timeWindow: config.RATE_LIMIT_WINDOW,
    // Authenticated clients are limited per user, anonymous ones per IP.
    keyGenerator: (request) => {
      const auth = request.headers.authorization;
      return auth ? `token:${auth.slice(-24)}` : `ip:${request.ip}`;
    },
  });
  await app.register(jwt, {
    secret: config.JWT_SECRET,
    sign: { expiresIn: config.ACCESS_TOKEN_TTL },
  });
  await app.register(authPlugin);

  // --- error handling ----------------------------------------------------
  app.setErrorHandler((error, request, reply) => {
    if (error instanceof AppError) {
      return reply.code(error.statusCode).send({ error: { code: error.code, message: error.message, details: error.details } });
    }
    if (error instanceof ZodError) {
      return reply.code(400).send({
        error: {
          code: 'BAD_REQUEST',
          message: 'Die Eingabe ist ungültig.',
          details: error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
        },
      });
    }
    if (error instanceof Prisma.PrismaClientKnownRequestError) {
      if (error.code === 'P2002') {
        return reply.code(409).send({ error: { code: 'CONFLICT', message: 'Dieser Eintrag existiert bereits.' } });
      }
      if (error.code === 'P2025') {
        return reply.code(404).send({ error: { code: 'NOT_FOUND', message: 'Der Eintrag wurde nicht gefunden.' } });
      }
    }
    if ((error as { statusCode?: number }).statusCode === 429) {
      return reply.code(429).send({ error: { code: 'RATE_LIMITED', message: 'Zu viele Anfragen. Bitte kurz warten.' } });
    }
    if ((error as { validation?: unknown }).validation) {
      return reply.code(400).send({ error: { code: 'BAD_REQUEST', message: 'Die Anfrage ist ungültig.' } });
    }

    request.log.error({ err: error }, 'Unhandled error');
    return reply.code(500).send({ error: { code: 'INTERNAL_ERROR', message: 'Ein unerwarteter Fehler ist aufgetreten.' } });
  });

  app.setNotFoundHandler((request, reply) => {
    reply.code(404).send({ error: { code: 'NOT_FOUND', message: `Route ${request.method} ${request.url} existiert nicht.` } });
  });

  // --- routes ------------------------------------------------------------
  app.get('/health', { config: { rateLimit: false } }, async () => {
    await prisma().$queryRaw`SELECT 1`;
    return { status: 'ok', time: new Date().toISOString() };
  });

  await app.register(authRoutes, { prefix: '/api/auth' });
  await app.register(exerciseRoutes, { prefix: '/api/exercises' });
  await app.register(muscleGroupRoutes, { prefix: '/api/muscle-groups' });
  await app.register(workoutRoutes, { prefix: '/api/workouts' });
  await app.register(statsRoutes, { prefix: '/api/stats' });
  await app.register(syncRoutes, { prefix: '/api/sync' });
  await app.register(exportRoutes, { prefix: '/api/export' });
  await app.register(aiRoutes, { prefix: '/api/ai' });
  await app.register(telegramRoutes, { prefix: '/api/telegram' });

  if (config.TELEGRAM_MODE === 'webhook') {
    await app.register(telegramWebhookRoute, { prefix: '/api/telegram' });
  }

  return app;
}
