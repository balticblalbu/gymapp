package com.gymapp.tracker.data.local

import com.gymapp.tracker.core.ai.normalizeName
import java.util.UUID

/**
 * Muscle groups and the starter exercise catalogue — the Kotlin port of
 * `backend/prisma/seed.ts`.
 *
 * The German names and the alias lists are what make voice input work: the
 * matcher compares the recognised text against name, nameDe and every alias,
 * so "Bankdrücken", "Bank drücken" and "Benchpress" all land on Bench Press.
 */

data class MuscleGroup(
    val key: String,
    val nameDe: String,
    val nameEn: String,
    /** Sub-groups roll up for the dashboard: biceps → arms, quads → legs. */
    val parentKey: String? = null,
)

val MUSCLE_GROUPS: List<MuscleGroup> = listOf(
    MuscleGroup("chest", "Brust", "Chest"),
    MuscleGroup("back", "Rücken", "Back"),
    MuscleGroup("shoulders", "Schultern", "Shoulders"),
    MuscleGroup("arms", "Arme", "Arms"),
    MuscleGroup("biceps", "Bizeps", "Biceps", "arms"),
    MuscleGroup("triceps", "Trizeps", "Triceps", "arms"),
    MuscleGroup("forearms", "Unterarme", "Forearms", "arms"),
    MuscleGroup("legs", "Beine", "Legs"),
    MuscleGroup("quadriceps", "Quadrizeps", "Quadriceps", "legs"),
    MuscleGroup("hamstrings", "Beinbeuger", "Hamstrings", "legs"),
    MuscleGroup("glutes", "Gesäß", "Glutes", "legs"),
    MuscleGroup("calves", "Waden", "Calves", "legs"),
    MuscleGroup("core", "Rumpf", "Core"),
    MuscleGroup("cardio", "Ausdauer", "Cardio"),
)

private val byKey = MUSCLE_GROUPS.associateBy { it.key }

fun muscleGroup(key: String): MuscleGroup? = byKey[key]

fun muscleLabelDe(key: String): String = byKey[key]?.nameDe ?: key.replaceFirstChar { it.uppercase() }

/** Top level groups only — what the filters and the dashboard show. */
val TOP_LEVEL_GROUPS: List<MuscleGroup> = MUSCLE_GROUPS.filter { it.parentKey == null }

/** Rolls a specific group up to its parent, e.g. biceps → arms. */
fun rollUp(key: String): String = byKey[key]?.parentKey ?: key

private data class Seed(
    val name: String,
    val nameDe: String,
    val primary: List<String>,
    val secondary: List<String> = emptyList(),
    val type: String = "STRENGTH",
    val equipment: String? = null,
    val aliases: List<String> = emptyList(),
)

