package com.gymapp.tracker.core.ai

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

/**
 * Language helpers for voice input — ports of the backend's `numberWords.ts`,
 * `dateResolver.ts` and `exerciseMatcher.ts`.
 *
 * These run in the app rather than being left to the model on purpose: dates
 * and exercise identity have to be deterministic and testable. The model
 * reports what was *said* ("letzten Freitag"); resolving that to a calendar day
 * happens here, in the user's own timezone.
 */

// ---------------------------------------------------------------------------
// Names
// ---------------------------------------------------------------------------

/** Lowercase, umlauts expanded, punctuation gone — the key both sides compare. */
fun normalizeName(raw: String): String = raw
    .lowercase(Locale.GERMANY)
    .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)

    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

data class ExerciseCandidate(
    val id: String,
    val name: String,
    val nameDe: String?,
    val aliases: List<String>,
)

data class ExerciseMatch(val candidate: ExerciseCandidate, val score: Double)

/**
 * Ranks catalogue entries against a spoken name.
 * 1.0 = exact alias hit; anything below ~0.6 is not trustworthy.
 */
fun matchExercise(spoken: String, candidates: List<ExerciseCandidate>): List<ExerciseMatch> {
    val needle = normalizeName(spoken)
    if (needle.isBlank()) return emptyList()

    return candidates.mapNotNull { candidate ->
        val keys = buildList {
            add(normalizeName(candidate.name))
            candidate.nameDe?.let { add(normalizeName(it)) }
            addAll(candidate.aliases.map { normalizeName(it) })
        }.filter { it.isNotBlank() }.distinct()

        val best = keys.maxOfOrNull { key ->
            when {
                key == needle -> 1.0
                needle.contains(key) || key.contains(needle) -> {
                    // Longer overlaps are more convincing than short ones.
                    val shorter = min(key.length, needle.length).toDouble()
                    val longer = maxOf(key.length, needle.length).toDouble()
                    0.75 + 0.2 * (shorter / longer)
                }
                else -> {
                    val distance = levenshtein(key, needle)
                    val longest = maxOf(key.length, needle.length)
                    if (longest == 0) 0.0 else 1.0 - distance.toDouble() / longest
                }
            }
        } ?: 0.0

        if (best <= 0.0) null else ExerciseMatch(candidate, best)
    }.sortedByDescending { it.score }
}

sealed interface MatchDecision {
    data class Accept(val candidate: ExerciseCandidate, val score: Double) : MatchDecision
    data class Ask(val options: List<ExerciseCandidate>) : MatchDecision
    data class Create(val name: String) : MatchDecision
}

/** Turns the ranking into a decision: use it, ask about it, or create it. */
fun decideMatch(spoken: String, candidates: List<ExerciseCandidate>): MatchDecision {
    val ranked = matchExercise(spoken, candidates)
    val best = ranked.firstOrNull() ?: return MatchDecision.Create(spoken)
    val runnerUp = ranked.getOrNull(1)

    return when {
        best.score >= 0.92 -> MatchDecision.Accept(best.candidate, best.score)
        // Clear winner over the next best: still safe to take.
        best.score >= 0.72 && (runnerUp == null || best.score - runnerUp.score >= 0.12) ->
            MatchDecision.Accept(best.candidate, best.score)
        best.score >= 0.6 -> MatchDecision.Ask(ranked.take(3).map { it.candidate })
        else -> MatchDecision.Create(spoken)
    }
}

// ---------------------------------------------------------------------------
// Number words
// ---------------------------------------------------------------------------

private val GERMAN_UNITS = mapOf(
    "null" to 0, "ein" to 1, "eine" to 1, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4,
    "fuenf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10,
    "elf" to 11, "zwoelf" to 12, "dreizehn" to 13, "vierzehn" to 14, "fuenfzehn" to 15,
    "sechzehn" to 16, "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19,
)

private val GERMAN_TENS = mapOf(
    "zwanzig" to 20, "dreissig" to 30, "vierzig" to 40, "fuenfzig" to 50,
    "sechzig" to 60, "siebzig" to 70, "achtzig" to 80, "neunzig" to 90,
)

private val ENGLISH_UNITS = mapOf(
    "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
    "eighteen" to 18, "nineteen" to 19,
)

private val ENGLISH_TENS = mapOf(
    "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
)

