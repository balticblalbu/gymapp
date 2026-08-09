import { randomInt } from 'node:crypto';
import { getConfig } from '../config/env';
import { badRequest, forbidden, notFound } from '../lib/errors';
import { prisma } from '../lib/prisma';

/**
 * Account linking between the Android app and Telegram.
 *
 * The app generates a short one-time code, the user sends `/link ABC123` to the
 * bot. Until that happened the bot refuses to store anything – an unlinked
 * Telegram user can never reach somebody else's training data.
 */

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I
const CODE_LENGTH = 6;
export const LINK_CODE_TTL_MINUTES = 15;

function generateCode(): string {
  let code = '';
  for (let i = 0; i < CODE_LENGTH; i += 1) code += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return code;
}

export interface LinkCodeResponse {
  code: string;
  expiresAt: Date;
  botUsername: string | null;
}

export async function createLinkCode(userId: string, botUsername: string | null): Promise<LinkCodeResponse> {
  // Invalidate older, still unused codes of this user.
  await prisma().telegramLinkCode.updateMany({
    where: { userId, usedAt: null, expiresAt: { gt: new Date() } },
    data: { expiresAt: new Date() },
  });

  let code = generateCode();
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const clash = await prisma().telegramLinkCode.findUnique({ where: { code } });
    if (!clash) break;
    code = generateCode();
  }

  const expiresAt = new Date(Date.now() + LINK_CODE_TTL_MINUTES * 60 * 1000);
  await prisma().telegramLinkCode.create({ data: { userId, code, expiresAt } });
  return { code, expiresAt, botUsername };
}

export interface TelegramIdentity {
  telegramUserId: bigint;
  chatId: bigint;
  username?: string | null;
  firstName?: string | null;
}

export async function redeemLinkCode(code: string, identity: TelegramIdentity) {
  const config = getConfig();
  if (config.telegramAllowedUserIds.length > 0 && !config.telegramAllowedUserIds.includes(identity.telegramUserId)) {
    throw forbidden('Diese Telegram-Kennung ist für diese Instanz nicht freigeschaltet.');
  }

  const normalized = code.trim().toUpperCase();
  const record = await prisma().telegramLinkCode.findUnique({ where: { code: normalized } });
  if (!record) throw notFound('Link-Code');
  if (record.usedAt) throw badRequest('Dieser Code wurde bereits verwendet.');
  if (record.expiresAt < new Date()) throw badRequest('Dieser Code ist abgelaufen. Bitte erzeuge in der App einen neuen.');

  const existing = await prisma().telegramAccount.findUnique({ where: { telegramUserId: identity.telegramUserId } });
  if (existing && existing.userId !== record.userId) {
    throw badRequest('Dieses Telegram-Konto ist bereits mit einem anderen Account verbunden.');
  }

  const account = await prisma().telegramAccount.upsert({
    where: { telegramUserId: identity.telegramUserId },
    create: {
      userId: record.userId,
      telegramUserId: identity.telegramUserId,
      chatId: identity.chatId,
      username: identity.username ?? null,
      firstName: identity.firstName ?? null,
    },
    update: { chatId: identity.chatId, username: identity.username ?? null, firstName: identity.firstName ?? null },
    include: { user: true },
  });

  await prisma().telegramLinkCode.update({ where: { id: record.id }, data: { usedAt: new Date() } });
  return account;
}

/** Resolves the app user behind a Telegram id, or null when not linked. */
export async function findUserByTelegramId(telegramUserId: bigint) {
  const config = getConfig();
  if (config.telegramAllowedUserIds.length > 0 && !config.telegramAllowedUserIds.includes(telegramUserId)) {
    return null;
  }
  const account = await prisma().telegramAccount.findUnique({
    where: { telegramUserId },
    include: { user: true },
  });
  return account?.user ?? null;
}

export async function unlinkTelegram(userId: string): Promise<void> {
  await prisma().telegramAccount.deleteMany({ where: { userId } });
}

export async function getTelegramStatus(userId: string) {
  const account = await prisma().telegramAccount.findUnique({ where: { userId } });
  if (!account) return { linked: false as const };
  return {
    linked: true as const,
    telegramUserId: account.telegramUserId.toString(),
    username: account.username,
    firstName: account.firstName,
    linkedAt: account.linkedAt,
  };
}
