package com.gymapp.tracker.data.repo

import com.gymapp.tracker.core.domain.SetValues
import com.gymapp.tracker.core.domain.estimateOneRepMax
import com.gymapp.tracker.core.domain.setVolume
import com.gymapp.tracker.data.local.AppDatabase
import com.gymapp.tracker.data.prefs.TokenStore
import com.gymapp.tracker.data.remote.UpdateUserRequest
import com.gymapp.tracker.data.remote.UserDto

/** Profile and preferences — plain device settings, no account behind them. */
class SettingsRepository(private val prefs: TokenStore) {

    fun profile(): UserDto = UserDto(
        id = "local",
        email = "",
        name = prefs.displayName,
        unitSystem = prefs.unitSystem,
        timezone = java.util.TimeZone.getDefault().id,
    )

    fun update(request: UpdateUserRequest): UserDto {
        request.name?.let { prefs.displayName = it }
        request.unitSystem?.let { prefs.unitSystem = it }
        return profile()
    }

    var apiKey: String?
        get() = prefs.anthropicApiKey
        set(value) { prefs.anthropicApiKey = value?.trim()?.takeIf { it.isNotBlank() } }

    var model: String
        get() = prefs.aiModel
        set(value) { prefs.aiModel = value }

    val hasApiKey: Boolean get() = !prefs.anthropicApiKey.isNullOrBlank()

    var updateUrl: String
        get() = prefs.updateUrl
        set(value) { prefs.updateUrl = value }
}

/** CSV and JSON export — the only backup path now that data lives on-device. */
class ExportRepository(private val db: AppDatabase) {

    suspend fun export(format: String): ByteArray =
        if (format == "csv") csv().toByteArray() else json().toByteArray()

    private suspend fun csv(): String {
        val rows = db.setDao().allRows()
        val header = "date,exercise,muscle_groups,set,weight_kg,reps,duration_sec,distance_m,volume_kg,e1rm_kg,warmup,source"
        val lines = rows.map { row ->
            val values = SetValues(row.weightKg, row.reps, row.durationSec, row.distanceM, row.isWarmup)
            listOf(
                row.date,
                escape(row.exerciseNameDe ?: row.exerciseName),
                escape(row.muscleGroups),
                row.setNumber.toString(),
                row.weightKg?.toString().orEmpty(),
                row.reps?.toString().orEmpty(),
                row.durationSec?.toString().orEmpty(),
                row.distanceM?.toString().orEmpty(),
                setVolume(values).toString(),
                estimateOneRepMax(row.weightKg, row.reps).toString(),
                row.isWarmup.toString(),
                row.source,
            ).joinToString(",")
        }
        return (listOf(header) + lines).joinToString("\n")
    }

    private suspend fun json(): String {
        val rows = db.setDao().allRows()
        val records = db.recordDao().all()
        val entries = rows.groupBy { it.date }.toSortedMap().map { (date, dayRows) ->
            val exercises = dayRows.groupBy { it.exerciseId }.map { (_, exerciseRows) ->
                val first = exerciseRows.first()
                val sets = exerciseRows.joinToString(",") { row ->
                    """{"set":${row.setNumber},"weightKg":${row.weightKg ?: "null"},""" +
                        """"reps":${row.reps ?: "null"},"durationSec":${row.durationSec ?: "null"},""" +
                        """"distanceM":${row.distanceM ?: "null"},"warmup":${row.isWarmup}}"""
                }
                """{"exercise":"${escapeJson(first.exerciseNameDe ?: first.exerciseName)}",""" +
                    """"muscleGroups":"${escapeJson(first.muscleGroups)}","sets":[$sets]}"""
            }
            """{"date":"$date","exercises":[${exercises.joinToString(",")}]}"""
        }
        val recordJson = records.joinToString(",") { record ->
            """{"exerciseId":"${record.exerciseId}","type":"${record.type}","value":${record.value},""" +
                """"achievedAt":"${record.achievedAt}"}"""
        }
        return """{"exportedAt":"${java.time.Instant.now()}","workouts":[${entries.joinToString(",")}],""" +
            """"records":[$recordJson]}"""
    }

    suspend fun deleteEverything() {
        db.workoutDao().clear()
        db.recordDao().clear()
        db.aiLogDao().clear()
    }

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"')) "\"${value.replace("\"", "\"\"")}\"" else value

    private fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
