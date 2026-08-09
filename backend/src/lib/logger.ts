import pino from 'pino';
import { getConfig } from '../config/env';

/** Keys that must never reach the log output. */
const REDACTED = [
  'req.headers.authorization',
  'req.headers.cookie',
  'password',
  'passwordHash',
  'token',
  'accessToken',
  'refreshToken',
  'apiKey',
  'OPENAI_API_KEY',
  'TELEGRAM_BOT_TOKEN',
  '*.password',
  '*.token',
  '*.apiKey',
];

function createLogger() {
  const config = getConfig();
  const pretty = config.NODE_ENV === 'development';
  return pino({
    level: config.LOG_LEVEL,
    redact: { paths: REDACTED, censor: '[redacted]' },
    transport: pretty
      ? { target: 'pino-pretty', options: { colorize: true, translateTime: 'SYS:HH:MM:ss', ignore: 'pid,hostname' } }
      : undefined,
  });
}

let instance: pino.Logger | null = null;

export function logger(): pino.Logger {
  if (!instance) instance = createLogger();
  return instance;
}

/** Child logger with a stable component name, e.g. `log('bot')`. */
export function log(component: string): pino.Logger {
  return logger().child({ component });
}
