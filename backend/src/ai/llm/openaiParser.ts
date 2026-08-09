import OpenAI from 'openai';
import { log } from '../../lib/logger';
import type { LLMWorkoutParser, ParseContext, ParsedMessage } from '../types';
import { buildSystemPrompt, buildUserPrompt } from './prompt';
import { LLM_JSON_SCHEMA, llmResponseSchema, toParsedMessage } from './schema';

const logger = log('llm:openai');

export interface OpenAIParserOptions {
  apiKey: string;
  model: string;
  baseURL?: string;
  /** Used when the model call fails, so the bot still does something useful. */
  fallback?: LLMWorkoutParser;
}

/**
 * OpenAI implementation of the workout parser using structured outputs.
 * Any transport or validation failure degrades to the heuristic parser instead
 * of surfacing a stack trace to the user.
 */
export class OpenAIWorkoutParser implements LLMWorkoutParser {
  readonly name = 'openai';
  readonly model: string;
  private readonly client: OpenAI;
  private readonly fallback?: LLMWorkoutParser;

  constructor(options: OpenAIParserOptions) {
    this.model = options.model;
    this.client = new OpenAI({ apiKey: options.apiKey, baseURL: options.baseURL });
    this.fallback = options.fallback;
  }

  async parse(text: string, context: ParseContext): Promise<ParsedMessage> {
    const started = Date.now();
    try {
      const completion = await this.client.chat.completions.create({
        model: this.model,
        temperature: 0,
        messages: [
          { role: 'system', content: buildSystemPrompt(context) },
          { role: 'user', content: buildUserPrompt(text) },
        ],
        response_format: {
          type: 'json_schema',
          json_schema: {
            name: 'workout_extraction',
            strict: true,
            schema: LLM_JSON_SCHEMA as unknown as Record<string, unknown>,
          },
        },
      });

      const content = completion.choices[0]?.message?.content;
      if (!content) throw new Error('Empty completion');

      const json = JSON.parse(content) as unknown;
      const validated = llmResponseSchema.parse(json);
      return toParsedMessage(validated, {
        provider: this.name,
        model: this.model,
        latencyMs: Date.now() - started,
        raw: json,
      });
    } catch (error) {
      logger.warn({ err: (error as Error).message }, 'LLM parse failed, using fallback parser');
      if (this.fallback) {
        const fallbackResult = await this.fallback.parse(text, context);
        return { ...fallbackResult, confidence: Math.min(fallbackResult.confidence, 0.7) };
      }
      return {
        intent: 'unknown',
        exercises: [],
        newExercises: [],
        corrections: [],
        confidence: 0,
        provider: this.name,
        model: this.model,
        clarificationQuestion: null,
        latencyMs: Date.now() - started,
      };
    }
  }
}
