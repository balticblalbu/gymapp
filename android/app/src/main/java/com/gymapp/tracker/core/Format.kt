package com.gymapp.tracker.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** German formatting helpers – the whole UI is German. */
object Fmt {
    private val de = Locale.GERMANY
    private val dayMonth = DateTimeFormatter.ofPattern("d. MMMM", de)
    private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEEE, d. MMMM", de)
    private val shortDate = DateTimeFormatter.ofPattern("dd.MM.yy", de)

    fun number(value: Double, decimals: Int = 0): String =
        String.format(de, "%,.${decimals}f", value)

    fun weight(kg: Double?, useLb: Boolean = false): String = when {
        kg == null -> "–"
        useLb -> "${number(kg * 2.2046226218, if ((kg * 2.2046226218) % 1.0 == 0.0) 0 else 1)} lb"
        kg % 1.0 == 0.0 -> "${number(kg)} kg"
        else -> "${number(kg, 1)} kg"
    }

    /** Large volumes read better in tonnes: 12.430 kg -> 12,4 t */
    fun volume(kg: Double): String = if (kg >= 10_000) "${number(kg / 1000, 1)} t" else "${number(kg)} kg"

    fun percent(value: Double?): String {
        if (value == null) return "–"
        val sign = if (value >= 0) "+" else ""
        return "$sign${number(value, 1)} %"
    }

    fun duration(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return "–"
        val minutes = seconds / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun timer(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(de, "%d:%02d:%02d", h, m, s) else String.format(de, "%02d:%02d", m, s)
    }

    fun distance(meters: Double?): String = when {
        meters == null || meters <= 0 -> "–"
        meters >= 1000 -> "${number(meters / 1000, 2)} km"
        else -> "${number(meters)} m"
    }

    private fun parse(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

    fun dayLabel(iso: String): String = parse(iso)?.format(dayMonth) ?: iso
    fun fullDayLabel(iso: String): String = parse(iso)?.format(weekdayDayMonth) ?: iso
    fun shortDay(iso: String): String = parse(iso)?.format(shortDate) ?: iso

    /** "Heute", "Gestern" or the date – used in history headers. */
    fun relativeDay(iso: String): String {
        val date = parse(iso) ?: return iso
        val today = LocalDate.now()
        return when (date) {
            today -> "Heute"
            today.minusDays(1) -> "Gestern"
            else -> date.format(dayMonth)
        }
    }
}

/** German labels for the muscle group keys used by the backend. */
val MuscleGroupLabels = mapOf(
    "chest" to "Brust",
    "back" to "Rücken",
    "shoulders" to "Schultern",
    "arms" to "Arme",
    "biceps" to "Bizeps",
    "triceps" to "Trizeps",
    "forearms" to "Unterarme",
    "legs" to "Beine",
    "quadriceps" to "Quadrizeps",
    "hamstrings" to "Beinbeuger",
    "glutes" to "Gesäß",
    "calves" to "Waden",
    "core" to "Rumpf",
    "cardio" to "Ausdauer",
)

fun muscleLabel(key: String): String = MuscleGroupLabels[key] ?: key.replaceFirstChar { it.uppercase() }

val ExerciseTypeLabels = mapOf(
    "STRENGTH" to "Kraft",
    "BODYWEIGHT" to "Körpergewicht",
    "CARDIO" to "Ausdauer",
    "DURATION" to "Zeit",
)

val RecordTypeLabels = mapOf(
    "MAX_WEIGHT" to "Höchstes Gewicht",
    "MAX_REPS" to "Meiste Wiederholungen",
    "MAX_VOLUME_SET" to "Bestes Satz-Volumen",
    "MAX_VOLUME_SESSION" to "Bestes Trainings-Volumen",
    "BEST_E1RM" to "Bestes geschätztes 1RM",
    "LONGEST_DURATION" to "Längste Dauer",
    "LONGEST_DISTANCE" to "Größte Distanz",
)

/** Formats a record value in the unit that belongs to its type. */
fun formatRecordValue(type: String, value: Double): String = when (type) {
    "MAX_REPS" -> "${Fmt.number(value)} Wdh"
    "LONGEST_DURATION" -> Fmt.duration(value.toInt())
    "LONGEST_DISTANCE" -> Fmt.distance(value)
    else -> Fmt.weight(value)
}

val Periods = listOf(
    "7d" to "7 T",
    "30d" to "30 T",
    "90d" to "3 M",
    "6m" to "6 M",
    "1y" to "1 J",
    "all" to "Alles",
)
