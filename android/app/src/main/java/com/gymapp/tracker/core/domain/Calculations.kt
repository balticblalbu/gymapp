package com.gymapp.tracker.core.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Training mathematics — the Kotlin port of `backend/src/domain/calculations.ts`.
 *
 * The formulas must stay identical to the backend's: if the two ever disagree,
 * the same workout shows different numbers depending on where it was computed.
 * `docs/CALCULATIONS.md` documents the reasoning behind each one.
 */

/** Minimal view of a set — everything the maths needs, nothing else. */
data class SetValues(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val isWarmup: Boolean = false,
    val isOneRmTest: Boolean = false,
)

data class SetSummary(
    val sets: Int = 0,
    val reps: Int = 0,
    val volumeKg: Double = 0.0,
    val maxWeightKg: Double? = null,
    val durationSec: Int = 0,
    val distanceM: Double = 0.0,
)

data class TrendResult(
    val changePercent: Double? = null,
    /** False when there was too little data to say anything meaningful. */
    val reliable: Boolean = false,
    val previousMedian: Double? = null,
    val currentMedian: Double? = null,
)

data class BestE1rm(val value: Double, val set: SetValues?)

/** Volume of a single set. Only counts when both weight and reps are present. */
fun setVolume(set: SetValues): Double {
    val weight = set.weightKg ?: return 0.0
    val reps = set.reps ?: return 0.0
    if (weight <= 0 || reps <= 0) return 0.0
    return weight * reps
}

/** Aggregates a list of sets. Warm-up sets can be excluded from the totals. */
fun summarizeSets(sets: List<SetValues>, includeWarmups: Boolean = true): SetSummary {
    val relevant = if (includeWarmups) sets else sets.filterNot { it.isWarmup }
    var volume = 0.0
    var reps = 0
    var duration = 0
    var distance = 0.0
    var maxWeight: Double? = null

    for (set in relevant) {
        volume += setVolume(set)
        reps += set.reps?.takeIf { it > 0 } ?: 0
        duration += set.durationSec?.takeIf { it > 0 } ?: 0
        distance += set.distanceM?.takeIf { it > 0 } ?: 0.0
        val weight = set.weightKg
        if (weight != null && weight > 0 && (maxWeight == null || weight > maxWeight!!)) maxWeight = weight
    }

    return SetSummary(
        sets = relevant.size,
        reps = reps,
        volumeKg = round2(volume),
        maxWeightKg = maxWeight,
        durationSec = duration,
        distanceM = round2(distance),
    )
}

/** Reps above this are capped — Epley overestimates badly in high rep ranges. */
const val E1RM_REP_CAP = 12

/**
 * Estimated one-rep max, Epley: weight × (1 + reps / 30).
 * Returns 0 for input that cannot produce a meaningful estimate.
 */
fun estimateOneRepMax(weightKg: Double?, reps: Int?): Double {
    if (weightKg == null || reps == null) return 0.0
    if (weightKg <= 0 || reps <= 0) return 0.0
    if (reps == 1) return round2(weightKg)
    val capped = minOf(reps, E1RM_REP_CAP)
    return round2(weightKg * (1 + capped / 30.0))
}

/** True when the rep count was above the cap, so the estimate is extrapolated. */
fun isE1rmExtrapolated(reps: Int?): Boolean = (reps ?: 0) > E1RM_REP_CAP

/** Best estimated 1RM across a list of sets, with the set that produced it. */
fun bestE1rm(sets: List<SetValues>): BestE1rm {
    var best = 0.0
    var bestSet: SetValues? = null
    for (set in sets) {
        if (set.isWarmup) continue
        val value = estimateOneRepMax(set.weightKg, set.reps)
        if (value > best) {
            best = value
            bestSet = set
        }
    }
    return BestE1rm(best, bestSet)
}

/**
 * One comparable performance number per set, so a exercise can be compared with
 * itself over time regardless of how it is measured.
 */
fun performanceMetric(set: SetValues): Double? = when {
    set.weightKg != null && set.weightKg > 0 && (set.reps ?: 0) > 0 -> estimateOneRepMax(set.weightKg, set.reps)
    (set.reps ?: 0) > 0 -> set.reps!!.toDouble()
    (set.distanceM ?: 0.0) > 0 && (set.durationSec ?: 0) > 0 -> set.distanceM!! / set.durationSec!!
    (set.distanceM ?: 0.0) > 0 -> set.distanceM
    (set.durationSec ?: 0) > 0 -> set.durationSec!!.toDouble()
    else -> null
}

// --- statistics helpers ----------------------------------------------------

fun mean(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else round2(values.sum() / values.size)

fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return round2(
        if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle],
    )
}

/** Simple trailing moving average; the first entries average what exists so far. */
fun movingAverage(values: List<Double>, window: Int): List<Double> {
    if (window <= 1) return values
    return values.indices.map { index ->
        val from = maxOf(0, index - window + 1)
        round2(values.subList(from, index + 1).average())
    }
}

/** Percentage change, or null when there is no sensible denominator. */
fun percentChange(previous: Double?, current: Double?): Double? {
    if (previous == null || current == null) return null
    if (previous == 0.0) return null
    return round1(((current - previous) / abs(previous)) * 100)
}

/**
 * Trend between two periods, resistant to single outliers.
 *
 * Uses the median rather than the mean and requires at least two data points on
 * each side; otherwise one record set would read as lasting progress.
 */
fun robustTrend(previous: List<Double>, current: List<Double>, minSamples: Int = 2): TrendResult {
    if (previous.size < minSamples || current.size < minSamples) {
        return TrendResult(changePercent = null, reliable = false)
    }
    val previousMedian = median(previous)
    val currentMedian = median(current)
    return TrendResult(
        changePercent = percentChange(previousMedian, currentMedian),
        reliable = true,
        previousMedian = previousMedian,
        currentMedian = currentMedian,
    )
}

/** Consecutive sets with identical values, for the "100 kg × 10 × 3" summary. */
data class SetGroup(val set: SetValues, val count: Int)

fun groupSets(sets: List<SetValues>): List<SetGroup> {
    val groups = mutableListOf<SetGroup>()
    for (set in sets) {
        val last = groups.lastOrNull()
        if (last != null && sameShape(last.set, set)) {
            groups[groups.lastIndex] = last.copy(count = last.count + 1)
        } else {
            groups.add(SetGroup(set, 1))
        }
    }
    return groups
}

private fun sameShape(a: SetValues, b: SetValues): Boolean =
    a.weightKg == b.weightKg &&
        a.reps == b.reps &&
        a.durationSec == b.durationSec &&
        a.distanceM == b.distanceM

internal fun round1(value: Double): Double = (value * 10).roundToInt() / 10.0
internal fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0
