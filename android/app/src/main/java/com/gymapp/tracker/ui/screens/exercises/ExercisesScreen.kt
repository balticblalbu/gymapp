package com.gymapp.tracker.ui.screens.exercises

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymapp.tracker.AppContainer
import com.gymapp.tracker.core.ExerciseTypeLabels
import com.gymapp.tracker.core.muscleLabel
import com.gymapp.tracker.data.remote.ExerciseDto
import com.gymapp.tracker.data.remote.ExerciseRequest
import com.gymapp.tracker.data.remote.MuscleGroupDto
import com.gymapp.tracker.ui.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExercisesUiState(
    val exercises: List<ExerciseDto> = emptyList(),
    val muscleGroups: List<MuscleGroupDto> = emptyList(),
    val query: String = "",
    val filter: String? = null,
    val loading: Boolean = true,
    val offline: Boolean = false,
    val error: String? = null,
    /** Custom order (exercise ids) for the unfiltered catalogue, drag-reorderable. */
    val order: List<String> = emptyList(),
) {
    /** Reordering only makes sense over the full, unfiltered catalogue. */
    val isReorderable: Boolean get() = query.isBlank() && filter == null
}

class ExercisesViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ExercisesUiState())
    val state: StateFlow<ExercisesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { container.exercises.muscleGroups() }
                .onSuccess { groups -> _state.value = _state.value.copy(muscleGroups = groups.filter { it.parentKey == null }) }
        }
        viewModelScope.launch {
            // Cached list keeps the screen usable while the request is in flight,
            // and its ids (always the full, unfiltered catalogue) are what the
            // drag order is merged against as exercises are added or removed.
            container.exercises.observeCached().collect { cached ->
                if (_state.value.exercises.isEmpty()) {
                    _state.value = _state.value.copy(exercises = cached, loading = false)
                }
                mergeOrder(cached.map { it.id })
            }
        }
        load()
    }

    private fun mergeOrder(allIds: List<String>) {
        val saved = container.settings.exerciseOrder.split(",").filter { it.isNotBlank() }
        val merged = saved.filter { it in allIds } + allIds.filterNot { it in saved }
        if (merged != _state.value.order) {
            _state.value = _state.value.copy(order = merged)
        }
    }

    fun reorder(newOrder: List<String>) {
        _state.value = _state.value.copy(order = newOrder)
        container.settings.exerciseOrder = newOrder.joinToString(",")
    }

    fun onQuery(value: String) {
        _state.value = _state.value.copy(query = value)
        load()
    }

    fun onFilter(key: String?) {
        _state.value = _state.value.copy(filter = key)
        load()
    }

    fun load() {
        viewModelScope.launch {
            val current = _state.value
            runCatching { container.exercises.refresh(current.query, current.filter) }.fold(
                onSuccess = { result ->
                    _state.value = _state.value.copy(
                        exercises = result.value,
                        loading = false,
                        offline = result.fromCache,
                        error = null,
                    )
                },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) },
            )
        }
    }

    fun create(request: ExerciseRequest, onDone: () -> Unit) = mutate({ container.exercises.create(request) }, onDone)

    fun update(id: String, request: ExerciseRequest, onDone: () -> Unit) =
        mutate({ container.exercises.update(id, request) }, onDone)

    fun delete(id: String) = mutate({ container.exercises.delete(id) }) {}

    private fun mutate(block: suspend () -> Unit, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.fold(
                onSuccess = { load(); onDone() },
                onFailure = { _state.value = _state.value.copy(error = it.message) },
            )
        }
    }
}

