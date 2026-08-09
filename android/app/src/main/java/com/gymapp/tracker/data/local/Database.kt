package com.gymapp.tracker.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * The app's database — the single source of truth now that there is no server.
 *
 * Mirrors the relational shape the backend used (exercises → workouts →
 * workout_exercises → sets, plus records and an AI audit trail), so the same
 * queries and the same statistics carry over. Muscle groups are a fixed list in
 * code rather than a table: they never change at runtime.
 */

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameDe: String?,
    val type: String,
    val equipment: String?,
    val notes: String?,
    /** Comma separated muscle group keys, primary ones first. */
    val muscleGroups: String,
    /** Comma separated secondary keys — they count 0.4 towards a group. */
    val secondaryGroups: String = "",
    /** Normalised alternative spellings, separated by "|", for voice matching. */
    val aliases: String = "",
    val isCustom: Boolean = false,
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    /** ISO date (yyyy-MM-dd) in the user's local timezone. */
    val date: String,
    val title: String?,
    val notes: String?,
    val status: String = "COMPLETED",
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationSec: Int? = null,
    val source: String = "MANUAL",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "workout_exercises",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class WorkoutExerciseEntity(
    @PrimaryKey val id: String,
    val workoutId: String,
    val exerciseId: String,
    val position: Int = 0,
    val notes: String? = null,
)

@Entity(
    tableName = "workout_sets",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutExerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutExerciseId")],
)
data class WorkoutSetEntity(
    @PrimaryKey val id: String,
    val workoutExerciseId: String,
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
)

@Entity(tableName = "personal_records", indices = [Index("exerciseId")])
data class PersonalRecordEntity(
    @PrimaryKey val id: String,
    val exerciseId: String,
    val type: String,
    val value: Double,
    val previousValue: Double? = null,
    val weightKg: Double? = null,
    val reps: Int? = null,
    /** ISO date of the workout that set the record. */
    val achievedAt: String,
)

/** Audit trail: what was said, what the model made of it, what was stored. */
@Entity(tableName = "ai_log")
data class AiLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inputText: String,
    val model: String,
    val intent: String,
    val confidence: Double,
    val resolvedDate: String?,
    val outcome: String,
    val rawResponse: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

// --- projections used by the statistics ------------------------------------

/** One set joined with everything the statistics need about it. */
data class SetRow(
    val setId: String,
    val workoutId: String,
    val date: String,
    val exerciseId: String,
    val exerciseName: String,
    val exerciseNameDe: String?,
    val exerciseType: String,
    val muscleGroups: String,
    val secondaryGroups: String,
    val workoutExerciseId: String,
    val position: Int,
    val setNumber: Int,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val distanceM: Double?,
    val isWarmup: Boolean,
    val isOneRmTest: Boolean,
    val source: String,
    val confidence: Double?,
    val notes: String?,
)

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE deleted = 0 ORDER BY name ASC")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE deleted = 0 ORDER BY name ASC")
    suspend fun all(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun byId(id: String): ExerciseEntity?

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(exercises: List<ExerciseEntity>)

    @Upsert
    suspend fun upsert(exercise: ExerciseEntity)

    @Query("UPDATE exercises SET deleted = 1 WHERE id = :id")
    suspend fun softDelete(id: String)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY date DESC, updatedAt DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts ORDER BY date DESC, updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun byId(id: String): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE date = :date LIMIT 1")
    suspend fun byDate(date: String): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE status = 'IN_PROGRESS' ORDER BY date DESC LIMIT 1")
    suspend fun active(): WorkoutEntity?

    @Query("SELECT DISTINCT date FROM workouts ORDER BY date DESC")
    suspend fun allDates(): List<String>

    @Upsert
    suspend fun upsert(workout: WorkoutEntity)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM workouts")
    suspend fun clear()
}

@Dao
interface WorkoutExerciseDao {
    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId ORDER BY position ASC")
    suspend fun forWorkout(workoutId: String): List<WorkoutExerciseEntity>

    @Query("SELECT * FROM workout_exercises WHERE id = :id")
    suspend fun byId(id: String): WorkoutExerciseEntity?

    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId AND exerciseId = :exerciseId LIMIT 1")
    suspend fun find(workoutId: String, exerciseId: String): WorkoutExerciseEntity?

    @Query("SELECT COUNT(*) FROM workout_exercises WHERE workoutId = :workoutId")
    suspend fun countFor(workoutId: String): Int

    @Upsert
    suspend fun upsert(entity: WorkoutExerciseEntity)

