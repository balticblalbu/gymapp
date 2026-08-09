import { PrismaClient } from '@prisma/client';
import { getConfig } from '../config/env';

let client: PrismaClient | null = null;

export function prisma(): PrismaClient {
  if (!client) {
    const config = getConfig();
    client = new PrismaClient({
      log: config.LOG_LEVEL === 'debug' || config.LOG_LEVEL === 'trace' ? ['warn', 'error', 'query'] : ['warn', 'error'],
    });
  }
  return client;
}

export async function disconnectPrisma(): Promise<void> {
  if (client) {
    await client.$disconnect();
    client = null;
  }
}

/**
 * BigInt is not JSON-serialisable. Telegram ids are BigInt in the database, so
 * every API response funnels through this helper before serialisation.
 */
export function jsonSafe<T>(value: T): T {
  return JSON.parse(JSON.stringify(value, (_k, v) => (typeof v === 'bigint' ? v.toString() : v))) as T;
}
