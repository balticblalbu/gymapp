package com.gymapp.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gymapp.tracker.ui.screens.dashboard.DashboardScreen
import com.gymapp.tracker.ui.screens.dashboard.DashboardViewModel
import com.gymapp.tracker.ui.screens.exercises.ExerciseDetailScreen
import com.gymapp.tracker.ui.screens.exercises.ExerciseDetailViewModel
import com.gymapp.tracker.ui.screens.exercises.ExercisesScreen
import com.gymapp.tracker.ui.screens.exercises.ExercisesViewModel
import com.gymapp.tracker.ui.screens.history.HistoryScreen
import com.gymapp.tracker.ui.screens.history.HistoryViewModel
import com.gymapp.tracker.ui.screens.settings.SettingsScreen
import com.gymapp.tracker.ui.screens.settings.SettingsViewModel
import com.gymapp.tracker.ui.screens.training.TrainingScreen
import com.gymapp.tracker.ui.screens.training.TrainingViewModel
import com.gymapp.tracker.ui.screens.voice.VoiceSheet
import com.gymapp.tracker.ui.screens.voice.VoiceViewModel
import com.gymapp.tracker.ui.theme.GymAppTheme
import com.gymapp.tracker.ui.theme.ThemeMode

/**
 * Single activity, Compose navigation.
 * ViewModels are created through a small factory because dependencies come from
 * the manual AppContainer rather than a DI framework.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as GymApplication).container

        setContent {
            var themeMode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM) }

            GymAppTheme(themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Private single-user instance: no login, straight into the app.
                    MainScaffold(
                        container = container,
                        themeMode = themeMode,
                        onThemeChange = { themeMode = it },
                    )
                }
            }
        }
    }
}

private sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Destination("dashboard", "Start", Icons.Default.Home)
    data object Training : Destination("training", "Training", Icons.Default.FitnessCenter)
    data object Exercises : Destination("exercises", "Übungen", Icons.Default.BarChart)
    data object History : Destination("history", "Verlauf", Icons.Default.CalendarMonth)
    data object Settings : Destination("settings", "Profil", Icons.Default.Person)
}

private val bottomDestinations = listOf(
    Destination.Dashboard,
    Destination.Training,
    Destination.Exercises,
    Destination.History,
    Destination.Settings,
)

@Composable
private fun MainScaffold(
    container: AppContainer,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    var userName by remember { mutableStateOf("Athlet") }
    var showVoice by remember { mutableStateOf(false) }
    var dataVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        userName = container.settings.profile().name.substringBefore(' ')
    }

    val showBottomBar = currentDestination?.route?.startsWith("exerciseDetail") != true &&
        currentDestination?.route?.startsWith("workout/") != true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (showBottomBar) {
                // The fastest way in: speak the workout, Claude structures it.
                FloatingActionButton(
                    onClick = { showVoice = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Training diktieren")
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    bottomDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            composable(Destination.Dashboard.route) {
                DashboardScreen(
                    viewModel = viewModel { DashboardViewModel(container) },
                    userName = userName,
                    onOpenWorkout = { navController.navigate(Destination.History.route) },
                    onStartTraining = { navController.navigate(Destination.Training.route) },
                    onOpenExercise = { navController.navigate("exerciseDetail/$it") },
                )
            }

            composable(Destination.Training.route) {
                TrainingScreen(
                    viewModel = viewModel { TrainingViewModel(container) },
                    onFinished = { navController.navigate(Destination.Dashboard.route) },
                )
            }

            composable(Destination.Exercises.route) {
                ExercisesScreen(
                    viewModel = viewModel { ExercisesViewModel(container) },
                    onOpenExercise = { navController.navigate("exerciseDetail/$it") },
                )
            }

            composable(Destination.History.route) {
                HistoryScreen(
                    viewModel = viewModel { HistoryViewModel(container) },
                    onOpenWorkout = { navController.navigate(Destination.Training.route) },
                    onOpenExercise = { navController.navigate("exerciseDetail/$it") },
                )
            }

            composable(Destination.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel { SettingsViewModel(container) },
                    themeMode = themeMode,
                    onThemeChange = onThemeChange,
                )
            }

            composable("exerciseDetail/{exerciseId}") { entry ->
                val exerciseId = entry.arguments?.getString("exerciseId").orEmpty()
                ExerciseDetailScreen(
                    viewModel = viewModel(key = exerciseId) { ExerciseDetailViewModel(container, exerciseId) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    if (showVoice) {
        VoiceSheet(
            viewModel = viewModel { VoiceViewModel(container) },
            onDismiss = { showVoice = false },
            onSaved = { dataVersion += 1 },
        )
    }
}
