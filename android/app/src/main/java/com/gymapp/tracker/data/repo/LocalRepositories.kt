package com.gymapp.tracker.data.repo

import com.gymapp.tracker.core.ai.DateResolution
import com.gymapp.tracker.core.ai.ExerciseCandidate
import com.gymapp.tracker.core.ai.MatchDecision
import com.gymapp.tracker.core.ai.decideMatch
import com.gymapp.tracker.core.ai.normalizeName
import com.gymapp.tracker.core.ai.resolveDateExpression
import com.gymapp.tracker.core.domain.*
import com.gymapp.tracker.data.ai.ClaudeClient
import com.gymapp.tracker.data.ai.ParsedMessage
import com.gymapp.tracker.data.local.*
import com.gymapp.tracker.data.prefs.TokenStore
import com.gymapp.tracker.data.remote.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The whole data layer, backed by Room.
 *
 * Deliberately keeps the DTO types the UI already speaks: only the source of
 * the data changed, so every screen, ViewModel and chart carries over
 * unmodified. `Cached.fromCache` is always false now — nothing can be stale
 * when the database is the origin.
 */

data class Cached<T>(val value: T, val fromCache: Boolean = false)

private fun newId(): String = UUID.randomUUID().toString()

private fun ExerciseEntity.primaryKeys(): List<String> =
    muscleGroups.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun ExerciseEntity.secondaryKeys(): List<String> =
    secondaryGroups.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun ExerciseEntity.toDto(): ExerciseDto = ExerciseDto(
    id = id,
    name = name,
    nameDe = nameDe,
    type = type,
    equipment = equipment,
    notes = notes,
    isCustom = isCustom,
    isGlobal = !isCustom,
    muscleGroups = primaryKeys().map { key ->
        MuscleGroupRefDto(key = key, nameDe = muscleLabelDe(key), role = "PRIMARY")
    } + secondaryKeys().map { key ->
        MuscleGroupRefDto(key = key, nameDe = muscleLabelDe(key), role = "SECONDARY", contribution = 0.4f)
    },
    aliases = aliases.split("|").filter { it.isNotBlank() },
)

private fun SetRow.toValues(): SetValues = SetValues(
    weightKg = weightKg,
    reps = reps,
    durationSec = durationSec,
    distanceM = distanceM,
    isWarmup = isWarmup,
    isOneRmTest = isOneRmTest,
)

private fun SetRow.toDto(): SetDto = SetDto(
    id = setId,
    setNumber = setNumber,
    weightKg = weightKg,
    reps = reps,
    durationSec = durationSec,
    distanceM = distanceM,
    isWarmup = isWarmup,
    isOneRmTest = isOneRmTest,
    notes = notes,
    source = source,
    confidence = confidence,
)

/** Inclusive start of a period, or null for "all". */
private fun periodStart(period: String, today: LocalDate): LocalDate? = when (period) {
    "7d" -> today.minusDays(6)
    "30d" -> today.minusDays(29)
    "90d" -> today.minusDays(89)
    "6m" -> today.minusMonths(6).plusDays(1)
    "1y" -> today.minusYears(1).plusDays(1)
    else -> null
}

private fun parseDate(iso: String): LocalDate = runCatching { LocalDate.parse(iso) }.getOrDefault(LocalDate.now())

// ---------------------------------------------------------------------------
// Exercises
// ---------------------------------------------------------------------------

class ExerciseRepository(private val db: AppDatabase) {

    fun observeCached(): Flow<List<ExerciseDto>> =
        db.exerciseDao().observeAll().map { rows -> rows.map { it.toDto() } }

    /** Kept for API compatibility with the screens; reads straight from Room. */
    suspend fun refresh(search: String? = null, muscleGroup: String? = null): Cached<List<ExerciseDto>> {
        val needle = search?.trim()?.lowercase().orEmpty()
        val filtered = db.exerciseDao().all().filter { entity ->
            val matchesSearch = needle.isEmpty() ||
                entity.name.lowercase().contains(needle) ||
                entity.nameDe?.lowercase()?.contains(needle) == true ||
                entity.aliases.contains(normalizeName(needle))
            val matchesGroup = muscleGroup == null ||
                (entity.primaryKeys() + entity.secondaryKeys()).any { it == muscleGroup || rollUp(it) == muscleGroup }
            matchesSearch && matchesGroup
        }
        return Cached(filtered.map { it.toDto() })
    }

    suspend fun create(request: ExerciseRequest): ExerciseDto {
        val existing = db.exerciseDao().all().firstOrNull {
            normalizeName(it.name) == normalizeName(request.name)
        }
        if (existing != null) throw IllegalStateException("Diese Übung gibt es bereits.")

        val entity = ExerciseEntity(
            id = newId(),
            name = request.name.trim(),
            nameDe = request.nameDe?.trim(),
            type = request.type ?: "STRENGTH",
            equipment = request.equipment?.trim(),
            notes = request.notes,
            muscleGroups = (request.muscleGroupKeys ?: emptyList()).joinToString(","),
            aliases = listOfNotNull(normalizeName(request.name), request.nameDe?.let { normalizeName(it) })
                .filter { it.isNotBlank() }.distinct().joinToString("|"),
            isCustom = true,
        )
        db.exerciseDao().upsert(entity)
        return entity.toDto()
    }

