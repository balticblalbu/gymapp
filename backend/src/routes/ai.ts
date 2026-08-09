import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { badRequest } from '../lib/errors';
import { jsonSafe } from '../lib/prisma';
import { processMessage } from '../ai/pipeline';
import { getWorkoutParser } from '../ai/llm';
import { formatPipelineResult } from '../bot/format';

const parseBodySchema = z.object({
  /** Recognised speech or typed text, in German or English. */
  text: z.string().min(1, 'Bitte etwas sagen oder eingeben.').max(4000),
  /** True when the text came from the phone's speech recognition. */
  spoken: z.boolean().optional(),
  /** Set to true to always ask before saving, even at high confidence. */
  confirmFirst: z.boolean().optional(),
});

/**
 * In-app AI entry point.
 *
 * The phone does the speech-to-text (Android's own recogniser) and posts the
 * resulting text here; Claude turns it into structured training data through
 * exactly the same pipeline the Telegram bot uses. That keeps one source of
 * truth for parsing, confidence handling and the audit trail.
 */
export default async function aiRoutes(app: FastifyInstance) {
  app.addHook('onRequest', app.authenticate);

  app.post('/parse', async (request) => {
    const parsed = parseBodySchema.safeParse(request.body);
    if (!parsed.success) throw badRequest(parsed.error.issues[0]?.message ?? 'Ungültige Eingabe.');

    const { text, spoken, confirmFirst } = parsed.data;

    const result = await processMessage({
      user: request.currentUser,
      // Chat id 0 = the app itself; keeps conversation context separate per source.
      chatId: BigInt(0),
      text,
      source: spoken ? 'APP_VOICE' : 'APP_TEXT',
      forceConfirmation: confirmFirst ?? false,
    });

    // The bot's formatter already renders every result kind in German; the app
    // strips the HTML tags it uses for Telegram.
    const formatted = formatPipelineResult(result);
    return jsonSafe({
      transcript: text,
      kind: result.kind,
      saved: result.kind === 'saved' || result.kind === 'exercise_created' || result.kind === 'corrected',
      needsConfirmation: result.kind === 'confirm',
      aiResultId: formatted.aiResultId ?? null,
      message: formatted.text.replace(/<[^>]+>/g, ''),
      result,
    });
  });

  /** Which model is actually answering – shown in settings. */
  app.get('/status', async () => {
    const parser = getWorkoutParser();
    return {
      provider: parser.name,
      model: parser.model,
      ready: parser.name !== 'heuristic',
      note:
        parser.name === 'heuristic'
          ? 'Kein API-Key hinterlegt – es läuft der eingebaute regelbasierte Parser.'
          : undefined,
    };
  });
}
