package com.gymapp.tracker.ui.screens.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymapp.tracker.AppContainer
import com.gymapp.tracker.core.Fmt
import com.gymapp.tracker.core.muscleLabel
import com.gymapp.tracker.data.remote.*
import com.gymapp.tracker.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrainingUiState(
    val workout: WorkoutDto? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val paused: Boolean = false,
    val elapsedSeconds: Long = 0,
    val exercisePicker: List<ExerciseDto> = emptyList(),
    val pickerQuery: String = "",
    val showPicker: Boolean = false,
)

class TrainingViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(TrainingUiState())
    val state: StateFlow<TrainingUiState> = _state.asStateFlow()

    /** Wall clock start of the running session, used for the live timer. */
    private var startedAtMillis: Long? = null
    private var accumulated: Long = 0

    init {
        loadActive()
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _state.value
                if (current.workout?.status == "IN_PROGRESS" && !current.paused) {
                    val start = startedAtMillis ?: System.currentTimeMillis().also { startedAtMillis = it }
                    _state.value = current.copy(elapsedSeconds = accumulated + (System.currentTimeMillis() - start) / 1000)
                }
            }
        }
    }

    fun loadActive(workoutId: String? = null) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                if (workoutId != null) container.workouts.detail(workoutId).value
                else container.workouts.activeWorkout()
            }.fold(
                onSuccess = { workout ->
                    if (workout != null && workout.status == "IN_PROGRESS") {
                        accumulated = (workout.durationSec ?: 0).toLong()
                        startedAtMillis = System.currentTimeMillis()
                    }
                    _state.value = _state.value.copy(workout = workout, loading = false, elapsedSeconds = accumulated)
                },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) },
            )
        }
    }

    fun start() = launchAndReload { container.workouts.start(null) }

    fun togglePause() {
        val current = _state.value
        if (current.paused) {
            startedAtMillis = System.currentTimeMillis()
        } else {
            accumulated = current.elapsedSeconds
            startedAtMillis = null
        }
        _state.value = current.copy(paused = !current.paused)
    }

    fun finish(onDone: () -> Unit) {
        val workout = _state.value.workout ?: return
        viewModelScope.launch {
            runCatching {
                container.workouts.update(
                    workout.id,
                    UpdateWorkoutRequest(
                        status = "COMPLETED",
                        endedAt = java.time.Instant.now().toString(),
                        durationSec = _state.value.elapsedSeconds.toInt(),
                    ),
                )
            }
            accumulated = 0
            startedAtMillis = null
            _state.value = TrainingUiState(loading = false)
            onDone()
        }
    }

    fun openPicker() {
        _state.value = _state.value.copy(showPicker = true)
        searchExercises("")
    }

    fun closePicker() = run { _state.value = _state.value.copy(showPicker = false) }

    fun searchExercises(query: String) {
        _state.value = _state.value.copy(pickerQuery = query)
        viewModelScope.launch {
            runCatching { container.exercises.refresh(search = query).value }
                .onSuccess { _state.value = _state.value.copy(exercisePicker = it.take(60)) }
        }
    }

    fun addExercise(exerciseId: String) {
        val workout = _state.value.workout ?: return
        _state.value = _state.value.copy(showPicker = false)
        launchAndReload { container.workouts.addExercise(workout.id, exerciseId) }
    }

    fun removeExercise(workoutExerciseId: String) =
        launchAndReload { container.workouts.removeExercise(workoutExerciseId) }

    fun addSet(workoutExerciseId: String, request: SetRequest) =
        launchAndReload { container.workouts.addSet(workoutExerciseId, request) }

    fun updateSet(setId: String, request: SetRequest) =
        launchAndReload { container.workouts.updateSet(setId, request) }

    fun deleteSet(setId: String) = launchAndReload { container.workouts.deleteSet(setId) }

    private fun launchAndReload(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.fold(
                onSuccess = { reload() },
                onFailure = { _state.value = _state.value.copy(error = it.message) },
            )
        }
    }

    private suspend fun reload() {
        val id = _state.value.workout?.id ?: return
        runCatching { container.workouts.detail(id).value }
            .onSuccess { _state.value = _state.value.copy(workout = it, error = null) }
    }
}

