import { getConfig } from '../../config/env';
import { log } from '../../lib/logger';
import type { LLMWorkoutParser } from '../types';
import { AnthropicWorkoutParser } from './anthropicParser';
import { HeuristicWorkoutParser } from './heuristicParser';
import { OpenAIWorkoutParser } from './openaiParser';

const logger = log('llm');

let cached: LLMWorkoutParser | null = null;

/**
 * Provider factory. `LLM_PROVIDER=heuristic` (or a missing API key) keeps the
 * whole pipeline working offline with the rule based parser.
 */
export function getWorkoutParser(): LLMWorkoutParser {
  if (cached) return cached;
  const config = getConfig();
  const heuristic = new HeuristicWorkoutParser();

  if (config.LLM_PROVIDER === 'anthropic' && config.ANTHROPIC_API_KEY) {
    cached = new AnthropicWorkoutParser({
      apiKey: config.ANTHROPIC_API_KEY,
      model: config.LLM_MODEL,
      fallback: heuristic,
    });
  } else if (config.LLM_PROVIDER === 'openai' && config.OPENAI_API_KEY) {
    cached = new OpenAIWorkoutParser({
      apiKey: config.OPENAI_API_KEY,
      model: config.LLM_MODEL,
      baseURL: config.OPENAI_BASE_URL,
      fallback: heuristic,
    });
  } else {
    if (config.LLM_PROVIDER !== 'heuristic') {
      logger.warn(
        { provider: config.LLM_PROVIDER },
        'API key for the configured LLM provider is missing – falling back to the rule based parser',
      );
    }
    cached = heuristic;
  }

  logger.info({ provider: cached.name, model: cached.model }, 'Workout parser ready');
  return cached;
}

export function setWorkoutParser(parser: LLMWorkoutParser | null): void {
  cached = parser;
}

export { AnthropicWorkoutParser, HeuristicWorkoutParser, OpenAIWorkoutParser };
