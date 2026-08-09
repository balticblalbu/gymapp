import { Bot, GrammyError, HttpError, InlineKeyboard, type Context } from 'grammy';
import { DataSource, TelegramMessageType, type User } from '@prisma/client';
import { getConfig } from '../config/env';
import { AppError } from '../lib/errors';
import { log } from '../lib/logger';
import { prisma } from '../lib/prisma';
import { getSpeechToTextProvider } from '../ai/stt';
import { cancelPending, confirmPending, processMessage } from '../ai/pipeline';
import { findUserByTelegramId, redeemLinkCode } from '../services/linkService';
import { clearContext } from '../services/conversationService';
import { HELP_TEXT, START_TEXT, exercisesCommand, historyCommand, statsCommand, todayCommand } from './commands';
import { formatPipelineResult } from './format';

const logger = log('bot');

/** Telegram caps messages at 4096 characters. */
const MAX_MESSAGE_LENGTH = 3900;

function truncate(text: string): string {
  return text.length <= MAX_MESSAGE_LENGTH ? text : `${text.slice(0, MAX_MESSAGE_LENGTH)}\n…`;
}

async function reply(ctx: Context, text: string, keyboard?: InlineKeyboard): Promise<void> {
  await ctx.reply(truncate(text), { parse_mode: 'HTML', reply_markup: keyboard, link_preview_options: { is_disabled: true } });
}

function confirmKeyboard(aiResultId: string): InlineKeyboard {
  return new InlineKeyboard()
    .text('✓ Speichern', `save:${aiResultId}`)
    .text('✎ Bearbeiten', `edit:${aiResultId}`)
    .text('✕ Abbrechen', `cancel:${aiResultId}`);
}

async function requireUser(ctx: Context): Promise<User | null> {
  const telegramUserId = ctx.from?.id;
  if (!telegramUserId) return null;

  const user = await findUserByTelegramId(BigInt(telegramUserId));
  if (!user) {
    await reply(
      ctx,
      'Dieser Chat ist noch mit keinem Account verbunden.\n\nÖffne die App → Einstellungen → <b>Telegram verbinden</b> und schick mir den Code:\n<code>/link ABC123</code>',
    );
    return null;
  }
  return user;
}

async function recordMessage(
  ctx: Context,
  user: User | null,
  type: TelegramMessageType,
  data: { rawText?: string | null; fileId?: string | null; durationSec?: number | null; transcript?: string | null; transcriptLanguage?: string | null; sttProvider?: string | null },
): Promise<string> {
  const row = await prisma().telegramMessage.create({
    data: {
      userId: user?.id ?? null,
      telegramUserId: BigInt(ctx.from?.id ?? 0),
      chatId: BigInt(ctx.chat?.id ?? 0),
      messageId: ctx.msg?.message_id ? BigInt(ctx.msg.message_id) : null,
      type,
      rawText: data.rawText ?? null,
      fileId: data.fileId ?? null,
      durationSec: data.durationSec ?? null,
      transcript: data.transcript ?? null,
      transcriptLanguage: data.transcriptLanguage ?? null,
      sttProvider: data.sttProvider ?? null,
    },
  });
  return row.id;
}

/** Runs the parsed text through the pipeline and renders the answer. */
async function handleText(ctx: Context, user: User, text: string, source: DataSource, telegramMessageId: string): Promise<void> {
  const result = await processMessage({
    user,
    chatId: BigInt(ctx.chat?.id ?? 0),
    text,
    source,
    telegramMessageId,
  });

  const formatted = formatPipelineResult(result);
  await reply(ctx, formatted.text, formatted.keyboard === 'confirm' && formatted.aiResultId ? confirmKeyboard(formatted.aiResultId) : undefined);
}

