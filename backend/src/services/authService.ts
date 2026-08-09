import { randomBytes, createHash } from 'node:crypto';
import bcrypt from 'bcryptjs';
import type { User } from '@prisma/client';
import { getConfig } from '../config/env';
import { conflict, forbidden, unauthorized } from '../lib/errors';
import { isValidTimezone } from '../lib/dates';
import { prisma } from '../lib/prisma';

const BCRYPT_ROUNDS = 12;

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  expiresIn: string;
}

export type SignAccessToken = (payload: { sub: string }) => string;

function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

export function publicUser(user: User) {
  return {
    id: user.id,
    email: user.email,
    name: user.name,
    unitSystem: user.unitSystem,
    locale: user.locale,
    timezone: user.timezone,
    themePreference: user.themePreference,
    aiModel: user.aiModel,
    notificationsEnabled: user.notificationsEnabled,
    createdAt: user.createdAt,
  };
}

export async function register(input: {
  email: string;
  password: string;
  name: string;
  timezone?: string;
  locale?: string;
}): Promise<User> {
  const config = getConfig();
  if (!config.ALLOW_REGISTRATION) throw forbidden('Die Registrierung ist auf dieser Instanz deaktiviert.');

  const email = input.email.trim().toLowerCase();
  const existing = await prisma().user.findUnique({ where: { email } });
  if (existing) throw conflict('Diese E-Mail-Adresse ist bereits registriert.');

  const passwordHash = await bcrypt.hash(input.password, BCRYPT_ROUNDS);
  const timezone = input.timezone && isValidTimezone(input.timezone) ? input.timezone : config.DEFAULT_TIMEZONE;

  return prisma().user.create({
    data: {
      email,
      passwordHash,
      name: input.name.trim(),
      timezone,
      locale: input.locale ?? config.DEFAULT_LOCALE,
    },
  });
}

export async function verifyCredentials(email: string, password: string): Promise<User> {
  const user = await prisma().user.findUnique({ where: { email: email.trim().toLowerCase() } });
  // Always run a hash comparison to keep the response time constant.
  const hash = user?.passwordHash ?? '$2a$12$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidiu';
  const valid = await bcrypt.compare(password, hash);
  if (!user || !valid) throw unauthorized('E-Mail oder Passwort ist falsch.');
  return user;
}

export async function issueTokens(user: User, signAccessToken: SignAccessToken): Promise<AuthTokens> {
  const config = getConfig();
  const refreshToken = randomBytes(48).toString('hex');
  const expiresAt = new Date(Date.now() + config.REFRESH_TOKEN_TTL_DAYS * 24 * 60 * 60 * 1000);

  await prisma().refreshToken.create({
    data: { userId: user.id, tokenHash: hashToken(refreshToken), expiresAt },
  });

  return {
    accessToken: signAccessToken({ sub: user.id }),
    refreshToken,
    expiresIn: config.ACCESS_TOKEN_TTL,
  };
}

export async function refreshTokens(refreshToken: string, signAccessToken: SignAccessToken): Promise<AuthTokens> {
  const tokenHash = hashToken(refreshToken);
  const stored = await prisma().refreshToken.findUnique({ where: { tokenHash }, include: { user: true } });

  if (!stored || stored.revokedAt || stored.expiresAt < new Date()) {
    throw unauthorized('Die Sitzung ist abgelaufen. Bitte melde dich erneut an.');
  }

  // Rotate: the used token is invalidated immediately.
  await prisma().refreshToken.update({ where: { id: stored.id }, data: { revokedAt: new Date() } });
  return issueTokens(stored.user, signAccessToken);
}

export async function revokeRefreshToken(refreshToken: string): Promise<void> {
  const tokenHash = hashToken(refreshToken);
  await prisma().refreshToken.updateMany({ where: { tokenHash, revokedAt: null }, data: { revokedAt: new Date() } });
}

export async function revokeAllTokens(userId: string): Promise<void> {
  await prisma().refreshToken.updateMany({ where: { userId, revokedAt: null }, data: { revokedAt: new Date() } });
}

export async function updateUserSettings(
  userId: string,
  input: Partial<Pick<User, 'name' | 'unitSystem' | 'locale' | 'timezone' | 'themePreference' | 'aiModel' | 'notificationsEnabled'>>,
): Promise<User> {
  if (input.timezone && !isValidTimezone(input.timezone)) {
    throw conflict(`Unbekannte Zeitzone: ${input.timezone}`);
  }
  return prisma().user.update({ where: { id: userId }, data: input });
}

export async function changePassword(userId: string, currentPassword: string, newPassword: string): Promise<void> {
  const user = await prisma().user.findUnique({ where: { id: userId } });
  if (!user) throw unauthorized();
  const valid = await bcrypt.compare(currentPassword, user.passwordHash);
  if (!valid) throw unauthorized('Das aktuelle Passwort ist falsch.');

  await prisma().user.update({ where: { id: userId }, data: { passwordHash: await bcrypt.hash(newPassword, BCRYPT_ROUNDS) } });
  await revokeAllTokens(userId);
}

/** Deletes the account and – via cascades – every piece of data attached to it. */
export async function deleteAccount(userId: string): Promise<void> {
  await prisma().user.delete({ where: { id: userId } });
}