@Composable
fun TrainingScreen(viewModel: TrainingViewModel, onFinished: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val workout = state.workout
    var editingSet by remember { mutableStateOf<Pair<String, SetDto?>?>(null) }

    if (state.loading && workout == null) {
        LoadingBox(Modifier.fillMaxSize())
        return
    }

    if (workout == null || workout.status != "IN_PROGRESS") {
        EmptyState(
            title = "Kein aktives Training",
            modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.CenterVertically),
            action = { Button(onClick = viewModel::start) { Text("Training starten") } },
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        if (state.paused) "Pausiert · ${Fmt.timer(state.elapsedSeconds)}"
                        else "Läuft · ${Fmt.timer(state.elapsedSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(workout.title ?: "Training", style = MaterialTheme.typography.headlineMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::togglePause) {
                        Text(if (state.paused) "Weiter" else "Pause")
                    }
                    Button(onClick = { viewModel.finish(onFinished) }) { Text("Beenden") }
                }
            }
        }

        state.error?.let { item { StatusBanner(it, isError = true) } }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Fmt.volume(workout.volumeKg), "Volumen", Modifier.weight(1f))
                StatTile("${workout.totalSets}", "Sätze", Modifier.weight(1f))
                StatTile("${workout.totalReps}", "Wdh", Modifier.weight(1f))
            }
        }

        items(workout.exercises, key = { it.id }) { exercise ->
            SectionCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(exercise.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            exercise.muscleGroups.joinToString(" · ") { muscleLabel(it) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.removeExercise(exercise.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Übung entfernen")
                    }
                }
                Spacer(Modifier.height(8.dp))

                exercise.sets.forEach { set ->
                    SetRow(
                        set = set,
                        type = exercise.type,
                        onClick = { editingSet = exercise.id to set },
                        onDelete = { viewModel.deleteSet(set.id) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { editingSet = exercise.id to null }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Satz")
                    }
                    Text(
                        Fmt.volume(exercise.volumeKg),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick = viewModel::openPicker,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Übung hinzufügen")
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }

    if (state.showPicker) {
        ExercisePickerSheet(
            exercises = state.exercisePicker,
            query = state.pickerQuery,
            onQueryChange = viewModel::searchExercises,
            onPick = viewModel::addExercise,
            onDismiss = viewModel::closePicker,
        )
    }

    editingSet?.let { (workoutExerciseId, set) ->
        val type = workout.exercises.find { it.id == workoutExerciseId }?.type ?: "STRENGTH"
        SetEditorDialog(
            existing = set,
            type = type,
            onDismiss = { editingSet = null },
            onSave = { request ->
                if (set == null) viewModel.addSet(workoutExerciseId, request)
                else viewModel.updateSet(set.id, request)
                editingSet = null
            },
        )
    }
}

@Composable
private fun SetRow(set: SetDto, type: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${set.setNumber}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Text(setDescription(set, type), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))

        if (set.isWarmup) {
            Text(
                "Aufwärmen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (set.source != "MANUAL") {
            AiBadge(set.confidence)
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Satz löschen", modifier = Modifier.size(18.dp))
        }
    }
}

private fun setDescription(set: SetDto, type: String): String = when (type) {
    "CARDIO" -> listOfNotNull(
        set.durationSec?.takeIf { it > 0 }?.let { Fmt.duration(it) },
        set.distanceM?.takeIf { it > 0 }?.let { Fmt.distance(it) },
    ).joinToString(" · ").ifEmpty { "–" }
    "DURATION" -> Fmt.duration(set.durationSec)
    else -> listOfNotNull(
        set.weightKg?.takeIf { it > 0 }?.let { Fmt.weight(it) },
        set.reps?.takeIf { it > 0 }?.let { "$it Wdh" },
    ).joinToString(" × ").ifEmpty { "–" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExercisePickerSheet(
    exercises: List<ExerciseDto>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text("Übung hinzufügen", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Übung suchen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(exercises, key = { it.id }) { exercise ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(exercise.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(exercise.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            exercise.muscleGroups.joinToString(" · ") { muscleLabel(it.key) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (exercises.isEmpty()) {
                    item { Text("Keine Übung gefunden", Modifier.padding(vertical = 24.dp)) }
                }
            }
        }
    }
}

/** Dialog for creating and editing a set – adapts its fields to the exercise type. */
@Composable
fun SetEditorDialog(
    existing: SetDto?,
    type: String,
    onDismiss: () -> Unit,
    onSave: (SetRequest) -> Unit,
) {
    var weight by remember { mutableStateOf(existing?.weightKg?.let { Fmt.number(it, if (it % 1.0 == 0.0) 0 else 1) } ?: "") }
    var reps by remember { mutableStateOf(existing?.reps?.toString() ?: "") }
    var minutes by remember { mutableStateOf(existing?.durationSec?.let { (it / 60).toString() } ?: "") }
    var distanceKm by remember { mutableStateOf(existing?.distanceM?.let { Fmt.number(it / 1000, 2) } ?: "") }
    var rpe by remember { mutableStateOf(existing?.rpe?.let { Fmt.number(it, 1) } ?: "") }
    var warmup by remember { mutableStateOf(existing?.isWarmup ?: false) }
    var oneRmTest by remember { mutableStateOf(existing?.isOneRmTest ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun parse(value: String): Double? = value.replace(',', '.').trim().toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Satz hinzufügen" else "Satz ${existing.setNumber} bearbeiten") },
        text = {
            Column {
                if (type == "STRENGTH" || type == "BODYWEIGHT") {
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it },
                        label = { Text("Gewicht (kg)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reps,
                        onValueChange = { reps = it },
                        label = { Text("Wiederholungen") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == "CARDIO" || type == "DURATION") {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it },
                        label = { Text("Dauer (Minuten)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == "CARDIO") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = distanceKm,
                        onValueChange = { distanceKm = it },
                        label = { Text("Distanz (km)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = rpe,
                    onValueChange = { rpe = it },
                    label = { Text("RPE (optional, 1–10)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Checkbox(checked = warmup, onCheckedChange = { warmup = it })
                    Text("Aufwärmsatz", style = MaterialTheme.typography.bodyMedium)
                }
                if (type == "STRENGTH") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = oneRmTest, onCheckedChange = { oneRmTest = it })
                        Text("Echter 1RM-Versuch", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val request = SetRequest(
                    weightKg = parse(weight),
                    reps = reps.trim().toIntOrNull(),
                    durationSec = minutes.trim().toIntOrNull()?.times(60),
                    distanceM = parse(distanceKm)?.times(1000),
                    rpe = parse(rpe)?.takeIf { it in 1.0..10.0 },
                    isWarmup = warmup,
                    isOneRmTest = oneRmTest,
                )
                val hasValue = listOfNotNull(request.weightKg, request.reps?.toDouble(), request.durationSec?.toDouble(), request.distanceM).any { it > 0 }
                if (!hasValue) {
                    error = "Bitte mindestens Gewicht, Wiederholungen, Dauer oder Distanz eingeben."
                } else {
                    onSave(request)
                }
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