private val CATALOGUE = listOf(
    // --- Brust --------------------------------------------------------------
    Seed("Bench Press", "Bankdrücken", listOf("chest"), listOf("triceps", "shoulders"), equipment = "Langhantel",
        aliases = listOf("bankdruecken", "bank druecken", "benchpress", "bench", "flachbankdruecken")),
    Seed("Incline Bench Press", "Schrägbankdrücken", listOf("chest"), listOf("shoulders", "triceps"), equipment = "Langhantel",
        aliases = listOf("schraegbankdruecken", "schraegbank", "incline bench", "incline press")),
    Seed("Dumbbell Bench Press", "Kurzhantel-Bankdrücken", listOf("chest"), listOf("triceps", "shoulders"), equipment = "Kurzhantel",
        aliases = listOf("kurzhantel bankdruecken", "kh bankdruecken", "dumbbell press")),
    Seed("Cable Fly", "Kabelzug-Fliegende", listOf("chest"), equipment = "Kabelzug",
        aliases = listOf("cable fly", "cable flys", "fliegende", "kabelzug fliegende", "cable crossover")),
    Seed("Pec Deck", "Butterfly", listOf("chest"), equipment = "Maschine",
        aliases = listOf("pec deck", "butterfly", "brustmaschine")),
    Seed("Push Up", "Liegestütze", listOf("chest"), listOf("triceps", "core"), type = "BODYWEIGHT",
        aliases = listOf("liegestuetze", "liegestuetz", "pushup", "push ups")),

    // --- Rücken -------------------------------------------------------------
    Seed("Lat Pulldown", "Latzug", listOf("back"), listOf("biceps"), equipment = "Kabelzug",
        aliases = listOf("latzug", "lat zug", "pulldown", "latziehen")),
    Seed("Pull Up", "Klimmzug", listOf("back"), listOf("biceps"), type = "BODYWEIGHT",
        aliases = listOf("klimmzug", "klimmzuege", "pullup", "pull ups", "chin up")),
    Seed("Barbell Row", "Langhantelrudern", listOf("back"), listOf("biceps"), equipment = "Langhantel",
        aliases = listOf("langhantelrudern", "langhantel rudern", "rudern", "barbell row", "vorgebeugtes rudern")),
    Seed("Cable Row", "Kabelrudern", listOf("back"), listOf("biceps"), equipment = "Kabelzug",
        aliases = listOf("kabelrudern", "seated row", "sitzendes rudern", "ruderzug")),
    Seed("T-Bar Row", "T-Bar-Rudern", listOf("back"), listOf("biceps"), equipment = "T-Bar",
        aliases = listOf("t bar rudern", "tbar row", "t bar")),
    Seed("Deadlift", "Kreuzheben", listOf("back"), listOf("hamstrings", "glutes"), equipment = "Langhantel",
        aliases = listOf("kreuzheben", "deadlift", "deadlifts")),

    // --- Schultern ----------------------------------------------------------
    Seed("Shoulder Press", "Schulterdrücken", listOf("shoulders"), listOf("triceps"), equipment = "Kurzhantel",
        aliases = listOf("schulterdruecken", "schulter druecken", "overhead press", "ohp", "military press", "schulterpresse")),
    Seed("Lateral Raise", "Seitheben", listOf("shoulders"), equipment = "Kurzhantel",
        aliases = listOf("seitheben", "seit heben", "lateral raise", "seitliches heben", "cable lateral raise")),
    Seed("Front Raise", "Frontheben", listOf("shoulders"), equipment = "Kurzhantel",
        aliases = listOf("frontheben", "front raise")),
    Seed("Rear Delt Fly", "Reverse Butterfly", listOf("shoulders"), listOf("back"), equipment = "Maschine",
        aliases = listOf("reverse butterfly", "rear delt fly", "reverse fly", "vorgebeugtes seitheben")),

    // --- Arme ---------------------------------------------------------------
    Seed("Biceps Curl", "Bizepscurl", listOf("biceps"), equipment = "Kurzhantel",
        aliases = listOf("bizepscurl", "bizeps curl", "bizeps curls", "curls", "curl", "langhantelcurl", "sz curl")),
    Seed("Hammer Curl", "Hammercurl", listOf("biceps"), listOf("forearms"), equipment = "Kurzhantel",
        aliases = listOf("hammercurl", "hammer curl", "hammer curls")),
    Seed("Triceps Pushdown", "Trizepsdrücken am Kabel", listOf("triceps"), equipment = "Kabelzug",
        aliases = listOf("trizepsdruecken", "trizeps druecken", "pushdown", "pushdowns", "trizeps kabel")),
    Seed("Skull Crusher", "Stirndrücken", listOf("triceps"), equipment = "SZ-Stange",
        aliases = listOf("stirndruecken", "skull crusher", "french press")),
    Seed("Dips", "Dips", listOf("triceps"), listOf("chest"), type = "BODYWEIGHT",
        aliases = listOf("dips", "dip", "barrendips")),

    // --- Beine --------------------------------------------------------------
    Seed("Squat", "Kniebeuge", listOf("quadriceps"), listOf("glutes", "hamstrings"), equipment = "Langhantel",
        aliases = listOf("kniebeuge", "kniebeugen", "squat", "squats", "back squat", "beugen")),
    Seed("Leg Press", "Beinpresse", listOf("quadriceps"), listOf("glutes"), equipment = "Maschine",
        aliases = listOf("beinpresse", "bein presse", "leg press")),
    Seed("Leg Extension", "Beinstrecker", listOf("quadriceps"), equipment = "Maschine",
        aliases = listOf("beinstrecker", "bein strecker", "leg extension")),
    Seed("Leg Curl", "Beinbeuger", listOf("hamstrings"), equipment = "Maschine",
        aliases = listOf("beinbeuger", "bein beuger", "leg curl")),
    Seed("Romanian Deadlift", "Rumänisches Kreuzheben", listOf("hamstrings"), listOf("glutes", "back"), equipment = "Langhantel",
        aliases = listOf("rumaenisches kreuzheben", "romanian deadlift", "rdl", "gestrecktes kreuzheben")),
    Seed("Calf Raise", "Wadenheben", listOf("calves"), equipment = "Maschine",
        aliases = listOf("wadenheben", "waden heben", "calf raise", "wadenmaschine")),
    Seed("Hip Thrust", "Hip Thrust", listOf("glutes"), listOf("hamstrings"), equipment = "Langhantel",
        aliases = listOf("hip thrust", "hipthrust", "hueftheben")),
    Seed("Lunge", "Ausfallschritt", listOf("quadriceps"), listOf("glutes"), equipment = "Kurzhantel",
        aliases = listOf("ausfallschritt", "ausfallschritte", "lunge", "lunges")),

    // --- Rumpf --------------------------------------------------------------
    Seed("Crunch", "Crunch", listOf("core"), type = "BODYWEIGHT",
        aliases = listOf("crunch", "crunches", "bauchpresse", "sit up", "situps")),
    Seed("Leg Raise", "Beinheben", listOf("core"), type = "BODYWEIGHT",
        aliases = listOf("beinheben", "bein heben", "leg raise", "hanging leg raise")),
    Seed("Plank", "Unterarmstütz", listOf("core"), type = "DURATION",
        aliases = listOf("plank", "planks", "unterarmstuetz", "brett")),

    // --- Ausdauer -----------------------------------------------------------
    Seed("Treadmill", "Laufband", listOf("cardio"), type = "CARDIO",
        aliases = listOf("laufband", "treadmill", "laufen", "joggen", "running")),
    Seed("Rowing Machine", "Rudergerät", listOf("cardio"), type = "CARDIO",
        aliases = listOf("rudergeraet", "rudermaschine", "rowing machine", "ergometer rudern")),
    Seed("Cycling", "Fahrradergometer", listOf("cardio"), type = "CARDIO",
        aliases = listOf("fahrrad", "fahrradergometer", "ergometer", "spinning", "radfahren")),
)

/** Builds the starter catalogue. Called once, when the database is empty. */
fun seedExercises(): List<ExerciseEntity> = CATALOGUE.map { seed ->
    val aliases = buildSet {
        add(normalizeName(seed.name))
        add(normalizeName(seed.nameDe))
        seed.aliases.forEach { add(normalizeName(it)) }
    }.filter { it.isNotBlank() }

    ExerciseEntity(
        id = UUID.randomUUID().toString(),
        name = seed.name,
        nameDe = seed.nameDe,
        type = seed.type,
        equipment = seed.equipment,
        notes = null,
        muscleGroups = seed.primary.joinToString(","),
        secondaryGroups = seed.secondary.joinToString(","),
        aliases = aliases.joinToString("|"),
        isCustom = false,
    )
}