export function createBot(token: string): Bot {
  const bot = new Bot(token);
  const config = getConfig();

  // --- Commands ----------------------------------------------------------
  bot.command('start', async (ctx) => {
    await recordMessage(ctx, null, TelegramMessageType.COMMAND, { rawText: ctx.message?.text });
    const user = await findUserByTelegramId(BigInt(ctx.from?.id ?? 0));
    await reply(ctx, user ? `👋 Hi ${user.name}! Alles verbunden. Schick mir einfach eine Sprachnachricht.\n\n${HELP_TEXT}` : START_TEXT);
  });

  bot.command('help', async (ctx) => {
    await reply(ctx, HELP_TEXT);
  });

  bot.command('link', async (ctx) => {
    const code = (ctx.match ?? '').toString().trim();
    await recordMessage(ctx, null, TelegramMessageType.COMMAND, { rawText: ctx.message?.text });

    if (!code) {
      await reply(ctx, 'Bitte den Code aus der App mitschicken, z. B. <code>/link ABC123</code>');
      return;
    }
    try {
      const account = await redeemLinkCode(code, {
        telegramUserId: BigInt(ctx.from?.id ?? 0),
        chatId: BigInt(ctx.chat?.id ?? 0),
        username: ctx.from?.username ?? null,
        firstName: ctx.from?.first_name ?? null,
      });
      await reply(ctx, `✓ Verbunden mit dem Account von <b>${account.user.name}</b>.\n\nAb jetzt kannst du mir einfach dein Training diktieren.`);
    } catch (error) {
      const message = error instanceof AppError ? error.message : 'Der Code konnte nicht eingelöst werden.';
      await reply(ctx, `❌ ${message}`);
    }
  });

  bot.command('today', async (ctx) => {
    const user = await requireUser(ctx);
    if (!user) return;
    await reply(ctx, await todayCommand(user));
  });

  bot.command('history', async (ctx) => {
    const user = await requireUser(ctx);
    if (!user) return;
    await reply(ctx, await historyCommand(user));
  });

  bot.command('stats', async (ctx) => {
    const user = await requireUser(ctx);
    if (!user) return;
    await reply(ctx, await statsCommand(user));
  });

  bot.command('exercises', async (ctx) => {
    const user = await requireUser(ctx);
    if (!user) return;
    await reply(ctx, await exercisesCommand(user));
  });

  bot.command('cancel', async (ctx) => {
    const user = await requireUser(ctx);
    if (!user) return;
    await clearContext(user.id, BigInt(ctx.chat?.id ?? 0));
    await reply(ctx, 'Alles klar, ich habe den offenen Vorschlag verworfen.');
  });

  // --- Inline keyboard ---------------------------------------------------
  bot.on('callback_query:data', async (ctx) => {
    const [action, aiResultId] = ctx.callbackQuery.data.split(':');
    const user = await findUserByTelegramId(BigInt(ctx.from.id));
    if (!user) {
      await ctx.answerCallbackQuery({ text: 'Account nicht verbunden.' });
      return;
    }
    const chatId = BigInt(ctx.chat?.id ?? 0);

    try {
      if (action === 'save') {
        const result = await confirmPending(user, chatId, aiResultId);
        const formatted = formatPipelineResult(result);
        await ctx.answerCallbackQuery({ text: 'Gespeichert' });
        await ctx.editMessageReplyMarkup({ reply_markup: undefined });
        await reply(ctx, formatted.text);
        return;
      }
      if (action === 'cancel') {
        await cancelPending(user, chatId, aiResultId);
        await ctx.answerCallbackQuery({ text: 'Verworfen' });
        await ctx.editMessageReplyMarkup({ reply_markup: undefined });
        await reply(ctx, 'Verworfen. Schick es einfach nochmal – oder korrigiere es direkt in der App.');
        return;
      }
      if (action === 'edit') {
        await ctx.answerCallbackQuery();
        await reply(
          ctx,
          'Sag mir einfach, was falsch ist – z. B. „die 120 Kilo waren 110" oder „das waren 4 Sätze". Alternativ kannst du alles in der App bearbeiten.',
        );
        return;
      }
      await ctx.answerCallbackQuery();
    } catch (error) {
      logger.error({ err: (error as Error).message }, 'Callback failed');
      await ctx.answerCallbackQuery({ text: 'Da ist etwas schiefgelaufen.' });
    }
  });

  // --- Voice / audio -----------------------------------------------------
  bot.on(['message:voice', 'message:audio'], async (ctx) => {
    const user = await requireUser(ctx);
    if (!user) return;

    const voice = ctx.message?.voice ?? ctx.message?.audio;
    if (!voice) return;

    const telegramMessageId = await recordMessage(ctx, user, TelegramMessageType.VOICE, {
      fileId: voice.file_id,
      durationSec: voice.duration,
    });

    await ctx.replyWithChatAction('typing');

    let transcript: string;
    try {
      const file = await ctx.getFile();
      if (!file.file_path) throw new Error('Telegram did not return a file path');

      const url = `https://api.telegram.org/file/bot${token}/${file.file_path}`;
      const response = await fetch(url);
      if (!response.ok) throw new Error(`Download failed with status ${response.status}`);
      const audio = Buffer.from(await response.arrayBuffer());

      const stt = getSpeechToTextProvider();
      const result = await stt.transcribe(audio, {
        filename: file.file_path.split('/').pop() ?? 'voice.oga',
        mimeType: ctx.message?.voice ? 'audio/ogg' : 'audio/mpeg',
        languageHint: user.locale === 'de' || user.locale === 'en' ? user.locale : undefined,
      });

      transcript = result.text;
      await prisma().telegramMessage.update({
        where: { id: telegramMessageId },
        data: { transcript: result.text, transcriptLanguage: result.language ?? null, sttProvider: `${result.provider}:${result.model}` },
      });
    } catch (error) {
      const message =
        error instanceof AppError
          ? error.message
          : 'Die Sprachnachricht konnte nicht transkribiert werden. Bitte versuche es erneut oder schick den Text.';
      logger.warn({ err: (error as Error).message }, 'Voice handling failed');
      await reply(ctx, `❌ ${message}`);
      return;
    }

    try {
      await handleText(ctx, user, transcript, DataSource.TELEGRAM_VOICE, telegramMessageId);
    } catch (error) {
      await handleFailure(ctx, error);
    }
  });

  // --- Plain text --------------------------------------------------------
  bot.on('message:text', async (ctx) => {
    const text = ctx.message.text.trim();
    if (text.startsWith('/')) return;

    const user = await requireUser(ctx);
    if (!user) return;

    const telegramMessageId = await recordMessage(ctx, user, TelegramMessageType.TEXT, { rawText: text });
    await ctx.replyWithChatAction('typing');

    try {
      await handleText(ctx, user, text, DataSource.TELEGRAM_TEXT, telegramMessageId);
    } catch (error) {
      await handleFailure(ctx, error);
    }
  });

  bot.catch((err) => {
    const e = err.error;
    if (e instanceof GrammyError) logger.error({ description: e.description }, 'Telegram API error');
    else if (e instanceof HttpError) logger.error({ err: e.message }, 'Could not reach Telegram');
    else logger.error({ err: (e as Error)?.message }, 'Unhandled bot error');
  });

  void config;
  return bot;
}

