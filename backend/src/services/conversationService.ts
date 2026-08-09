import { prisma } from '../lib/prisma';
import type { ParsedMessage } from '../ai/types';

/**
 * Short lived conversation memory for the bot.
 *
 * Keeps just enough state to support follow-up questions ("Wie viele Sätze?"),
 * pending confirmations and corrections ("die 120 waren 110"), and expires
 * automatically so an old context can never contaminate a new message.
 */

export const CONTEXT_TTL_MINUTES = 30;
const MAX_HISTORY = 6;

export interface LoggedEntry {
  workoutId: string;
  workoutExerciseId: string;
  exerciseId: string;
  exerciseName: string;
  date: string;
  setIds: string[];
}

export interface PendingProposal {
  aiResultId: string;
  /** Serialised ParsedMessage plus the already resolved date. */
  parsed: ParsedMessage;
  isoDate: string;
  transcript: string;
}

export interface ConversationSnapshot {
  history: Array<{ role: 'user' | 'bot'; text: string }>;
  pending?: PendingProposal | null;
  awaitingClarification?: {
    aiResultId: string;
    parsed: ParsedMessage;
    question: string;
  } | null;
  lastEntries?: LoggedEntry[];
}

const EMPTY: ConversationSnapshot = { history: [], pending: null, awaitingClarification: null, lastEntries: [] };

export async function loadContext(userId: string, chatId: bigint): Promise<ConversationSnapshot> {
  const row = await prisma().conversationState.findUnique({ where: { userId_chatId: { userId, chatId } } });
  if (!row || row.expiresAt < new Date()) return { ...EMPTY };
  return { ...EMPTY, ...(row.state as unknown as ConversationSnapshot) };
}

export async function saveContext(userId: string, chatId: bigint, snapshot: ConversationSnapshot): Promise<void> {
  const state = {
    ...snapshot,
    history: snapshot.history.slice(-MAX_HISTORY),
    lastEntries: (snapshot.lastEntries ?? []).slice(-10),
  };
  const expiresAt = new Date(Date.now() + CONTEXT_TTL_MINUTES * 60 * 1000);

  await prisma().conversationState.upsert({
    where: { userId_chatId: { userId, chatId } },
    create: { userId, chatId, state: state as never, expiresAt },
    update: { state: state as never, expiresAt },
  });
}

export async function clearContext(userId: string, chatId: bigint): Promise<void> {
  await prisma().conversationState.deleteMany({ where: { userId, chatId } });
}

export function summarizeHistory(snapshot: ConversationSnapshot): string | null {
  if (snapshot.history.length === 0) return null;
  return snapshot.history.map((h) => `${h.role === 'user' ? 'User' : 'Bot'}: ${h.text}`).join('\n');
}

export function summarizeEntries(snapshot: ConversationSnapshot): string | null {
  if (!snapshot.lastEntries || snapshot.lastEntries.length === 0) return null;
  return snapshot.lastEntries.map((e) => `${e.date}: ${e.exerciseName} (${e.setIds.length} Sätze)`).join('\n');
}

/** Removes conversation rows that expired – called from a periodic job. */
export async function pruneExpiredContexts(): Promise<number> {
  const result = await prisma().conversationState.deleteMany({ where: { expiresAt: { lt: new Date() } } });
  return result.count;
}
