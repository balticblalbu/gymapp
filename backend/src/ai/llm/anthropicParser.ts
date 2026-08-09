import Anthropic from '@anthropic-ai/sdk';
import { log } from '../../lib/logger';
import type { LLMWorkoutParser, ParseContext, ParsedMessage } from '../types';
import { buildSystemPrompt, buildUserPrompt } from './prompt';
import { LLM_JSON_SCHEMA, llmResponseSchema, toParsedMessage } from './schema';

const logger = log('llm:anthropic');

export interface AnthropicParserOptions {
  apiKey: string;
  model: string;
  /** Used when the model call fails, so the app still does something useful. */
  fallback?: LLMWorkoutParser;
}

/**
 * Anthropic implementation of the workout parser.
 *
 * Uses structured outputs (`output_config.format`) so the model is constrained
 * to the same JSON schema the OpenAI provider uses — the rest of the pipeline
 * (date resolution, exercise matching, confidence handling) is untouched.
 *
 * Notes on the request shape, which differs from the OpenAI one:
 *  - `temperature` must NOT be sent. Claude Opus 5 rejects sampling parameters
 *    with a 400.
 *  - Effort is set to "low": extracting sets and reps from one sentence is not
 *    a reasoning-heavy task, and low effort keeps latency and cost down.
 *    Thinking is left at its default (adaptive) rather than disabled, which is
 *    the recommended way to control spend on this model.
 */
export class AnthropicWorkoutParser implements LLMWorkoutParser {
  readonly name = 'anthropic';
  readonly model: string;
  private readonly client: Anthropic;
  private readonly fallback?: LLMWorkoutParser;

  constructor(options: AnthropicParserOptions) {
    this.model = options.model;
    this.client = new Anthropic({ apiKey: options.apiKey });
    this.fallback = options.fallback;
  }

  async parse(text: string, context: ParseContext): Promise<ParsedMessage> {
    const started = Date.now();
    try {
      const response = await this.client.messages.create({
        model: this.model,
        max_tokens: 4096,
        system: buildSystemPrompt(context),
        messages: [{ role: 'user', content: buildUserPrompt(text) }],
        output_config: {
          effort: 'low',
          format: {
            type: 'json_schema',
            schema: LLM_JSON_SCHEMA as unknown as Record<string, unknown>,
          },
        },
      });

      // Safety classifiers can decline a request; content is then empty or partial.
      if (response.stop_reason === 'refusal') {
        throw new Error('Die Anfrage wurde vom Modell abgelehnt.');
      }

      const content = response.content.find((block) => block.type === 'text');
      if (!content || content.type !== 'text' || !content.text) {
        throw new Error('Leere Antwort vom Modell');
      }

      const json = JSON.parse(content.text) as unknown;
      const validated = llmResponseSchema.parse(json);
      return toParsedMessage(validated, {
        provider: this.name,
        model: this.model,
        latencyMs: Date.now() - started,
        raw: json,
      });
    } catch (error) {
      logger.warn(
        { err: error instanceof Error ? error.message : String(error) },
        'Anthropic parsing failed – using the rule based parser',
      );
      if (!this.fallback) throw error;
      return this.fallback.parse(text, context);
    }
  }
}
