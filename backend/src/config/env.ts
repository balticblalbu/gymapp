import 'dotenv/config';
import { z } from 'zod';

/**
 * Central, validated configuration. The process refuses to boot with an
 * invalid configuration instead of failing later at a random call site.
 */
const booleanish = z
  .union([z.boolean(), z.string()])
  .transform((v) => (typeof v === 'boolean' ? v : ['1', 'true', 'yes', 'on'].includes(v.toLowerCase())));

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),
  HOST: z.string().default('0.0.0.0'),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),

  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),

  JWT_SECRET: z.string().min(16, 'JWT_SECRET must be at least 16 characters'),
  ACCESS_TOKEN_TTL: z.string().default('15m'),
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().positive().default(60),

  /** Comma separated origins, or "*" during development. */
  CORS_ORIGIN: z.string().default('*'),
  RATE_LIMIT_MAX: z.coerce.number().int().positive().default(120),
  RATE_LIMIT_WINDOW: z.string().default('1 minute'),

  // --- Telegram ---------------------------------------------------------
  TELEGRAM_BOT_TOKEN: z.string().optional(),
  TELEGRAM_ENABLED: booleanish.default(false),
  /** "polling" works everywhere; "webhook" needs a public HTTPS URL. */
  TELEGRAM_MODE: z.enum(['polling', 'webhook']).default('polling'),
  TELEGRAM_WEBHOOK_URL: z.string().url().optional(),
  TELEGRAM_WEBHOOK_SECRET: z.string().optional(),
  /** Optional hard whitelist of Telegram user ids (comma separated). */
  TELEGRAM_ALLOWED_USER_IDS: z.string().optional(),

  // --- AI providers ------------------------------------------------------
  OPENAI_API_KEY: z.string().optional(),
  ANTHROPIC_API_KEY: z.string().optional(),
  OPENAI_BASE_URL: z.string().url().optional(),
  /** Swappable providers – see src/ai/stt and src/ai/llm. */
  STT_PROVIDER: z.enum(['openai', 'none']).default('openai'),
  STT_MODEL: z.string().default('whisper-1'),
  LLM_PROVIDER: z.enum(['anthropic', 'openai', 'heuristic']).default('anthropic'),
  /** Model id for the selected provider. Claude Opus 5 is the default. */
  LLM_MODEL: z.string().default('claude-opus-5'),
  /** Confidence thresholds that drive auto-save vs. confirm vs. ask-back. */
  AI_AUTOSAVE_THRESHOLD: z.coerce.number().min(0).max(1).default(0.85),
  AI_CONFIRM_THRESHOLD: z.coerce.number().min(0).max(1).default(0.5),

  DEFAULT_TIMEZONE: z.string().default('Europe/Berlin'),
  DEFAULT_LOCALE: z.string().default('de'),

  /** Allow anybody to POST /auth/register. Turn off for a private instance. */
  ALLOW_REGISTRATION: booleanish.default(true),

  /**
   * Private single-user instance: no registration, no login, no tokens.
   * Every request is served as the one local user, which is created on boot.
   * Only safe because the server is meant to run on the user's own network.
   */
  SINGLE_USER_MODE: booleanish.default(true),
  SINGLE_USER_NAME: z.string().default('Athlet'),
  SINGLE_USER_EMAIL: z.string().default('local@gymapp.local'),
});

export type AppConfig = z.infer<typeof envSchema> & {
  telegramAllowedUserIds: bigint[];
  corsOrigins: string[] | true;
};

function buildConfig(): AppConfig {
  const parsed = envSchema.safeParse(process.env);
  if (!parsed.success) {
    const issues = parsed.error.issues.map((i) => `  - ${i.path.join('.') || '(root)'}: ${i.message}`).join('\n');
    throw new Error(`Invalid environment configuration:\n${issues}\n\nCopy .env.example to .env and fill in the values.`);
  }
  const raw = parsed.data;

  const telegramAllowedUserIds = (raw.TELEGRAM_ALLOWED_USER_IDS ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => BigInt(s));

  const corsOrigins = raw.CORS_ORIGIN.trim() === '*' ? true : raw.CORS_ORIGIN.split(',').map((s) => s.trim()).filter(Boolean);

  return { ...raw, telegramAllowedUserIds, corsOrigins };
}

let cached: AppConfig | null = null;

export function getConfig(): AppConfig {
  if (!cached) cached = buildConfig();
  return cached;
}

/** Test helper – forces re-reading process.env. */
export function resetConfigCache(): void {
  cached = null;
}