    suspend fun update(id: String, request: ExerciseRequest): ExerciseDto {
        val current = db.exerciseDao().byId(id) ?: throw IllegalStateException("Übung nicht gefunden.")
        val updated = current.copy(
            name = request.name.trim().ifBlank { current.name },
            nameDe = request.nameDe?.trim() ?: current.nameDe,
            type = request.type ?: current.type,
            equipment = request.equipment?.trim() ?: current.equipment,
            notes = request.notes ?: current.notes,
            muscleGroups = request.muscleGroupKeys?.joinToString(",") ?: current.muscleGroups,
            isCustom = true,
            updatedAt = System.currentTimeMillis(),
        )
        db.exerciseDao().upsert(updated)
        return updated.toDto()
    }

    suspend fun delete(id: String) {
        db.exerciseDao().softDelete(id)
        db.recordDao().deleteForExercise(id)
    }

    suspend fun muscleGroups(): List<MuscleGroupDto> = MUSCLE_GROUPS.mapIndexed { index, group ->
        MuscleGroupDto(
            id = group.key,
            key = group.key,
            nameEn = group.nameEn,
            nameDe = group.nameDe,
            parentKey = group.parentKey,
            sortOrder = index,
        )
    }

    suspend fun candidates(): List<ExerciseCandidate> = db.exerciseDao().all().map { entity ->
        ExerciseCandidate(
            id = entity.id,
            name = entity.name,
            nameDe = entity.nameDe,
            aliases = entity.aliases.split("|").filter { it.isNotBlank() },
        )
    }

    suspend fun stats(id: String, period: String): ExerciseStatsResponse {
        val exercise = db.exerciseDao().byId(id) ?: throw IllegalStateException("Übung nicht gefunden.")
        val today = LocalDate.now()
        val from = periodStart(period, today)
        val allRows = db.setDao().rowsForExercise(id).filterNot { it.isWarmup }
        val rows = allRows.filter { from == null || !parseDate(it.date).isBefore(from) }

        val byDate = rows.groupBy { it.date }.toSortedMap()
        val series = byDate.map { (date, dayRows) ->
            val values = dayRows.map { it.toValues() }
            val summary = summarizeSets(values)
            ExerciseStatsPointDto(
                date = date,
                maxWeightKg = summary.maxWeightKg,
                totalVolumeKg = summary.volumeKg,
                bestE1rm = bestE1rm(values).value.takeIf { it > 0 },
                totalReps = summary.reps,
                sets = summary.sets,
                avgWeightKg = values.mapNotNull { it.weightKg }.takeIf { it.isNotEmpty() }?.let { mean(it) },
            )
        }

        val values = rows.map { it.toValues() }
        val bestRepsRow = rows.filter { (it.reps ?: 0) > 0 }.maxByOrNull { it.reps!! }
        val bestSessionVolume = byDate.values.maxOfOrNull { day -> summarizeSets(day.map { it.toValues() }).volumeKg }

        // Progress: median performance now vs. the same span before.
        val progress = if (from != null) {
            val span = ChronoUnit.DAYS.between(from, today)
            val previousFrom = from.minusDays(span + 1)
            val previous = allRows.filter {
                val date = parseDate(it.date)
                !date.isBefore(previousFrom) && date.isBefore(from)
            }.mapNotNull { performanceMetric(it.toValues()) }
            val current = rows.mapNotNull { performanceMetric(it.toValues()) }
            robustTrend(previous, current).changePercent
        } else {
            val half = rows.size / 2
            if (rows.size >= 4) {
                robustTrend(
                    rows.take(half).mapNotNull { performanceMetric(it.toValues()) },
                    rows.drop(half).mapNotNull { performanceMetric(it.toValues()) },
                ).changePercent
            } else null
        }

        val weeks = if (from != null) maxOf(1.0, ChronoUnit.DAYS.between(from, today) / 7.0) else {
            val first = allRows.firstOrNull()?.date?.let { parseDate(it) } ?: today
            maxOf(1.0, ChronoUnit.DAYS.between(first, today) / 7.0)
        }

        val stats = ExerciseStatsDto(
            exerciseId = id,
            name = exercise.nameDe?.takeIf { it.isNotBlank() } ?: exercise.name,
            type = exercise.type,
            period = period,
            personalBestKg = rows.mapNotNull { it.weightKg }.maxOrNull(),
            bestReps = bestRepsRow?.let { BestRepsDto(reps = it.reps!!, weightKg = it.weightKg) },
            bestVolumeKg = bestSessionVolume,
            bestE1rmKg = bestE1rm(values).value.takeIf { it > 0 },
            hasMeasuredOneRm = rows.any { it.isOneRmTest },
            avgWeightKg = rows.mapNotNull { it.weightKg }.takeIf { it.isNotEmpty() }?.let { mean(it) },
            avgReps = rows.mapNotNull { it.reps?.toDouble() }.takeIf { it.isNotEmpty() }?.let { mean(it) },
            totalSets = rows.size,
            totalVolumeKg = summarizeSets(values).volumeKg,
            sessions = byDate.size,
            frequencyPerWeek = round1(byDate.size / weeks),
            progressPercent = progress,
            series = series,
        )

        val records = db.recordDao().forExercise(id).map {
            PersonalRecordDto(
                type = it.type,
                value = it.value,
                previousValue = it.previousValue,
                weightKg = it.weightKg,
                reps = it.reps,
                achievedAt = it.achievedAt,
            )
        }
        return ExerciseStatsResponse(stats = stats, records = records)
    }
}