@Composable
fun ExercisesScreen(viewModel: ExercisesViewModel, onOpenExercise: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    var editing by remember { mutableStateOf<ExerciseDto?>(null) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Übungen", style = MaterialTheme.typography.headlineMedium)
                    FilledTonalIconButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Neue Übung")
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQuery,
                    placeholder = { Text("Übung suchen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                PeriodSelector(
                    periods = listOf<Pair<String, String>>("" to "Alle") +
                        state.muscleGroups.map { it.key to it.nameDe },
                    selected = state.filter ?: "",
                    onSelect = { viewModel.onFilter(it.takeIf { key -> key.isNotEmpty() }) },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            if (state.offline) item { StatusBanner("Offline – zeige gespeicherte Übungen") }
            state.error?.let { item { StatusBanner(it, isError = true, onAction = viewModel::load, actionLabel = "Erneut") } }
            if (state.loading && state.exercises.isEmpty()) item { LoadingBox() }

            if (state.isReorderable) {
                val indexOf = state.order.withIndex().associate { (i, id) -> id to i }
                val ordered = state.exercises.sortedBy { indexOf[it.id] ?: Int.MAX_VALUE }
                item {
                    DraggableSectionList(
                        items = ordered,
                        key = { it.id },
                        onReorder = { newOrder -> viewModel.reorder(newOrder.map { it.id }) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { exercise ->
                        Box(Modifier.padding(bottom = 8.dp)) {
                            ExerciseRow(exercise, onOpenExercise = onOpenExercise, onEdit = { editing = exercise })
                        }
                    }
                }
            } else {
                items(state.exercises, key = { it.id }) { exercise ->
                    ExerciseRow(exercise, onOpenExercise = onOpenExercise, onEdit = { editing = exercise })
                }
            }

            if (!state.loading && state.exercises.isEmpty()) {
                item { EmptyState("Keine Übung gefunden", "Lege eine neue Übung an – oder diktiere sie dem Telegram-Bot.") }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }

    if (creating) {
        ExerciseEditorDialog(
            existing = null,
            muscleGroups = state.muscleGroups,
            onDismiss = { creating = false },
            onSave = { request -> viewModel.create(request) { creating = false } },
        )
    }

    editing?.let { exercise ->
        ExerciseEditorDialog(
            existing = exercise,
            muscleGroups = state.muscleGroups,
            onDismiss = { editing = null },
            onDelete = if (exercise.isCustom) {
                { viewModel.delete(exercise.id); editing = null }
            } else null,
            onSave = { request -> viewModel.update(exercise.id, request) { editing = null } },
        )
    }
}

@Composable
private fun ExerciseRow(exercise: ExerciseDto, onOpenExercise: (String) -> Unit, onEdit: () -> Unit) {
    SectionCard(modifier = Modifier.clickable { onOpenExercise(exercise.id) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(exercise.displayName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(exercise.muscleGroups.joinToString(" · ") { muscleLabel(it.key) })
                        exercise.equipment?.let { append(" · $it") }
                        ExerciseTypeLabels[exercise.type]?.takeIf { exercise.type != "STRENGTH" }
                            ?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text("Bearbeiten") }
        }
    }
}

@Composable
private fun ExerciseEditorDialog(
    existing: ExerciseDto?,
    muscleGroups: List<MuscleGroupDto>,
    onDismiss: () -> Unit,
    onSave: (ExerciseRequest) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var nameDe by remember { mutableStateOf(existing?.nameDe ?: "") }
    var equipment by remember { mutableStateOf(existing?.equipment ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: "STRENGTH") }
    var selected by remember { mutableStateOf(existing?.muscleGroups?.map { it.key }?.toSet() ?: emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Neue Übung" else "Übung bearbeiten") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nameDe,
                    onValueChange = { nameDe = it },
                    label = { Text("Deutscher Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = equipment,
                    onValueChange = { equipment = it },
                    label = { Text("Equipment (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text("Übungsart", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                PeriodSelector(
                    periods = ExerciseTypeLabels.toList(),
                    selected = type,
                    onSelect = { type = it },
                )

                Spacer(Modifier.height(12.dp))
                Text("Muskelgruppen", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                muscleGroups.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { group ->
                            Row(
                                Modifier.weight(1f).clickable {
                                    selected = if (selected.contains(group.key)) selected - group.key else selected + group.key
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = selected.contains(group.key), onCheckedChange = null)
                                Text(group.nameDe, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                if (existing != null && existing.isGlobal) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Dies ist eine Standardübung. Beim Speichern wird eine persönliche Kopie angelegt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        ExerciseRequest(
                            name = name.trim(),
                            nameDe = nameDe.trim().takeIf { it.isNotBlank() },
                            type = type,
                            equipment = equipment.trim().takeIf { it.isNotBlank() },
                            muscleGroupKeys = selected.toList(),
                        ),
                    )
                },
            ) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text("Löschen", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

