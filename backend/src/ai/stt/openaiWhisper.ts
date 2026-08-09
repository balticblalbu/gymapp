import OpenAI, { toFile } from 'openai';
import { log } from '../../lib/logger';
import { serviceUnavailable } from '../../lib/errors';
import type { SpeechToTextProvider, TranscriptionResult } from '../types';

const logger = log('stt:openai');

export interface OpenAIWhisperOptions {
  apiKey: string;
  model: string;
  baseURL?: string;
}

/**
 * OpenAI Whisper transcription. German and English are auto-detected; the
 * caller may pass a language hint to improve accuracy for short recordings.
 */
export class OpenAIWhisperProvider implements SpeechToTextProvider {
  readonly name = 'openai';
  readonly model: string;
  private readonly client: OpenAI;

  constructor(options: OpenAIWhisperOptions) {
    this.model = options.model;
    this.client = new OpenAI({ apiKey: options.apiKey, baseURL: options.baseURL });
  }

  async transcribe(
    audio: Buffer,
    options: { filename: string; mimeType?: string; languageHint?: string },
  ): Promise<TranscriptionResult> {
    const started = Date.now();
    try {
      const file = await toFile(audio, options.filename, { type: options.mimeType ?? 'audio/ogg' });
      const response = await this.client.audio.transcriptions.create({
        file,
        model: this.model,
        language: options.languageHint,
        // Nudges Whisper towards gym vocabulary instead of phonetic guesses.
        prompt: 'Fitness, Krafttraining, Bankdrücken, Kniebeuge, Kreuzheben, Sätze, Wiederholungen, Kilo, kg, Latzug, Rudern, bench press, squat, deadlift, sets, reps.',
        response_format: 'verbose_json',
      });

      const text = typeof response === 'string' ? response : response.text;
      const language = typeof response === 'string' ? undefined : (response as { language?: string }).language;

      if (!text || !text.trim()) throw new Error('Empty transcript');

      return {
        text: text.trim(),
        language,
        provider: this.name,
        model: this.model,
        latencyMs: Date.now() - started,
      };
    } catch (error) {
      logger.error({ err: (error as Error).message }, 'Transcription failed');
      throw serviceUnavailable('Die Sprachnachricht konnte nicht transkribiert werden.');
    }
  }
}
