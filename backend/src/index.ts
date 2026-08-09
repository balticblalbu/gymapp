import { getConfig } from './config/env';
import { log } from './lib/logger';
import { disconnectPrisma, prisma } from './lib/prisma';
import { buildServer } from './server';
import { startBot, stopBot } from './bot';
import { pruneExpiredContexts } from './services/conversationService';

const logger = log('main');

async function main(): Promise<void> {
  const config = getConfig();

  // Fail fast with a readable message instead of a driver stack trace.
  try {
    await prisma().$queryRaw`SELECT 1`;
  } catch (error) {
    logger.fatal(
      { err: (error as Error).message },
      'Keine Verbindung zur Datenbank. Läuft PostgreSQL und stimmt DATABASE_URL? (docker compose up -d db)',
    );
    process.exit(1);
  }

  const app = await buildServer();
  await app.listen({ port: config.PORT, host: config.HOST });
  logger.info({ port: config.PORT, env: config.NODE_ENV }, 'API läuft');

  await startBot().catch((error) => {
    logger.error({ err: (error as Error).message }, 'Telegram-Bot konnte nicht gestartet werden');
  });

  // Housekeeping: drop expired conversation contexts once an hour.
  const cleanup = setInterval(() => {
    pruneExpiredContexts()
      .then((count) => count > 0 && logger.debug({ count }, 'Abgelaufene Kontexte entfernt'))
      .catch(() => undefined);
  }, 60 * 60 * 1000);
  cleanup.unref();

  const shutdown = async (signal: string) => {
    logger.info({ signal }, 'Fahre herunter');
    clearInterval(cleanup);
    await stopBot();
    await app.close();
    await disconnectPrisma();
    process.exit(0);
  };

  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
}

main().catch((error) => {
  logger.fatal({ err: (error as Error).message, stack: (error as Error).stack }, 'Start fehlgeschlagen');
  process.exit(1);
});