// ---------------------------------------------------------------------------
// Workouts
// ---------------------------------------------------------------------------

class WorkoutRepository(private val db: AppDatabase) {

    /** Nothing is ever pending now that writes go straight to the database. */
    val pendingCount: Flow<Int> = MutableStateFlow(0)

    fun observeCached(): Flow<List<WorkoutDto>> = db.workoutDao().observeAll().map { emptyList() }

    suspend fun refreshList(
        muscleGroup: String? = null,
        exerciseId: String? = null,
        limit: Int = 60,
    ): Cached<List<WorkoutDto>> {
        val workouts = db.workoutDao().recent(limit).map { buildWorkoutDto(it) }
        val filtered = workouts.filter { workout ->
            val groupOk = muscleGroup == null || workout.exercises.any { ex ->
                ex.muscleGroups.any { it == muscleGroup || rollUp(it) == muscleGroup }
            }
            val exerciseOk = exerciseId == null || workout.exercises.any { it.exerciseId == exerciseId }
            groupOk && exerciseOk
        }
        return Cached(filtered)
    }

    suspend fun detail(id: String): Cached<WorkoutDto> {
        val entity = db.workoutDao().byId(id) ?: throw IllegalStateException("Training nicht gefunden.")
        return Cached(buildWorkoutDto(entity))
    }

    suspend fun activeWorkout(): WorkoutDto? = db.workoutDao().active()?.let { buildWorkoutDto(it) }

    suspend fun start(title: String?): WorkoutDto {
        val today = LocalDate.now().toString()
        val existing = db.workoutDao().byDate(today)
        val entity = existing?.copy(status = "IN_PROGRESS", updatedAt = System.currentTimeMillis())
            ?: WorkoutEntity(
                id = newId(),
                date = today,
                title = title,
                notes = null,
                status = "IN_PROGRESS",
                startedAt = java.time.Instant.now().toString(),
            )
        db.workoutDao().upsert(entity)
        return buildWorkoutDto(entity)
    }

    suspend fun update(id: String, request: UpdateWorkoutRequest): WorkoutDto {
        val current = db.workoutDao().byId(id) ?: throw IllegalStateException("Training nicht gefunden.")
        val updated = current.copy(
            date = request.date ?: current.date,
            title = request.title ?: current.title,
            notes = request.notes ?: current.notes,
            status = request.status ?: current.status,
            endedAt = request.endedAt ?: current.endedAt,
            durationSec = request.durationSec ?: current.durationSec,
            updatedAt = System.currentTimeMillis(),
        )
        db.workoutDao().upsert(updated)
        return buildWorkoutDto(updated)
    }

    suspend fun delete(id: String) {
        val affected = db.setDao().rowsForWorkout(id).map { it.exerciseId }.distinct()
        db.workoutDao().delete(id)
        affected.forEach { recomputeRecords(db, it) }
    }

    suspend fun addExercise(workoutId: String, exerciseId: String): WorkoutDto {
        val existing = db.workoutExerciseDao().find(workoutId, exerciseId)
        if (existing == null) {
            db.workoutExerciseDao().upsert(
                WorkoutExerciseEntity(
                    id = newId(),
                    workoutId = workoutId,
                    exerciseId = exerciseId,
                    position = db.workoutExerciseDao().countFor(workoutId),
                ),
            )
        }
        return detail(workoutId).value
    }

    suspend fun removeExercise(workoutExerciseId: String) {
        val link = db.workoutExerciseDao().byId(workoutExerciseId) ?: return
        db.workoutExerciseDao().delete(workoutExerciseId)
        recomputeRecords(db, link.exerciseId)
    }

    suspend fun addSet(workoutExerciseId: String, request: SetRequest): SetDto {
        val link = db.workoutExerciseDao().byId(workoutExerciseId)
            ?: throw IllegalStateException("Übung nicht gefunden.")
        val entity = WorkoutSetEntity(
            id = newId(),
            workoutExerciseId = workoutExerciseId,
            setNumber = request.setNumber ?: (db.setDao().maxSetNumber(workoutExerciseId) + 1),
            weightKg = request.weightKg,
            reps = request.reps,
            durationSec = request.durationSec,
            distanceM = request.distanceM,
            rpe = request.rpe,
            isWarmup = request.isWarmup ?: false,
            isOneRmTest = request.isOneRmTest ?: false,
            notes = request.notes,
        )
        db.setDao().upsert(entity)
        recomputeRecords(db, link.exerciseId)
        return entity.toDto()
    }

    suspend fun updateSet(setId: String, request: SetRequest): SetDto {
        val current = db.setDao().byId(setId) ?: throw IllegalStateException("Satz nicht gefunden.")
        val updated = current.copy(
            weightKg = request.weightKg ?: current.weightKg,
            reps = request.reps ?: current.reps,
            durationSec = request.durationSec ?: current.durationSec,
            distanceM = request.distanceM ?: current.distanceM,
            rpe = request.rpe ?: current.rpe,
            isWarmup = request.isWarmup ?: current.isWarmup,
            isOneRmTest = request.isOneRmTest ?: current.isOneRmTest,
            notes = request.notes ?: current.notes,
        )
        db.setDao().upsert(updated)
        db.workoutExerciseDao().byId(current.workoutExerciseId)?.let { recomputeRecords(db, it.exerciseId) }
        return updated.toDto()
    }

