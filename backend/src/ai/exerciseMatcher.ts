/**
 * Fuzzy matching of spoken exercise names against the catalogue.
 *
 * Speech-to-text produces "bank drücken", "Benchpress", "Beinpresse" – all of
 * which must map onto the same exercise. Matching is deliberately deterministic
 * (no LLM call) so it can be unit tested and stays fast.
 */

export interface ExerciseCandidate {
  id: string;
  name: string;
  nameDe?: string | null;
  aliases?: string[];
}

export interface ExerciseMatch {
  candidate: ExerciseCandidate;
  score: number;
  matchedOn: string;
}

const NOISE_WORDS = new Set([
  'die', 'der', 'das', 'den', 'dem', 'ein', 'eine', 'einen', 'the', 'a', 'an',
  'uebung', 'exercise', 'beim', 'bei', 'am', 'im', 'mit', 'with', 'on', 'of', 'fuer', 'for',
]);

export function normalizeName(value: string): string {
  return value
    .toLowerCase()
    .replace(/ß/g, 'ss')
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

export function tokens(value: string): string[] {
  return normalizeName(value)
    .split(' ')
    .filter((t) => t.length > 0 && !NOISE_WORDS.has(t));
}

/** Classic iterative Levenshtein distance. */
export function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;

  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
  const current = new Array<number>(b.length + 1);

  for (let i = 1; i <= a.length; i += 1) {
    current[0] = i;
    for (let j = 1; j <= b.length; j += 1) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      current[j] = Math.min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost);
    }
    previous = [...current];
  }
  return previous[b.length];
}

export function similarity(a: string, b: string): number {
  const maxLength = Math.max(a.length, b.length);
  if (maxLength === 0) return 1;
  return 1 - levenshtein(a, b) / maxLength;
}

/** Jaccard-ish token overlap, weighted towards full coverage of the query. */
function tokenScore(queryTokens: string[], targetTokens: string[]): number {
  if (queryTokens.length === 0 || targetTokens.length === 0) return 0;
  let matched = 0;
  for (const qt of queryTokens) {
    const hit = targetTokens.some((tt) => tt === qt || (qt.length >= 4 && tt.startsWith(qt)) || (tt.length >= 4 && qt.startsWith(tt)) || similarity(qt, tt) >= 0.85);
    if (hit) matched += 1;
  }
  const coverage = matched / queryTokens.length;
  const precision = matched / targetTokens.length;
  return coverage * 0.75 + precision * 0.25;
}

function scoreAgainst(query: string, target: string): number {
  const q = normalizeName(query);
  const t = normalizeName(target);
  if (!q || !t) return 0;
  if (q === t) return 1;
  // "bank druecken" vs "bankdruecken": compare with spaces removed as well.
  if (q.replace(/ /g, '') === t.replace(/ /g, '')) return 0.98;

  const direct = similarity(q, t);
  const byTokens = tokenScore(tokens(query), tokens(target));
  const containment = t.includes(q) || q.includes(t) ? 0.85 : 0;
  return Math.max(direct, byTokens, containment);
}

/**
 * Returns the ranked matches for a spoken name. Callers use the score to decide
 * between "use it", "ask which one did you mean" and "create a new exercise".
 */
export function matchExercise(query: string, candidates: ExerciseCandidate[]): ExerciseMatch[] {
  const results: ExerciseMatch[] = [];

  for (const candidate of candidates) {
    const targets: string[] = [candidate.name];
    if (candidate.nameDe) targets.push(candidate.nameDe);
    if (candidate.aliases) targets.push(...candidate.aliases);

    let best = 0;
    let matchedOn = candidate.name;
    for (const target of targets) {
      const score = scoreAgainst(query, target);
      if (score > best) {
        best = score;
        matchedOn = target;
      }
    }
    if (best > 0) results.push({ candidate, score: Number(best.toFixed(4)), matchedOn });
  }

  return results.sort((a, b) => b.score - a.score);
}

export const MATCH_ACCEPT_THRESHOLD = 0.82;
export const MATCH_SUGGEST_THRESHOLD = 0.6;

export interface MatchDecision {
  kind: 'accept' | 'suggest' | 'create';
  match?: ExerciseMatch;
  alternatives: ExerciseMatch[];
}

/**
 * Turns the ranked list into a decision:
 *  - `accept`  – confident single match
 *  - `suggest` – plausible matches, ask the user ("Meinst du Bench Press?")
 *  - `create`  – nothing close enough, offer to create a new exercise
 */
export function decideMatch(query: string, candidates: ExerciseCandidate[]): MatchDecision {
  const ranked = matchExercise(query, candidates);
  const top = ranked[0];
  if (!top) return { kind: 'create', alternatives: [] };

  const runnerUp = ranked[1];
  const clearlyBest = !runnerUp || top.score - runnerUp.score >= 0.08;

  if (top.score >= MATCH_ACCEPT_THRESHOLD && clearlyBest) {
    return { kind: 'accept', match: top, alternatives: ranked.slice(1, 4) };
  }
  if (top.score >= MATCH_SUGGEST_THRESHOLD) {
    return { kind: 'suggest', match: top, alternatives: ranked.slice(0, 4) };
  }
  return { kind: 'create', alternatives: ranked.slice(0, 3) };
}
