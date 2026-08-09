package com.gymapp.tracker

import android.app.Application
import android.content.Context
import com.gymapp.tracker.data.ai.ClaudeClient
import com.gymapp.tracker.data.local.AppDatabase
import com.gymapp.tracker.data.local.seedExercises
import com.gymapp.tracker.data.prefs.TokenStore
import com.gymapp.tracker.data.repo.AiRepository
import com.gymapp.tracker.data.repo.ExerciseRepository
import com.gymapp.tracker.data.repo.ExportRepository
import com.gymapp.tracker.data.repo.SettingsRepository
import com.gymapp.tracker.data.repo.StatsRepository
import com.gymapp.tracker.data.repo.WorkoutRepository
import com.gymapp.tracker.data.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container.
 *
 * Everything is local: Room holds the data, Claude is called directly from the
 * device. No HTTP stack, no tokens, no server address to configure.
 */
class AppContainer(context: Context) {
    val prefs = TokenStore(context)
    val database = AppDatabase.create(context)

    private val claude = ClaudeClient(
        apiKeyProvider = { prefs.anthropicApiKey },
        model = { prefs.aiModel },
    )

    val exercises = ExerciseRepository(database)
    val workouts = WorkoutRepository(database)
    val stats = StatsRepository(database)
    val settings = SettingsRepository(prefs)
    val export = ExportRepository(database)
    val updater = Updater(context.applicationContext)
    val ai = AiRepository(
        db = database,
        exercises = exercises,
        claude = claude,
        modelName = { prefs.aiModel },
        hasKey = { !prefs.anthropicApiKey.isNullOrBlank() },
    )

    /** Fills the exercise catalogue the first time the app runs. */
    fun seedIfEmpty(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            if (database.exerciseDao().count() == 0) {
                database.exerciseDao().upsert(seedExercises())
            }
        }
    }
}

class GymApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.seedIfEmpty(scope)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as GymApplication).container
