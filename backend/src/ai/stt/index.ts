import { getConfig } from '../../config/env';
import { serviceUnavailable } from '../../lib/errors';
import { log } from '../../lib/logger';
import type { SpeechToTextProvider, TranscriptionResult } from '../types';
import { OpenAIWhisperProvider } from './openaiWhisper';

const logger = log('stt');

/** Used when no STT provider is configured – fails with a friendly message. */
export class DisabledSpeechToTextProvider implements SpeechToTextProvider {
  readonly name = 'none';
  readonly model = 'none';

  async transcribe(): Promise<TranscriptionResult> {
    throw serviceUnavailable(
      'Sprachnachrichten sind nicht aktiviert. Bitte OPENAI_API_KEY setzen oder Text schicken.',
    );
  }
}

let cached: SpeechToTextProvider | null = null;

export function getSpeechToTextProvider(): SpeechToTextProvider {
  if (cached) return cached;
  const config = getConfig();

  if (config.STT_PROVIDER === 'openai' && config.OPENAI_API_KEY) {
    cached = new OpenAIWhisperProvider({
      apiKey: config.OPENAI_API_KEY,
      model: config.STT_MODEL,
      baseURL: config.OPENAI_BASE_URL,
    });
  } else {
    if (config.STT_PROVIDER === 'openai') {
      logger.warn('STT_PROVIDER=openai but OPENAI_API_KEY is missing – voice messages are disabled');
    }
    cached = new DisabledSpeechToTextProvider();
  }

  logger.info({ provider: cached.name, model: cached.model }, 'Speech-to-text provider ready');
  return cached;
}

export function setSpeechToTextProvider(provider: SpeechToTextProvider | null): void {
  cached = provider;
}