async function handleFailure(ctx: Context, error: unknown): Promise<void> {
  const message =
    error instanceof AppError ? error.message : 'Beim Speichern ist etwas schiefgelaufen. Bitte versuche es gleich noch einmal.';
  logger.error({ err: (error as Error).message }, 'Message handling failed');
  await reply(ctx, `❌ ${message}`);
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

let botInstance: Bot | null = null;

export function getBot(): Bot | null {
  return botInstance;
}

export async function startBot(): Promise<Bot | null> {
  const config = getConfig();
  if (!config.TELEGRAM_ENABLED || !config.TELEGRAM_BOT_TOKEN) {
    logger.warn('Telegram bot disabled (TELEGRAM_ENABLED=false or TELEGRAM_BOT_TOKEN missing)');
    return null;
  }

  const bot = createBot(config.TELEGRAM_BOT_TOKEN);
  botInstance = bot;

  await bot.api.setMyCommands([
    { command: 'today', description: 'Training von heute' },
    { command: 'history', description: 'Letzte Trainings' },
    { command: 'stats', description: 'Statistiken' },
    { command: 'exercises', description: 'Übungskatalog' },
    { command: 'link', description: 'Mit App-Account verbinden' },
    { command: 'cancel', description: 'Vorschlag verwerfen' },
    { command: 'help', description: 'Hilfe' },
  ]);

  if (config.TELEGRAM_MODE === 'webhook') {
    if (!config.TELEGRAM_WEBHOOK_URL) throw new Error('TELEGRAM_MODE=webhook requires TELEGRAM_WEBHOOK_URL');
    await bot.init();
    await bot.api.setWebhook(config.TELEGRAM_WEBHOOK_URL, {
      secret_token: config.TELEGRAM_WEBHOOK_SECRET || undefined,
      drop_pending_updates: true,
    });
    logger.info({ url: config.TELEGRAM_WEBHOOK_URL }, 'Telegram webhook registered');
  } else {
    await bot.api.deleteWebhook({ drop_pending_updates: true }).catch(() => undefined);
    // start() resolves only when the bot stops, so it is intentionally not awaited.
    void bot.start({
      onStart: (info) => logger.info({ username: info.username }, 'Telegram bot polling'),
    });
  }

  return bot;
}

export async function stopBot(): Promise<void> {
  if (botInstance) {
    await botInstance.stop().catch(() => undefined);
    botInstance = null;
  }
}

export async function getBotUsername(): Promise<string | null> {
  const bot = getBot();
  if (!bot) return null;
  try {
    if (!bot.isInited()) await bot.init();
    return bot.botInfo.username;
  } catch {
    return null;
  }
}
