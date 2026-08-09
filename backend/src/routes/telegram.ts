import type { FastifyInstance } from 'fastify';
import { webhookCallback } from 'grammy';
import { getConfig } from '../config/env';
import { forbidden, serviceUnavailable } from '../lib/errors';
import { getBot, getBotUsername } from '../bot';
import { createLinkCode, getTelegramStatus, unlinkTelegram } from '../services/linkService';

export default async function telegramRoutes(app: FastifyInstance) {
  app.post('/link-code', { preHandler: app.authenticate }, async (request) => {
    const botUsername = await getBotUsername();
    const result = await createLinkCode(request.currentUser.id, botUsername);
    return {
      code: result.code,
      expiresAt: result.expiresAt,
      botUsername: result.botUsername,
      deepLink: result.botUsername ? `https://t.me/${result.botUsername}?start=link` : null,
      instructions: `Sende im Telegram-Chat: /link ${result.code}`,
    };
  });

  app.get('/status', { preHandler: app.authenticate }, async (request) => ({
    ...(await getTelegramStatus(request.currentUser.id)),
    botUsername: await getBotUsername(),
  }));

  app.delete('/link', { preHandler: app.authenticate }, async (request, reply) => {
    await unlinkTelegram(request.currentUser.id);
    return reply.code(204).send();
  });
}

/**
 * Webhook endpoint. Only registered when TELEGRAM_MODE=webhook; the secret
 * header is verified so nobody can inject fake updates.
 */
export async function telegramWebhookRoute(app: FastifyInstance) {
  const config = getConfig();

  app.post('/webhook', async (request, reply) => {
    const bot = getBot();
    if (!bot) throw serviceUnavailable('Der Telegram-Bot ist nicht aktiv.');

    if (config.TELEGRAM_WEBHOOK_SECRET) {
      const provided = request.headers['x-telegram-bot-api-secret-token'];
      if (provided !== config.TELEGRAM_WEBHOOK_SECRET) throw forbidden('Ungültiges Webhook-Secret.');
    }

    const handler = webhookCallback(bot, 'fastify');
    return handler(request, reply);
  });
}