/** "einhundertzehn" → 110, "fünfundvierzig" → 45. Null when it is not a number. */
fun parseGermanNumberWord(raw: String): Int? {
    var word = normalizeName(raw).replace(" ", "")
    if (word.isEmpty()) return null

    GERMAN_UNITS[word]?.let { return it }
    GERMAN_TENS[word]?.let { return it }

    var total = 0
    // Hundreds first: "zweihundert…", "hundert…"
    val hundredIndex = word.indexOf("hundert")
    if (hundredIndex >= 0) {
        val prefix = word.substring(0, hundredIndex)
        val multiplier = if (prefix.isEmpty()) 1 else GERMAN_UNITS[prefix] ?: return null
        total += multiplier * 100
        word = word.substring(hundredIndex + "hundert".length)
        if (word.isEmpty()) return total
    }

    GERMAN_UNITS[word]?.let { return total + it }
    GERMAN_TENS[word]?.let { return total + it }

    // "fünfundvierzig" = 5 + 40
    val undIndex = word.indexOf("und")
    if (undIndex > 0) {
        val unit = GERMAN_UNITS[word.substring(0, undIndex)]
        val ten = GERMAN_TENS[word.substring(undIndex + 3)]
        if (unit != null && ten != null) return total + ten + unit
    }

    return if (total > 0) total else null
}

private fun parseEnglishSequence(words: List<String>): Int? {
    var total = 0
    var current = 0
    var matched = false

    for (word in words) {
        val normalised = word.lowercase(Locale.ENGLISH).removeSuffix(",")
        when {
            ENGLISH_UNITS.containsKey(normalised) -> { current += ENGLISH_UNITS.getValue(normalised); matched = true }
            ENGLISH_TENS.containsKey(normalised) -> { current += ENGLISH_TENS.getValue(normalised); matched = true }
            normalised == "hundred" -> { current = (if (current == 0) 1 else current) * 100; matched = true }
            else -> return null
        }
    }
    return if (matched) total + current else null
}

/**
 * Replaces spelled-out numbers with digits so the rest of the pipeline only
 * ever deals with numerals: "hundert Kilo für zehn" → "100 Kilo für 10".
 */
fun normalizeNumberWords(text: String): String {
    val tokens = text.split(" ")
    val out = mutableListOf<String>()
    var index = 0

    while (index < tokens.size) {
        // Try the longest English run first ("one hundred twenty").
        var consumed = 0
        var value: Int? = null
        for (length in minOf(4, tokens.size - index) downTo 1) {
            val slice = tokens.subList(index, index + length)
            if (slice.any { it.isBlank() }) continue
            val parsed = parseEnglishSequence(slice.map { it.trim(',', '.', '!', '?') })
            if (parsed != null) {
                value = parsed
                consumed = length
                break
            }
        }

        if (value != null && consumed > 0) {
            val trailing = tokens[index + consumed - 1].takeLastWhile { it in ",.!?" }
            out.add("$value$trailing")
            index += consumed
            continue
        }

        val token = tokens[index]
        val core = token.trim(',', '.', '!', '?', ';', ':')
        val trailing = token.removePrefix(core)
        // Hyphenated English ("twenty-five") arrives as one token.
        val hyphenParts = core.split("-")
        val hyphenValue = if (hyphenParts.size > 1) parseEnglishSequence(hyphenParts) else null
        val german = hyphenValue ?: parseGermanNumberWord(core)

        if (german != null && core.isNotEmpty() && core.any { it.isLetter() }) {
            out.add("$german$trailing")
        } else {
            out.add(token)
        }
        index += 1
    }

    return out.joinToString(" ")
}

// ---------------------------------------------------------------------------
// Dates
// ---------------------------------------------------------------------------

data class DateResolution(
    val date: LocalDate?,
    val confidence: Double,
    val ambiguous: Boolean = false,
    val reason: String? = null,
)

/**
 * Luxon-style weekday numbers, Monday = 1.
 *
 * Two letter abbreviations are deliberately absent: "so", "mi" and "do" are
 * ordinary German words and would turn "irgendwann mal so" into a Sunday.
 */
private val WEEKDAYS = mapOf(
    "montag" to 1, "monday" to 1, "mon" to 1,
    "dienstag" to 2, "tuesday" to 2, "tue" to 2,
    "mittwoch" to 3, "wednesday" to 3, "wed" to 3,
    "donnerstag" to 4, "thursday" to 4, "thu" to 4,
    "freitag" to 5, "friday" to 5, "fri" to 5,
    "samstag" to 6, "sonnabend" to 6, "saturday" to 6, "sat" to 6,
    "sonntag" to 7, "sunday" to 7, "sun" to 7,
)

