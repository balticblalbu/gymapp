import type { FastifyInstance } from 'fastify';
import { ThemePreference, UnitSystem } from '@prisma/client';
import { z } from 'zod';
import { badRequest } from '../lib/errors';
import {
  changePassword,
  deleteAccount,
  issueTokens,
  publicUser,
  refreshTokens,
  register,
  revokeRefreshToken,
  updateUserSettings,
  verifyCredentials,
} from '../services/authService';

const registerSchema = z.object({
  email: z.string().email('Bitte eine gültige E-Mail-Adresse angeben.'),
  password: z.string().min(8, 'Das Passwort muss mindestens 8 Zeichen haben.'),
  name: z.string().min(1, 'Bitte einen Namen angeben.'),
  timezone: z.string().optional(),
  locale: z.string().optional(),
});

const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

const settingsSchema = z.object({
  name: z.string().min(1).optional(),
  unitSystem: z.nativeEnum(UnitSystem).optional(),
  locale: z.string().min(2).max(5).optional(),
  timezone: z.string().min(1).optional(),
  themePreference: z.nativeEnum(ThemePreference).optional(),
  aiModel: z.string().nullable().optional(),
  notificationsEnabled: z.boolean().optional(),
});

export function parseBody<T extends z.ZodTypeAny>(schema: T, body: unknown): z.infer<T> {
  const result = schema.safeParse(body);
  if (!result.success) {
    throw badRequest('Die Eingabe ist ungültig.', result.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })));
  }
  return result.data;
}

export default async function authRoutes(app: FastifyInstance) {
  const sign = (payload: { sub: string }) => app.jwt.sign(payload);

  app.post('/register', async (request, reply) => {
    const body = parseBody(registerSchema, request.body);
    const user = await register(body);
    const tokens = await issueTokens(user, sign);
    return reply.code(201).send({ user: publicUser(user), ...tokens });
  });

  app.post('/login', async (request) => {
    const body = parseBody(loginSchema, request.body);
    const user = await verifyCredentials(body.email, body.password);
    const tokens = await issueTokens(user, sign);
    return { user: publicUser(user), ...tokens };
  });

  app.post('/refresh', async (request) => {
    const body = parseBody(z.object({ refreshToken: z.string().min(1) }), request.body);
    return refreshTokens(body.refreshToken, sign);
  });

  app.post('/logout', async (request, reply) => {
    const body = parseBody(z.object({ refreshToken: z.string().min(1) }), request.body);
    await revokeRefreshToken(body.refreshToken);
    return reply.code(204).send();
  });

  app.get('/me', { preHandler: app.authenticate }, async (request) => ({ user: publicUser(request.currentUser) }));

  app.patch('/me', { preHandler: app.authenticate }, async (request) => {
    const body = parseBody(settingsSchema, request.body);
    const user = await updateUserSettings(request.currentUser.id, body);
    return { user: publicUser(user) };
  });

  app.post('/password', { preHandler: app.authenticate }, async (request, reply) => {
    const body = parseBody(
      z.object({ currentPassword: z.string().min(1), newPassword: z.string().min(8) }),
      request.body,
    );
    await changePassword(request.currentUser.id, body.currentPassword, body.newPassword);
    return reply.code(204).send();
  });

  app.delete('/me', { preHandler: app.authenticate }, async (request, reply) => {
    await deleteAccount(request.currentUser.id);
    return reply.code(204).send();
  });
}
