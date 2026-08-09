package com.gymapp.tracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format of the REST API. Mirrors the serializers in the backend
 * (see backend/src/routes). Every field the backend may omit is nullable or
 * has a default, so an older app never crashes on a newer server.
 */

// --- Auth -------------------------------------------------------------------

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val name: String,
    val timezone: String? = null,
    val locale: String? = null,
)

@Serializable
data class AuthResponse(
    val user: UserDto,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: String? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresIn: String? = null)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val unitSystem: String = "KG",
    val locale: String = "de",
    val timezone: String = "Europe/Berlin",
    val themePreference: String = "SYSTEM",
    val aiModel: String? = null,
    val notificationsEnabled: Boolean = true,
)

@Serializable
data class UserWrapper(val user: UserDto)

@Serializable
data class UpdateUserRequest(
    val name: String? = null,
    val unitSystem: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val themePreference: String? = null,
    val notificationsEnabled: Boolean? = null,
)

// --- Exercises ---------------------------------------------------------------

@Serializable
data class MuscleGroupRefDto(
    val key: String,
    val nameDe: String? = null,
    val nameEn: String? = null,
    val role: String = "PRIMARY",
    val contribution: Float = 1f,
)

@Serializable
data class ExerciseDto(
    val id: String,
    val name: String,
    val nameDe: String? = null,
    val type: String = "STRENGTH",
    val equipment: String? = null,
    val notes: String? = null,
    val isCustom: Boolean = false,
    val isGlobal: Boolean = false,
    val muscleGroups: List<MuscleGroupRefDto> = emptyList(),
    val aliases: List<String> = emptyList(),
) {
    /** German name when available – the UI is German. */
    val displayName: String get() = nameDe?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class ExerciseListResponse(val exercises: List<ExerciseDto>)

@Serializable
data class ExerciseWrapper(val exercise: ExerciseDto)

@Serializable
data class ExerciseRequest(
    val name: String,
    val nameDe: String? = null,
    val type: String? = null,
    val equipment: String? = null,
    val notes: String? = null,
    val muscleGroupKeys: List<String>? = null,
)

@Serializable
data class MuscleGroupDto(
    val id: String,
    val key: String,
    val nameEn: String,
    val nameDe: String,
    val parentKey: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class MuscleGroupListResponse(val muscleGroups: List<MuscleGroupDto>)

// --- Workouts -----------------------------------------------------------------

@Serializable
data class SetDto(
    val id: String,
    val setNumber: Int,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
    val isOneRmTest: Boolean = false,
    val notes: String? = null,
    val source: String = "MANUAL",
    val confidence: Double? = null,
    val updatedAt: String? = null,
)

@Serializable
data class WorkoutExerciseDto(
    val id: String,
    val exerciseId: String,
    val name: String,
    val nameDe: String? = null,
    val type: String = "STRENGTH",
    val position: Int = 0,
    val notes: String? = null,
    val muscleGroups: List<String> = emptyList(),
    val volumeKg: Double = 0.0,
    val sets: List<SetDto> = emptyList(),
) {
    val displayName: String get() = nameDe?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class WorkoutDto(
    val id: String,
    val date: String,
    val title: String? = null,
    val notes: String? = null,
    val status: String = "COMPLETED",
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationSec: Int? = null,
    val source: String = "MANUAL",
    val volumeKg: Double = 0.0,
    val totalSets: Int = 0,
    val totalReps: Int = 0,
    val updatedAt: String? = null,
    val exercises: List<WorkoutExerciseDto> = emptyList(),
)

@Serializable
data class WorkoutListResponse(val workouts: List<WorkoutDto>)

@Serializable
data class WorkoutWrapper(val workout: WorkoutDto)

@Serializable
data class CreateWorkoutRequest(
    val date: String,
    val title: String? = null,
    val notes: String? = null,
    val status: String? = null,
)

@Serializable
data class UpdateWorkoutRequest(
    val date: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val endedAt: String? = null,
    val durationSec: Int? = null,
)

@Serializable
data class AddExerciseRequest(val exerciseId: String, val notes: String? = null)

@Serializable
data class SetRequest(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val rpe: Double? = null,
    val isWarmup: Boolean? = null,
    val isOneRmTest: Boolean? = null,
    val notes: String? = null,
    val setNumber: Int? = null,
)

@Serializable
data class SetWrapper(val set: SetDto)

// --- Statistics -----------------------------------------------------------------

@Serializable
data class DashboardExerciseDto(
    val exerciseId: String,
    val name: String,
    val muscleGroups: List<String> = emptyList(),
    val summary: String,
    val volumeKg: Double = 0.0,
    val sets: Int = 0,
)

@Serializable
data class DashboardTodayDto(
    val hasWorkout: Boolean = false,
    val workoutId: String? = null,
    val title: String? = null,
    val exercises: List<DashboardExerciseDto> = emptyList(),
    val volumeKg: Double = 0.0,
    val sets: Int = 0,
    val reps: Int = 0,
    val durationSec: Int? = null,
)

@Serializable
data class ComparisonsDto(
    val vsLastWorkout: Double? = null,
    val vsLastWeek: Double? = null,
    val vsLastMonth: Double? = null,
    val strengthTrend: Double? = null,
)

@Serializable
data class MuscleGroupProgressDto(
    val key: String,
    val changePercent: Double? = null,
    val volumeKg: Double = 0.0,
    val sets: Int = 0,
    val exercises: Int = 0,
    val reliable: Boolean = false,
)

@Serializable
data class RecordSummaryDto(
    val exerciseName: String,
    val type: String,
    val value: Double,
    val previousValue: Double? = null,
    val improvementPercent: Double? = null,
    val achievedAt: String,
)

@Serializable
data class DashboardTotalsDto(val workouts: Int = 0, val volumeKg: Double = 0.0, val sets: Int = 0)

@Serializable
data class DashboardDto(
    val date: String,
    val today: DashboardTodayDto = DashboardTodayDto(),
    val streakDays: Int = 0,
    val workoutsThisWeek: Int = 0,
    val totals: DashboardTotalsDto = DashboardTotalsDto(),
    val comparisons: ComparisonsDto = ComparisonsDto(),
    val muscleGroups: List<MuscleGroupProgressDto> = emptyList(),
    val recentRecords: List<RecordSummaryDto> = emptyList(),
)

@Serializable
data class VolumePointDto(val date: String, val volumeKg: Double = 0.0, val sets: Int = 0)

@Serializable
data class OverviewDto(
    val period: String,
    val from: String? = null,
    val to: String,
    val workouts: Int = 0,
    val workoutsPerWeek: Double = 0.0,
    val volumeKg: Double = 0.0,
    val avgVolumePerWorkout: Double = 0.0,
    val avgWeightKg: Double = 0.0,
    val avgReps: Double = 0.0,
    val totalSets: Int = 0,
    val totalReps: Int = 0,
    val durationSec: Int = 0,
    val newRecords: Int = 0,
    val strengthTrend: Double? = null,
    val volumeTrend: Double? = null,
    val muscleGroups: List<MuscleGroupProgressDto> = emptyList(),
    val strongest: List<MuscleGroupProgressDto> = emptyList(),
    val weakest: List<MuscleGroupProgressDto> = emptyList(),
    val volumeSeries: List<VolumePointDto> = emptyList(),
)

@Serializable
data class ExerciseStatsPointDto(
    val date: String,
    val maxWeightKg: Double? = null,
    val totalVolumeKg: Double = 0.0,
    val bestE1rm: Double? = null,
    val totalReps: Int = 0,
    val sets: Int = 0,
    val avgWeightKg: Double? = null,
)

@Serializable
data class BestRepsDto(val reps: Int, val weightKg: Double? = null)

@Serializable
data class ExerciseStatsDto(
    val exerciseId: String,
    val name: String,
    val type: String = "STRENGTH",
    val period: String = "90d",
    val personalBestKg: Double? = null,
    val bestReps: BestRepsDto? = null,
    val bestVolumeKg: Double? = null,
    val bestE1rmKg: Double? = null,
    val hasMeasuredOneRm: Boolean = false,
    val avgWeightKg: Double? = null,
    val avgReps: Double? = null,
    val totalSets: Int = 0,
    val totalVolumeKg: Double = 0.0,
    val sessions: Int = 0,
    val frequencyPerWeek: Double = 0.0,
    val progressPercent: Double? = null,
    val series: List<ExerciseStatsPointDto> = emptyList(),
)

@Serializable
data class PersonalRecordDto(
    val type: String,
    val value: Double,
    val previousValue: Double? = null,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val achievedAt: String,
)

@Serializable
data class ExerciseStatsResponse(
    val stats: ExerciseStatsDto,
    val records: List<PersonalRecordDto> = emptyList(),
)

@Serializable
data class RecordListItemDto(
    val id: String,
    val exerciseId: String,
    val exerciseName: String,
    val type: String,
    val value: Double,
    val previousValue: Double? = null,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val achievedAt: String,
)

@Serializable
data class RecordListResponse(val records: List<RecordListItemDto> = emptyList())

@Serializable
data class CalendarDayDto(
    val date: String,
    val workoutId: String,
    val volumeKg: Double = 0.0,
    val sets: Int = 0,
    val exercises: Int = 0,
    val muscleGroups: List<String> = emptyList(),
    val records: Int = 0,
)

@Serializable
data class CalendarResponse(val days: List<CalendarDayDto> = emptyList())

@Serializable
data class WeeklyVolumePointDto(
    val weekStart: String,
    val volumeKg: Double = 0.0,
    val sets: Int = 0,
    val workouts: Int = 0,
)

@Serializable
data class VolumeResponse(
    val granularity: String = "week",
    val points: List<WeeklyVolumePointDto> = emptyList(),
)

// --- Telegram ---------------------------------------------------------------

@Serializable
data class LinkCodeResponse(
    val code: String,
    val expiresAt: String,
    val botUsername: String? = null,
    val deepLink: String? = null,
    val instructions: String? = null,
)

@Serializable
data class TelegramStatusResponse(
    val linked: Boolean = false,
    val telegramUserId: String? = null,
    val username: String? = null,
    val firstName: String? = null,
    val linkedAt: String? = null,
    val botUsername: String? = null,
)

// --- Sync ---------------------------------------------------------------------

@Serializable
data class SyncOperationDto(
    val entity: String,
    val op: String,
    val id: String,
    val baseUpdatedAt: String? = null,
    val data: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class SyncPushRequest(val operations: List<SyncOperationDto>)

@Serializable
data class SyncConflictDto(val entity: String, val id: String, val reason: String, val message: String)

@Serializable
data class SyncPushResponse(
    val applied: Int = 0,
    val conflicts: List<SyncConflictDto> = emptyList(),
    val serverTime: String? = null,
)

@Serializable
data class SyncPullResponse(
    val serverTime: String,
    val exercises: List<SyncExerciseDto> = emptyList(),
    val workouts: List<WorkoutDto> = emptyList(),
)

@Serializable
data class SyncExerciseDto(
    val id: String,
    val name: String,
    val nameDe: String? = null,
    val type: String = "STRENGTH",
    val equipment: String? = null,
    val notes: String? = null,
    val isGlobal: Boolean = false,
    val isCustom: Boolean = false,
    val muscleGroups: List<String> = emptyList(),
    val updatedAt: String? = null,
    val deletedAt: String? = null,
)

// --- AI (in-app voice / text input) --------------------------------------------

@Serializable
data class AiParseRequest(
    val text: String,
    val spoken: Boolean = false,
    val confirmFirst: Boolean = false,
)

@Serializable
data class AiParseResponse(
    val transcript: String = "",
    val kind: String = "nothing",
    val saved: Boolean = false,
    val needsConfirmation: Boolean = false,
    val aiResultId: String? = null,
    val message: String = "",
)

@Serializable
data class AiStatusResponse(
    val provider: String = "heuristic",
    val model: String = "",
    val ready: Boolean = false,
    val note: String? = null,
)

// --- Errors -------------------------------------------------------------------

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(
    val code: String = "UNKNOWN",
    val message: String = "",
    @SerialName("details") val details: kotlinx.serialization.json.JsonElement? = null,
)