    suspend fun deleteSet(setId: String) {
        val current = db.setDao().byId(setId) ?: return
        db.setDao().delete(setId)
        db.workoutExerciseDao().byId(current.workoutExerciseId)?.let { recomputeRecords(db, it.exerciseId) }
    }

    private suspend fun buildWorkoutDto(entity: WorkoutEntity): WorkoutDto =
        buildWorkoutDto(db, entity)
}

private fun WorkoutSetEntity.toDto(): SetDto = SetDto(
    id = id,
    setNumber = setNumber,
    weightKg = weightKg,
    reps = reps,
    durationSec = durationSec,
    distanceM = distanceM,
    rpe = rpe,
    isWarmup = isWarmup,
    isOneRmTest = isOneRmTest,
    notes = notes,
    source = source,
    confidence = confidence,
)

private suspend fun buildWorkoutDto(db: AppDatabase, entity: WorkoutEntity): WorkoutDto {
    val rows = db.setDao().rowsForWorkout(entity.id)
    val links = db.workoutExerciseDao().forWorkout(entity.id)
    val exercises = links.mapNotNull { link ->
        val exercise = db.exerciseDao().byId(link.exerciseId) ?: return@mapNotNull null
        val setRows = rows.filter { it.workoutExerciseId == link.id }
        WorkoutExerciseDto(
            id = link.id,
            exerciseId = link.exerciseId,
            name = exercise.name,
            nameDe = exercise.nameDe,
            type = exercise.type,
            position = link.position,
            notes = link.notes,
            muscleGroups = exercise.primaryKeys(),
            volumeKg = summarizeSets(setRows.map { it.toValues() }).volumeKg,
            sets = setRows.map { it.toDto() },
        )
    }
    val summary = summarizeSets(rows.map { it.toValues() })
    return WorkoutDto(
        id = entity.id,
        date = entity.date,
        title = entity.title,
        notes = entity.notes,
        status = entity.status,
        startedAt = entity.startedAt,
        endedAt = entity.endedAt,
        durationSec = entity.durationSec,
        source = entity.source,
        volumeKg = summary.volumeKg,
        totalSets = summary.sets,
        totalReps = summary.reps,
        exercises = exercises,
    )
}

// ---------------------------------------------------------------------------
// Personal records
// ---------------------------------------------------------------------------

/**
 * Recomputes every record for one exercise from scratch.
 *
 * Replaying the whole history rather than appending keeps the record list
 * correct after a set is edited or deleted — the same choice the backend made.
 */