private val MONTHS = mapOf(
    "januar" to 1, "january" to 1, "jan" to 1,
    "februar" to 2, "february" to 2, "feb" to 2,
    "maerz" to 3, "march" to 3, "mar" to 3,
    "april" to 4, "apr" to 4,
    "mai" to 5, "may" to 5,
    "juni" to 6, "june" to 6, "jun" to 6,
    "juli" to 7, "july" to 7, "jul" to 7,
    "august" to 8, "aug" to 8,
    "september" to 9, "sep" to 9, "sept" to 9,
    "oktober" to 10, "october" to 10, "okt" to 10, "oct" to 10,
    "november" to 11, "nov" to 11,
    "dezember" to 12, "december" to 12, "dez" to 12, "dec" to 12,
)

/** Ranges we refuse to guess at rather than pick an arbitrary day. */
private val VAGUE = listOf(
    "letzte woche", "vorige woche", "letzten monat", "letzte tage", "neulich",
    "irgendwann", "kuerzlich", "last week", "last month", "recently", "the other day",
)

/**
 * Turns what the user said into a calendar day.
 *
 * Returns `ambiguous` instead of a date when the expression names a range
 * rather than a day — the caller then asks back instead of storing a guess.
 */
fun resolveDateExpression(expression: String?, today: LocalDate = LocalDate.now()): DateResolution {
    if (expression.isNullOrBlank()) return DateResolution(today, 0.9)

    val text = normalizeName(expression)
    if (text.isBlank()) return DateResolution(today, 0.9)

    if (VAGUE.any { text.contains(it) }) {
        return DateResolution(null, 0.0, ambiguous = true, reason = "ambiguous_range")
    }

    when {
        text.contains("vorgestern") -> return DateResolution(today.minusDays(2), 0.95)
        text.contains("gestern") || text.contains("yesterday") -> return DateResolution(today.minusDays(1), 0.97)
        text.contains("heute") || text.contains("today") -> return DateResolution(today, 0.99)
    }

    // "vor 3 Tagen" / "3 days ago"
    Regex("vor (\\d+) tag").find(text)?.let { match ->
        return DateResolution(today.minusDays(match.groupValues[1].toLong()), 0.9)
    }
    Regex("(\\d+) days? ago").find(text)?.let { match ->
        return DateResolution(today.minusDays(match.groupValues[1].toLong()), 0.9)
    }

    // ISO or numeric dates
    Regex("(\\d{4}) (\\d{1,2}) (\\d{1,2})").find(text)?.let { match ->
        val (y, m, d) = match.destructured
        runCatching { return DateResolution(LocalDate.of(y.toInt(), m.toInt(), d.toInt()), 0.95) }
    }
    Regex("(\\d{1,2}) (\\d{1,2}) (\\d{4})").find(text)?.let { match ->
        val (d, m, y) = match.destructured
        runCatching { return DateResolution(LocalDate.of(y.toInt(), m.toInt(), d.toInt()), 0.95) }
    }

    // "am 5. August" / "August 5"
    for ((monthName, monthNumber) in MONTHS) {
        if (!text.contains(monthName)) continue
        val day = Regex("(\\d{1,2})").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
        val candidate = runCatching { LocalDate.of(today.year, monthNumber, day) }.getOrNull() ?: continue
        // A date in the future without a year means the one that already passed.
        val resolved = if (candidate.isAfter(today)) candidate.minusYears(1) else candidate
        return DateResolution(resolved, 0.9)
    }

    // Weekdays always look backwards: you log training you already did.
    for ((word, weekday) in WEEKDAYS) {
        if (!Regex("\\b$word\\b").containsMatchIn(text)) continue
        val forceLastWeek = text.contains("letzt") || text.contains("vorig") || text.contains("last")
        var delta = today.dayOfWeek.value - weekday
        if (delta < 0) delta += 7
        if (delta == 0 && forceLastWeek) delta = 7
        return DateResolution(today.minusDays(delta.toLong()), if (forceLastWeek) 0.85 else 0.8)
    }

    return DateResolution(null, 0.0, ambiguous = true, reason = "unparsed")
}

/** German weekday + date, for the confirmation text. */
fun formatDateDe(date: LocalDate): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMANY)
    val month = date.month.getDisplayName(TextStyle.FULL, Locale.GERMANY)
    return "$weekday, ${date.dayOfMonth}. $month"
}
