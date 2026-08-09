/**
 * Converts spoken numbers into digits for German and English.
 *
 * Speech-to-text happily returns "hundert Kilo, zehn Wiederholungen" or
 * "one hundred ten", which the rule based parser cannot use directly. This
 * module normalises those into "100 Kilo, 10 Wiederholungen" *before* any
 * pattern matching happens. It is also used to sanity-check LLM output.
 */

export function normalizeWord(word: string): string {
  return word
    .toLowerCase()
    .replace(/ß/g, 'ss')
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/[^a-z0-9.,/-]/g, '');
}

const GERMAN_BASE: Record<string, number> = {
  null: 0,
  ein: 1,
  eine: 1,
  einen: 1,
  einem: 1,
  eins: 1,
  zwei: 2,
  zwo: 2,
  drei: 3,
  vier: 4,
  fuenf: 5,
  sechs: 6,
  sieben: 7,
  acht: 8,
  neun: 9,
  zehn: 10,
  elf: 11,
  zwoelf: 12,
  dreizehn: 13,
  vierzehn: 14,
  fuenfzehn: 15,
  sechzehn: 16,
  siebzehn: 17,
  achtzehn: 18,
  neunzehn: 19,
  zwanzig: 20,
  dreissig: 30,
  vierzig: 40,
  fuenfzig: 50,
  sechzig: 60,
  siebzig: 70,
  achtzig: 80,
  neunzig: 90,
};

const ENGLISH_BASE: Record<string, number> = {
  zero: 0,
  one: 1,
  two: 2,
  three: 3,
  four: 4,
  five: 5,
  six: 6,
  seven: 7,
  eight: 8,
  nine: 9,
  ten: 10,
  eleven: 11,
  twelve: 12,
  thirteen: 13,
  fourteen: 14,
  fifteen: 15,
  sixteen: 16,
  seventeen: 17,
  eighteen: 18,
  nineteen: 19,
  twenty: 20,
  thirty: 30,
  forty: 40,
  fourty: 40,
  fifty: 50,
  sixty: 60,
  seventy: 70,
  eighty: 80,
  ninety: 90,
};

/**
 * Parses a single (possibly compound) German number word such as
 * "zweiundzwanzig", "hundertzehn" or "einhundertfuenfzig".
 */
export function parseGermanNumberWord(raw: string): number | null {
  const word = normalizeWord(raw);
  if (!word) return null;
  if (word in GERMAN_BASE) return GERMAN_BASE[word];

  if (word.includes('tausend')) {
    const [left, right] = splitOnce(word, 'tausend');
    const multiplier = left ? parseGermanNumberWord(left) : 1;
    const remainder = right ? parseGermanNumberWord(right) : 0;
    if (multiplier == null || remainder == null) return null;
    return (multiplier || 1) * 1000 + remainder;
  }

  if (word.includes('hundert')) {
    const [left, right] = splitOnce(word, 'hundert');
    const multiplier = left ? parseGermanNumberWord(left) : 1;
    const remainder = right ? parseGermanNumberWord(right) : 0;
    if (multiplier == null || remainder == null) return null;
    return (multiplier || 1) * 100 + remainder;
  }

  if (word.includes('und')) {
    const [left, right] = splitOnce(word, 'und');
    const ones = parseGermanNumberWord(left);
    const tens = parseGermanNumberWord(right);
    if (ones == null || tens == null) return null;
    // "zweiundzwanzig" = 2 + 20
    if (ones < 10 && tens >= 20) return ones + tens;
    return null;
  }

  return null;
}

function splitOnce(word: string, needle: string): [string, string] {
  const index = word.indexOf(needle);
  return [word.slice(0, index), word.slice(index + needle.length)];
}

/**
 * Replaces spelled out numbers with digits. Handles German compound words as a
 * single token and English multi-token sequences ("one hundred twenty five").
 */
export function normalizeNumberWords(text: string): string {
  const tokens = text.split(/(\s+|[,.;:!?()])/);
  const out: string[] = [];
  let i = 0;

  while (i < tokens.length) {
    const token = tokens[i];
    if (!token || /^\s+$/.test(token) || /^[,.;:!?()]$/.test(token)) {
      out.push(token);
      i += 1;
      continue;
    }

    // English sequences are greedy: consume as many number words as possible.
    const englishRun = consumeEnglishNumber(tokens, i);
    if (englishRun) {
      out.push(String(englishRun.value));
      i = englishRun.nextIndex;
      continue;
    }

    const german = parseGermanNumberWord(token);
    if (german != null && isStandaloneNumberWord(token)) {
      out.push(String(german));
      i += 1;
      continue;
    }

    out.push(token);
    i += 1;
  }

  return out.join('');
}

/**
 * Guards against turning words that merely *contain* a number word into digits
 * (e.g. "Beine" contains "ein"). Only tokens that fully resolve are converted.
 */
function isStandaloneNumberWord(token: string): boolean {
  const word = normalizeWord(token);
  if (word in GERMAN_BASE) return true;
  return /^(ein|zwei|drei|vier|fuenf|sechs|sieben|acht|neun|null)?(hundert|tausend)/.test(word) || /und(zwanzig|dreissig|vierzig|fuenfzig|sechzig|siebzig|achtzig|neunzig)$/.test(word);
}

interface EnglishRun {
  value: number;
  nextIndex: number;
}

function consumeEnglishNumber(tokens: string[], start: number): EnglishRun | null {
  let total = 0;
  let current = 0;
  let consumedAny = false;
  let i = start;

  while (i < tokens.length) {
    const token = tokens[i];
    if (/^\s+$/.test(token) || token === '-') {
      // Skip separators but only if a number word follows.
      const lookahead = tokens[i + 1];
      if (lookahead && isEnglishNumberToken(lookahead)) {
        i += 1;
        continue;
      }
      break;
    }
    const parts = token.split('-').map(normalizeWord).filter(Boolean);
    if (parts.length === 0 || !parts.every((p) => p in ENGLISH_BASE || p === 'hundred' || p === 'thousand')) break;

    for (const part of parts) {
      if (part === 'hundred') {
        current = (current || 1) * 100;
      } else if (part === 'thousand') {
        total += (current || 1) * 1000;
        current = 0;
      } else {
        current += ENGLISH_BASE[part];
      }
    }
    consumedAny = true;
    i += 1;
  }

  if (!consumedAny) return null;
  return { value: total + current, nextIndex: i };
}

function isEnglishNumberToken(token: string): boolean {
  return token
    .split('-')
    .map(normalizeWord)
    .filter(Boolean)
    .every((p) => p in ENGLISH_BASE || p === 'hundred' || p === 'thousand');
}