suspend fun recomputeRecords(db: AppDatabase, exerciseId: String) {
    val rows = db.setDao().rowsForExercise(exerciseId).filterNot { it.isWarmup }
    db.recordDao().deleteForExercise(exerciseId)
    if (rows.isEmpty()) return

    val records = mutableListOf<PersonalRecordEntity>()
    var maxWeight = 0.0
    var maxReps = 0
    var maxSetVolume = 0.0
    var bestE1rm = 0.0
    var longestDuration = 0
    var longestDistance = 0.0

    fun add(type: String, value: Double, previous: Double?, row: SetRow) {
        records.add(
            PersonalRecordEntity(
                id = newId(),
                exerciseId = exerciseId,
                type = type,
                value = round2(value),
                previousValue = previous?.takeIf { it > 0 }?.let { round2(it) },
                weightKg = row.weightKg,
                reps = row.reps,
                achievedAt = row.date,
            ),
        )
    }

    for (row in rows) {
        val weight = row.weightKg ?: 0.0
        if (weight > maxWeight) { add("MAX_WEIGHT", weight, maxWeight, row); maxWeight = weight }

        val reps = row.reps ?: 0
        if (reps > maxReps) { add("MAX_REPS", reps.toDouble(), maxReps.toDouble(), row); maxReps = reps }

        val volume = setVolume(row.toValues())
        if (volume > maxSetVolume) { add("MAX_VOLUME_SET", volume, maxSetVolume, row); maxSetVolume = volume }

        val e1rm = estimateOneRepMax(row.weightKg, row.reps)
        if (e1rm > bestE1rm) { add("BEST_E1RM", e1rm, bestE1rm, row); bestE1rm = e1rm }

        val duration = row.durationSec ?: 0
        if (duration > longestDuration) {
            add("LONGEST_DURATION", duration.toDouble(), longestDuration.toDouble(), row); longestDuration = duration
        }

        val distance = row.distanceM ?: 0.0
        if (distance > longestDistance) { add("LONGEST_DISTANCE", distance, longestDistance, row); longestDistance = distance }
    }

    // Best single-session volume, computed per day rather than per set.
    var bestSession = 0.0
    rows.groupBy { it.date }.toSortedMap().forEach { (date, dayRows) ->
        val volume = summarizeSets(dayRows.map { it.toValues() }).volumeKg
        if (volume > bestSession) {
            records.add(
                PersonalRecordEntity(
                    id = newId(),
                    exerciseId = exerciseId,
                    type = "MAX_VOLUME_SESSION",
                    value = round2(volume),
                    previousValue = bestSession.takeIf { it > 0 }?.let { round2(it) },
                    achievedAt = date,
                ),
            )
            bestSession = volume
        }
    }

    // Only the newest record per type is interesting for the UI.
    val newest = records.groupBy { it.type }.mapNotNull { (_, entries) -> entries.maxByOrNull { it.achievedAt } }
    db.recordDao().insert(newest)
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

class StatsRepository(private val db: AppDatabase) {

    suspend fun dashboard(): Cached<DashboardDto> {
        val today = LocalDate.now()
        val rows = db.setDao().allRows()
        val todayIso = today.toString()
        val todayRows = rows.filter { it.date == todayIso }

        val todayExercises = todayRows.groupBy { it.exerciseId }.map { (exerciseId, exerciseRows) ->
            val values = exerciseRows.map { it.toValues() }
            val summary = summarizeSets(values)
            DashboardExerciseDto(
                exerciseId = exerciseId,
                name = exerciseRows.first().exerciseNameDe?.takeIf { it.isNotBlank() }
                    ?: exerciseRows.first().exerciseName,
                muscleGroups = exerciseRows.first().muscleGroups.split(",").filter { it.isNotBlank() },
                summary = describeSets(values),
                volumeKg = summary.volumeKg,
                sets = summary.sets,
            )
        }

        val todaySummary = summarizeSets(todayRows.map { it.toValues() })
        val todayWorkout = db.workoutDao().byDate(todayIso)
        val dates = db.workoutDao().allDates().map { parseDate(it) }

        val last30 = rows.filter { !parseDate(it.date).isBefore(today.minusDays(29)) }
        val totals = summarizeSets(rows.map { it.toValues() })

        Cached(DashboardDto(date = todayIso)) // placeholder to keep the type visible
        return Cached(
            DashboardDto(
                date = todayIso,
                today = DashboardTodayDto(
                    hasWorkout = todayRows.isNotEmpty(),
                    workoutId = todayWorkout?.id,
                    title = todayWorkout?.title,
                    exercises = todayExercises,
                    volumeKg = todaySummary.volumeKg,
                    sets = todaySummary.sets,
                    reps = todaySummary.reps,
                    durationSec = todayWorkout?.durationSec,
                ),
                streakDays = computeStreak(dates, today),
                workoutsThisWeek = dates.count { !it.isBefore(today.minusDays(6)) },
                totals = DashboardTotalsDto(
                    workouts = dates.size,
                    volumeKg = totals.volumeKg,
                    sets = totals.sets,
                ),
                comparisons = buildComparisons(rows, today),
                muscleGroups = muscleGroupProgress(last30, rows, today),
                recentRecords = recentRecords(),
            ),
        )
    }

    suspend fun overview(period: String): Cached<OverviewDto> {
        val today = LocalDate.now()
        val from = periodStart(period, today)
        val allRows = db.setDao().allRows()
        val rows = allRows.filter { from == null || !parseDate(it.date).isBefore(from) }
        val summary = summarizeSets(rows.map { it.toValues() })
        val days = rows.map { it.date }.distinct()
        val weeks = if (from != null) maxOf(1.0, ChronoUnit.DAYS.between(from, today) / 7.0) else {
            val first = allRows.firstOrNull()?.date?.let { parseDate(it) } ?: today
            maxOf(1.0, ChronoUnit.DAYS.between(first, today) / 7.0)
        }

        val groups = muscleGroupProgress(rows, allRows, today)
        val ranked = groups.filter { it.changePercent != null }.sortedByDescending { it.changePercent }

        val volumeSeries = rows.groupBy { it.date }.toSortedMap().map { (date, dayRows) ->
            val daySummary = summarizeSets(dayRows.map { it.toValues() })
            VolumePointDto(date = date, volumeKg = daySummary.volumeKg, sets = daySummary.sets)
        }

        val strength = trendOver(allRows, from, today)

        return Cached(
            OverviewDto(
                period = period,
                from = from?.toString(),
                to = today.toString(),
                workouts = days.size,
                workoutsPerWeek = round1(days.size / weeks),
                volumeKg = summary.volumeKg,
                avgVolumePerWorkout = if (days.isEmpty()) 0.0 else round2(summary.volumeKg / days.size),
                avgWeightKg = rows.mapNotNull { it.weightKg }.takeIf { it.isNotEmpty() }?.let { mean(it) } ?: 0.0,
                avgReps = rows.mapNotNull { it.reps?.toDouble() }.takeIf { it.isNotEmpty() }?.let { mean(it) } ?: 0.0,
                totalSets = summary.sets,
                totalReps = summary.reps,
                durationSec = 0,
                newRecords = db.recordDao().all().count { from == null || !parseDate(it.achievedAt).isBefore(from) },
                strengthTrend = strength,
                volumeTrend = null,
                muscleGroups = groups,
                strongest = ranked.take(3),
                weakest = ranked.takeLast(3).reversed(),
                volumeSeries = volumeSeries,
            ),
        )
    }

    suspend fun calendar(from: String, to: String): List<CalendarDayDto> {
        val start = parseDate(from)
        val end = parseDate(to)
        val rows = db.setDao().allRows().filter {
            val date = parseDate(it.date)
            !date.isBefore(start) && !date.isAfter(end)
        }
        val records = db.recordDao().all()
        return rows.groupBy { it.date }.map { (date, dayRows) ->
            val summary = summarizeSets(dayRows.map { it.toValues() })
            CalendarDayDto(
                date = date,
                workoutId = dayRows.first().workoutId,
                volumeKg = summary.volumeKg,
                sets = summary.sets,
                exercises = dayRows.map { it.exerciseId }.distinct().size,
                muscleGroups = dayRows.flatMap { it.muscleGroups.split(",") }.filter { it.isNotBlank() }.distinct(),
                records = records.count { it.achievedAt == date },
            )
        }.sortedBy { it.date }
    }

    suspend fun weeklyVolume(period: String): List<WeeklyVolumePointDto> {
        val today = LocalDate.now()
        val from = periodStart(period, today) ?: today.minusMonths(3)
        val rows = db.setDao().allRows().filter { !parseDate(it.date).isBefore(from) }
        return rows.groupBy { row ->
            val date = parseDate(row.date)
            date.minusDays((date.dayOfWeek.value - 1).toLong()).toString()
        }.toSortedMap().map { (weekStart, weekRows) ->
            val summary = summarizeSets(weekRows.map { it.toValues() })
            WeeklyVolumePointDto(
                weekStart = weekStart,
                volumeKg = summary.volumeKg,
                sets = summary.sets,
                workouts = weekRows.map { it.date }.distinct().size,
            )
        }
    }

    suspend fun records(): List<RecordListItemDto> {
        val exercises = db.exerciseDao().all().associateBy { it.id }
        return db.recordDao().all().map { record ->
            val exercise = exercises[record.exerciseId]
            RecordListItemDto(
                id = record.id,
                exerciseId = record.exerciseId,
                exerciseName = exercise?.nameDe?.takeIf { it.isNotBlank() } ?: exercise?.name ?: "Übung",
                type = record.type,
                value = record.value,
                previousValue = record.previousValue,
                weightKg = record.weightKg,
                reps = record.reps,
                achievedAt = record.achievedAt,
            )
        }
    }

    private suspend fun recentRecords(): List<RecordSummaryDto> {
        val exercises = db.exerciseDao().all().associateBy { it.id }
        return db.recordDao().all().sortedByDescending { it.achievedAt }.take(5).map { record ->
            val exercise = exercises[record.exerciseId]
            RecordSummaryDto(
                exerciseName = exercise?.nameDe?.takeIf { it.isNotBlank() } ?: exercise?.name ?: "Übung",
                type = record.type,
                value = record.value,
                previousValue = record.previousValue,
                improvementPercent = percentChange(record.previousValue, record.value),
                achievedAt = record.achievedAt,
            )
        }
    }

    private fun buildComparisons(rows: List<SetRow>, today: LocalDate): ComparisonsDto {
        val byDate = rows.groupBy { it.date }.toSortedMap()
        val dates = byDate.keys.toList()
        val todayIso = today.toString()

        val vsLastWorkout = run {
            val currentIndex = dates.indexOf(todayIso)
            val current = byDate[todayIso]
            val previousDate = when {
                currentIndex > 0 -> dates[currentIndex - 1]
                currentIndex == -1 -> dates.lastOrNull()
                else -> null
            }
            if (current == null || previousDate == null) null else percentChange(
                summarizeSets(byDate.getValue(previousDate).map { it.toValues() }).volumeKg,
                summarizeSets(current.map { it.toValues() }).volumeKg,
            )
        }

        fun windowVolume(fromDate: LocalDate, toDate: LocalDate): Double =
            summarizeSets(
                rows.filter {
                    val date = parseDate(it.date)
                    !date.isBefore(fromDate) && !date.isAfter(toDate)
                }.map { it.toValues() },
            ).volumeKg

        val vsLastWeek = percentChange(
            windowVolume(today.minusDays(13), today.minusDays(7)),
            windowVolume(today.minusDays(6), today),
        )
        val vsLastMonth = percentChange(
            windowVolume(today.minusDays(59), today.minusDays(30)),
            windowVolume(today.minusDays(29), today),
        )

        return ComparisonsDto(
            vsLastWorkout = vsLastWorkout,
            vsLastWeek = vsLastWeek,
            vsLastMonth = vsLastMonth,
            strengthTrend = trendOver(rows, today.minusDays(29), today),
        )
    }

    /** Median performance in the window vs. the equally long window before it. */
    private fun trendOver(rows: List<SetRow>, from: LocalDate?, today: LocalDate): Double? {
        if (from == null) return null
        val span = ChronoUnit.DAYS.between(from, today)
        val previousFrom = from.minusDays(span + 1)
        val current = rows.filter { !parseDate(it.date).isBefore(from) }
        val previous = rows.filter {
            val date = parseDate(it.date)
            !date.isBefore(previousFrom) && date.isBefore(from)
        }
        // Compare each exercise with itself, then average — never raw volume
        // across different exercises.
        val perExercise = current.map { it.exerciseId }.distinct().mapNotNull { exerciseId ->
            robustTrend(
                previous.filter { it.exerciseId == exerciseId }.mapNotNull { performanceMetric(it.toValues()) },
                current.filter { it.exerciseId == exerciseId }.mapNotNull { performanceMetric(it.toValues()) },
            ).changePercent
        }
        return if (perExercise.isEmpty()) null else round1(perExercise.average())
    }

    /**
     * Progress per muscle group: each exercise is compared with itself, then
     * the changes are weighted by set count and the exercise's contribution.
     */
    private fun muscleGroupProgress(
        windowRows: List<SetRow>,
        allRows: List<SetRow>,
        today: LocalDate,
    ): List<MuscleGroupProgressDto> {
        if (windowRows.isEmpty()) return emptyList()
        val from = windowRows.minOf { parseDate(it.date) }
        val span = maxOf(1, ChronoUnit.DAYS.between(from, today))
        val previousFrom = from.minusDays(span + 1)
        val previousRows = allRows.filter {
            val date = parseDate(it.date)
            !date.isBefore(previousFrom) && date.isBefore(from)
        }

        data class Bucket(
            var weighted: Double = 0.0,
            var weight: Double = 0.0,
            var volume: Double = 0.0,
            var sets: Int = 0,
            val exercises: MutableSet<String> = mutableSetOf(),
        )

        val buckets = mutableMapOf<String, Bucket>()

        for (exerciseId in windowRows.map { it.exerciseId }.distinct()) {
            val currentSets = windowRows.filter { it.exerciseId == exerciseId }
            val previousSets = previousRows.filter { it.exerciseId == exerciseId }
            val trend = robustTrend(
                previousSets.mapNotNull { performanceMetric(it.toValues()) },
                currentSets.mapNotNull { performanceMetric(it.toValues()) },
            )
            val sample = currentSets.first()
            val contributions = sample.muscleGroups.split(",").filter { it.isNotBlank() }.map { it to 1.0 } +
                sample.secondaryGroups.split(",").filter { it.isNotBlank() }.map { it to 0.4 }

            for ((rawKey, contribution) in contributions) {
                val key = rollUp(rawKey)
                val bucket = buckets.getOrPut(key) { Bucket() }
                val summary = summarizeSets(currentSets.map { it.toValues() })
                bucket.volume += summary.volumeKg * contribution
                bucket.sets += currentSets.size
                bucket.exercises.add(exerciseId)
                if (trend.changePercent != null) {
                    val weight = currentSets.size * contribution
                    bucket.weighted += trend.changePercent!! * weight
                    bucket.weight += weight
                }
            }
        }

        return buckets.entries
            .sortedByDescending { it.value.volume }
            .map { (key, bucket) ->
                MuscleGroupProgressDto(
                    key = key,
                    changePercent = if (bucket.weight > 0) round1(bucket.weighted / bucket.weight) else null,
                    volumeKg = round2(bucket.volume),
                    sets = bucket.sets,
                    exercises = bucket.exercises.size,
                    reliable = bucket.weight > 0,
                )
            }
    }
}

/**
 * Consecutive active days, tolerating up to three rest days.
 * Without that tolerance the streak would almost always read 1.
 */
fun computeStreak(dates: List<LocalDate>, today: LocalDate, toleranceDays: Long = 3): Int {
    if (dates.isEmpty()) return 0
    val sorted = dates.distinct().sortedDescending()
    if (ChronoUnit.DAYS.between(sorted.first(), today) > toleranceDays) return 0

    var streak = 1
    for (index in 1 until sorted.size) {
        val gap = ChronoUnit.DAYS.between(sorted[index], sorted[index - 1])
        if (gap <= toleranceDays) streak += 1 else break
    }
    // Report the span in days, matching what the dashboard label promises.
    return ChronoUnit.DAYS.between(sorted[streak - 1], sorted.first()).toInt() + 1
}

/** "100 kg × 10 × 3" — the compact German set summary. */
fun describeSets(sets: List<SetValues>): String {
    if (sets.isEmpty()) return "–"
    return groupSets(sets).joinToString(", ") { group ->
        val parts = buildList {
            group.set.weightKg?.takeIf { it > 0 }?.let { add(formatKg(it)) }
            group.set.reps?.takeIf { it > 0 }?.let { add("$it") }
            group.set.durationSec?.takeIf { it > 0 && group.set.reps == null }?.let { add("${it / 60} min") }
            group.set.distanceM?.takeIf { it > 0 }?.let { add(formatDistance(it)) }
        }
        val base = if (parts.isEmpty()) "–" else parts.joinToString(" × ")
        if (group.count > 1) "$base × ${group.count}" else base
    }
}

private fun formatKg(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()} kg" else "${round1(value)} kg"

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) "${round2(meters / 1000)} km" else "${meters.roundToInt()} m"

