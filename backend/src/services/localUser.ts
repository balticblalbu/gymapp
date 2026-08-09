import type { User } from '@prisma/client';
import { randomBytes } from 'node:crypto';
import bcrypt from 'bcryptjs';
import { getConfig } from '../config/env';
import { log } from '../lib/logger';
import { prisma } from '../lib/prisma';

const logger = log('single-user');

let cachedId: string | null = null;

/**
 * The one account of a private single-user instance.
 *
 * Created on first use with a random password nobody needs to know — the
 * account exists because every workout, exercise and record is owned by a user
 * in the schema, not because anyone logs in. Should the instance later be
 * opened up, SINGLE_USER_MODE=false restores the normal login flow and this
 * account keeps working with a password reset.
 */
export async function getLocalUser(): Promise<User> {
  const config = getConfig();
  const db = prisma();

  if (cachedId) {
    const cached = await db.user.findUnique({ where: { id: cachedId } });
    if (cached) return cached;
    cachedId = null;
  }

  const existing = await db.user.findUnique({ where: { email: config.SINGLE_USER_EMAIL } });
  if (existing) {
    cachedId = existing.id;
    return existing;
  }

  // Fall back to any pre-existing account so an instance that was used with
  // login before does not suddenly start on an empty database.
  const anyUser = await db.user.findFirst({ orderBy: { createdAt: 'asc' } });
  if (anyUser) {
    cachedId = anyUser.id;
    logger.info({ email: anyUser.email }, 'Single-user mode is using the existing account');
    return anyUser;
  }

  const created = await db.user.create({
    data: {
      email: config.SINGLE_USER_EMAIL,
      name: config.SINGLE_USER_NAME,
      passwordHash: await bcrypt.hash(randomBytes(32).toString('hex'), 12),
      timezone: config.DEFAULT_TIMEZONE,
      locale: config.DEFAULT_LOCALE,
    },
  });
  cachedId = created.id;
  logger.info({ email: created.email }, 'Local account created');
  return created;
}

export function resetLocalUserCache(): void {
  cachedId = null;
}