    @Query("DELETE FROM workout_exercises WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WorkoutSetDao {
    @Query("SELECT * FROM workout_sets WHERE workoutExerciseId = :id ORDER BY setNumber ASC")
    suspend fun forWorkoutExercise(id: String): List<WorkoutSetEntity>

    @Query("SELECT * FROM workout_sets WHERE id = :id")
    suspend fun byId(id: String): WorkoutSetEntity?

    @Query("SELECT COALESCE(MAX(setNumber), 0) FROM workout_sets WHERE workoutExerciseId = :id")
    suspend fun maxSetNumber(id: String): Int

    @Upsert
    suspend fun upsert(set: WorkoutSetEntity)

    @Query("DELETE FROM workout_sets WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Every set with its exercise and workout context — the input for all
     * statistics. Small enough to load fully: a decade of daily training is
     * on the order of tens of thousands of rows.
     */
    @Query(
        """
        SELECT s.id AS setId, w.id AS workoutId, w.date AS date,
               e.id AS exerciseId, e.name AS exerciseName, e.nameDe AS exerciseNameDe,
               e.type AS exerciseType, e.muscleGroups AS muscleGroups,
               e.secondaryGroups AS secondaryGroups,
               we.id AS workoutExerciseId, we.position AS position,
               s.setNumber AS setNumber, s.weightKg AS weightKg, s.reps AS reps,
               s.durationSec AS durationSec, s.distanceM AS distanceM,
               s.isWarmup AS isWarmup, s.isOneRmTest AS isOneRmTest,
               s.source AS source, s.confidence AS confidence, s.notes AS notes
        FROM workout_sets s
        JOIN workout_exercises we ON we.id = s.workoutExerciseId
        JOIN workouts w ON w.id = we.workoutId
        JOIN exercises e ON e.id = we.exerciseId
        ORDER BY w.date ASC, we.position ASC, s.setNumber ASC
        """,
    )
    suspend fun allRows(): List<SetRow>

    @Query(
        """
        SELECT s.id AS setId, w.id AS workoutId, w.date AS date,
               e.id AS exerciseId, e.name AS exerciseName, e.nameDe AS exerciseNameDe,
               e.type AS exerciseType, e.muscleGroups AS muscleGroups,
               e.secondaryGroups AS secondaryGroups,
               we.id AS workoutExerciseId, we.position AS position,
               s.setNumber AS setNumber, s.weightKg AS weightKg, s.reps AS reps,
               s.durationSec AS durationSec, s.distanceM AS distanceM,
               s.isWarmup AS isWarmup, s.isOneRmTest AS isOneRmTest,
               s.source AS source, s.confidence AS confidence, s.notes AS notes
        FROM workout_sets s
        JOIN workout_exercises we ON we.id = s.workoutExerciseId
        JOIN workouts w ON w.id = we.workoutId
        JOIN exercises e ON e.id = we.exerciseId
        WHERE we.exerciseId = :exerciseId
        ORDER BY w.date ASC, s.setNumber ASC
        """,
    )
    suspend fun rowsForExercise(exerciseId: String): List<SetRow>

    @Query(
        """
        SELECT s.id AS setId, w.id AS workoutId, w.date AS date,
               e.id AS exerciseId, e.name AS exerciseName, e.nameDe AS exerciseNameDe,
               e.type AS exerciseType, e.muscleGroups AS muscleGroups,
               e.secondaryGroups AS secondaryGroups,
               we.id AS workoutExerciseId, we.position AS position,
               s.setNumber AS setNumber, s.weightKg AS weightKg, s.reps AS reps,
               s.durationSec AS durationSec, s.distanceM AS distanceM,
               s.isWarmup AS isWarmup, s.isOneRmTest AS isOneRmTest,
               s.source AS source, s.confidence AS confidence, s.notes AS notes
        FROM workout_sets s
        JOIN workout_exercises we ON we.id = s.workoutExerciseId
        JOIN workouts w ON w.id = we.workoutId
        JOIN exercises e ON e.id = we.exerciseId
        WHERE w.id = :workoutId
        ORDER BY we.position ASC, s.setNumber ASC
        """,
    )
    suspend fun rowsForWorkout(workoutId: String): List<SetRow>
}

@Dao
interface PersonalRecordDao {
    @Query("SELECT * FROM personal_records ORDER BY achievedAt DESC")
    suspend fun all(): List<PersonalRecordEntity>

    @Query("SELECT * FROM personal_records WHERE exerciseId = :exerciseId ORDER BY achievedAt DESC")
    suspend fun forExercise(exerciseId: String): List<PersonalRecordEntity>

    @Query("DELETE FROM personal_records WHERE exerciseId = :exerciseId")
    suspend fun deleteForExercise(exerciseId: String)

    @Insert
    suspend fun insert(records: List<PersonalRecordEntity>)

    @Query("DELETE FROM personal_records")
    suspend fun clear()
}

@Dao
interface AiLogDao {
    @Insert
    suspend fun insert(entry: AiLogEntity)

    @Query("SELECT * FROM ai_log ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<AiLogEntity>

    @Query("DELETE FROM ai_log")
    suspend fun clear()
}

@Database(
    entities = [
        ExerciseEntity::class,
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        WorkoutSetEntity::class,
        PersonalRecordEntity::class,
        AiLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun workoutExerciseDao(): WorkoutExerciseDao
    abstract fun setDao(): WorkoutSetDao
    abstract fun recordDao(): PersonalRecordDao
    abstract fun aiLogDao(): AiLogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "gymapp.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