// ---------------------------------------------------------------------------
// AI input
// ---------------------------------------------------------------------------

class AiRepository(
    private val db: AppDatabase,
    private val exercises: ExerciseRepository,
    private val claude: ClaudeClient,
    private val modelName: () -> String,
    private val hasKey: () -> Boolean,
) {
    /**
     * Recognised text → structured training data.
     *
     * The model reports what was said; the date is resolved here and the
     * exercise is matched against the local catalogue, so neither depends on
     * the model getting a lookup right.
     */
    suspend fun parse(text: String, spoken: Boolean): AiParseResponse {
        val today = LocalDate.now()
        val candidates = exercises.candidates()
        val known = candidates.map { it.nameDe ?: it.name }

        val raw: ParsedMessage = claude.parseWorkout(text, known, today)
        // The schema can no longer enforce the 0..1 range, so clamp it here.
        val parsed = raw.copy(confidence = raw.confidence.coerceIn(0.0, 1.0))
        val resolution: DateResolution = resolveDateExpression(parsed.dateExpression, today)

        if (resolution.ambiguous) {
            log(text, parsed, null, "clarify")
            return AiParseResponse(
                transcript = text,
                kind = "clarify",
                message = "Auf welchen Tag bezieht sich das? Sag zum Beispiel „heute\" oder „letzten Freitag\".",
            )
        }

        val date = resolution.date ?: today

        if (parsed.exercises.isEmpty()) {
            val question = parsed.clarificationQuestion
                ?: "Ich habe keine Übung erkannt. Sag zum Beispiel: „Bankdrücken, drei Sätze mit 100 Kilo für zehn.\""
            log(text, parsed, date, "clarify")
            return AiParseResponse(transcript = text, kind = "clarify", message = question)
        }

        // Anything essential missing → ask instead of storing a guess.
        val incomplete = parsed.exercises.firstOrNull { exercise ->
            exercise.sets.isEmpty() || exercise.sets.all { set ->
                set.reps == null && set.durationSec == null && set.distanceM == null
            }
        }
        if (incomplete != null || parsed.confidence < 0.4) {
            val question = parsed.clarificationQuestion
                ?: "${incomplete?.name ?: "Die Übung"} erkannt. Wie viele Sätze und Wiederholungen waren das?"
            log(text, parsed, date, "clarify")
            return AiParseResponse(transcript = text, kind = "clarify", message = question)
        }

        val workout = getOrCreateWorkout(date, if (spoken) "APP_VOICE" else "APP_TEXT")
        val lines = mutableListOf<String>()
        var totalVolume = 0.0

        for (parsedExercise in parsed.exercises) {
            val exerciseId = resolveExercise(parsedExercise.name, parsedExercise, candidates)
            val link = db.workoutExerciseDao().find(workout.id, exerciseId)
                ?: WorkoutExerciseEntity(
                    id = newId(),
                    workoutId = workout.id,
                    exerciseId = exerciseId,
                    position = db.workoutExerciseDao().countFor(workout.id),
                ).also { db.workoutExerciseDao().upsert(it) }

            var setNumber = db.setDao().maxSetNumber(link.id)
            val stored = mutableListOf<SetValues>()
            for (set in parsedExercise.sets) {
                setNumber += 1
                val entity = WorkoutSetEntity(
                    id = newId(),
                    workoutExerciseId = link.id,
                    setNumber = setNumber,
                    weightKg = set.weightKg,
                    reps = set.reps,
                    durationSec = set.durationSec,
                    distanceM = set.distanceM,
                    isWarmup = set.isWarmup,
                    source = if (spoken) "APP_VOICE" else "APP_TEXT",
                    confidence = parsed.confidence,
                )
                db.setDao().upsert(entity)
                stored.add(SetValues(set.weightKg, set.reps, set.durationSec, set.distanceM, set.isWarmup))
            }

            recomputeRecords(db, exerciseId)
            val exercise = db.exerciseDao().byId(exerciseId)
            val summary = summarizeSets(stored)
            totalVolume += summary.volumeKg
            lines.add("${exercise?.nameDe ?: exercise?.name}: ${describeSets(stored)}")
        }

        log(text, parsed, date, "saved")

        val message = buildString {
            append("✓ Gespeichert für ${com.gymapp.tracker.core.ai.formatDateDe(date)}\n\n")
            append(lines.joinToString("\n"))
            if (totalVolume > 0) append("\n\nVolumen: ${formatKg(totalVolume)}")
        }
        return AiParseResponse(transcript = text, kind = "saved", saved = true, message = message)
    }

    suspend fun status(): AiStatusResponse = AiStatusResponse(
        provider = "anthropic",
        model = modelName(),
        ready = hasKey(),
        note = if (hasKey()) null else "Kein Anthropic-API-Key hinterlegt – trage ihn oben ein.",
    )

    private suspend fun resolveExercise(
        name: String,
        parsed: com.gymapp.tracker.data.ai.ParsedExercise,
        candidates: List<ExerciseCandidate>,
    ): String = when (val decision = decideMatch(name, candidates)) {
        is MatchDecision.Accept -> decision.candidate.id
        is MatchDecision.Ask -> decision.options.first().id
        is MatchDecision.Create -> {
            // Unknown exercise: create it rather than dropping the training.
            val entity = ExerciseEntity(
                id = newId(),
                name = name.trim().replaceFirstChar { it.uppercase() },
                nameDe = name.trim().replaceFirstChar { it.uppercase() },
                type = parsed.type,
                equipment = null,
                notes = null,
                muscleGroups = parsed.muscleGroups.joinToString(","),
                aliases = normalizeName(name),
                isCustom = true,
            )
            db.exerciseDao().upsert(entity)
            entity.id
        }
    }

    private suspend fun getOrCreateWorkout(date: LocalDate, source: String): WorkoutEntity {
        val iso = date.toString()
        db.workoutDao().byDate(iso)?.let { return it }
        val entity = WorkoutEntity(
            id = newId(),
            date = iso,
            title = null,
            notes = null,
            status = "COMPLETED",
            source = source,
        )
        db.workoutDao().upsert(entity)
        return entity
    }

    private suspend fun log(input: String, parsed: ParsedMessage, date: LocalDate?, outcome: String) {
        db.aiLogDao().insert(
            AiLogEntity(
                inputText = input,
                model = modelName(),
                intent = parsed.intent,
                confidence = parsed.confidence,
                resolvedDate = date?.toString(),
                outcome = outcome,
                rawResponse = null,
            ),
        )
    }
}
