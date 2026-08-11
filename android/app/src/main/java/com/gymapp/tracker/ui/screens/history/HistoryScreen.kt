package com.gymapp.tracker.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymapp.tracker.AppContainer
import com.gymapp.tracker.core.Fmt
import com.gymapp.tracker.core.muscleLabel
import com.gymapp.tracker.data.remote.CalendarDayDto
import com.gymapp.tracker.data.remote.MuscleGroupDto
import com.gymapp.tracker.data.remote.WorkoutDto
import com.gymapp.tracker.ui.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** The calendar rides along in the drag order as if it were a workout card. */
private const val CALENDAR_SECTION = "calendar"

data class HistoryUiState(
    val workouts: List<WorkoutDto> = emptyList(),
    val calendar: List<CalendarDayDto> = emptyList(),
    val muscleGroups: List<MuscleGroupDto> = emptyList(),
    val filter: String? = null,
    val month: YearMonth = YearMonth.now(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Custom order (workout ids) for the unfiltered list, drag-reorderable. */
    val order: List<String> = emptyList(),
) {
    /** Reordering only makes sense over the full, unfiltered list. */
    val isReorderable: Boolean get() = filter == null
}

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { container.exercises.muscleGroups() }
                .onSuccess { groups -> _state.value = _state.value.copy(muscleGroups = groups.filter { it.parentKey == null }) }
        }
        viewModelScope.launch {
            container.workouts.observeCached().collect { cached ->
                if (_state.value.workouts.isEmpty()) _state.value = _state.value.copy(workouts = cached, loading = false)
                mergeOrder(cached.map { it.id })
            }
        }
        load()
    }

    /** Keeps the saved order valid as workouts are added or deleted. */
    private fun mergeOrder(workoutIds: List<String>) {
        val available = listOf(CALENDAR_SECTION) + workoutIds
        val saved = container.settings.historyOrder.split(",")
            .filter { it.isNotBlank() && it in available }
        // Whatever has no place yet joins at the top: a workout that just
        // appeared is the newest one, and the calendar starts above the list.
        val merged = available.filterNot { it in saved } + saved
        if (merged != _state.value.order) _state.value = _state.value.copy(order = merged)
    }

    fun reorder(newOrder: List<String>) {
        _state.value = _state.value.copy(order = newOrder)
        container.settings.historyOrder = newOrder.joinToString(",")
    }

    fun onFilter(key: String?) {
        _state.value = _state.value.copy(filter = key)
        load()
    }

    fun changeMonth(delta: Long) {
        _state.value = _state.value.copy(month = _state.value.month.plusMonths(delta))
        loadCalendar()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            runCatching { container.workouts.refreshList(muscleGroup = _state.value.filter) }.fold(
                onSuccess = { result ->
                    _state.value = _state.value.copy(
                        workouts = result.value,
                        loading = false,
                        error = null,
                    )
                    // Only the unfiltered list is the complete set; merging a
                    // filtered subset would drop everything it leaves out.
                    if (_state.value.filter == null) mergeOrder(result.value.map { it.id })
                },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) },
            )
        }
        loadCalendar()
    }

    private fun loadCalendar() {
        viewModelScope.launch {
            val month = _state.value.month
            val days = container.stats.calendar(month.atDay(1).toString(), month.atEndOfMonth().toString())
            _state.value = _state.value.copy(calendar = days)
        }
    }

    fun deleteWorkout(id: String) {
        viewModelScope.launch {
            runCatching { container.workouts.delete(id) }.onSuccess { load() }
        }
    }
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenWorkout: (String) -> Unit,
    onOpenExercise: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<WorkoutDto?>(null) }
    val listState = rememberLazyListState()
    val scroller = rememberDragAutoScroller(listState)

    LazyColumn(
        state = listState,
        modifier = Modifier.dragAutoScrollBounds(scroller),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Verlauf", style = MaterialTheme.typography.headlineMedium)
        }

        // --- Filter --------------------------------------------------------
        item {
            PeriodSelector(
                periods = listOf<Pair<String, String>>("" to "Alle") + state.muscleGroups.map { it.key to it.nameDe },
                selected = state.filter ?: "",
                onSelect = { viewModel.onFilter(it.takeIf { key -> key.isNotEmpty() }) },
            )
        }

        state.error?.let { item { StatusBanner(it, isError = true, onAction = viewModel::load, actionLabel = "Erneut") } }
        if (state.loading && state.workouts.isEmpty()) item { LoadingBox() }

        // --- Kalender und Trainings -----------------------------------------
        if (state.isReorderable) {
            val byId = state.workouts.associateBy { it.id }
            // Built from what is actually there, then sorted by the saved
            // order — never the other way round, or a workout the order has
            // not caught up with yet would silently disappear.
            val rank = state.order.withIndex().associate { (position, key) -> key to position }
            val sections = (listOf(CALENDAR_SECTION) + state.workouts.map { it.id })
                .sortedBy { rank[it] ?: Int.MAX_VALUE }
            item {
                DraggableSectionList(
                    items = sections,
                    key = { it },
                    onReorder = viewModel::reorder,
                    modifier = Modifier.fillMaxWidth(),
                    scroller = scroller,
                ) { section ->
                    Box(Modifier.padding(bottom = 12.dp)) {
                        if (section == CALENDAR_SECTION) {
                            CalendarCard(
                                state = state,
                                onChangeMonth = viewModel::changeMonth,
                                onOpenWorkout = onOpenWorkout,
                            )
                        } else {
                            byId[section]?.let { workout ->
                                WorkoutCard(
                                    workout = workout,
                                    onOpenWorkout = onOpenWorkout,
                                    onOpenExercise = onOpenExercise,
                                    onDelete = { pendingDelete = workout },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            item {
                CalendarCard(
                    state = state,
                    onChangeMonth = viewModel::changeMonth,
                    onOpenWorkout = onOpenWorkout,
                )
            }
            items(state.workouts, key = { it.id }) { workout ->
                WorkoutCard(
                    workout = workout,
                    onOpenWorkout = onOpenWorkout,
                    onOpenExercise = onOpenExercise,
                    onDelete = { pendingDelete = workout },
                )
            }
        }

        if (!state.loading && state.workouts.isEmpty()) {
            item { EmptyState("Noch keine Trainings") }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }

    pendingDelete?.let { workout ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Training löschen?") },
            text = { Text("${Fmt.relativeDay(workout.date)} mit ${workout.totalSets} Sätzen wird gelöscht. Rekorde werden neu berechnet.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteWorkout(workout.id)
                    pendingDelete = null
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun CalendarCard(
    state: HistoryUiState,
    onChangeMonth: (Long) -> Unit,
    onOpenWorkout: (String) -> Unit,
) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onChangeMonth(-1) }) { Text("‹") }
            Text(
                "${monthName(state.month.monthValue)} ${state.month.year}",
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { onChangeMonth(1) }) { Text("›") }
        }
        Spacer(Modifier.height(8.dp))
        CalendarGrid(
            month = state.month,
            days = state.calendar,
            onSelect = { day -> onOpenWorkout(day.workoutId) },
        )
    }
}

@Composable
private fun WorkoutCard(
    workout: WorkoutDto,
    onOpenWorkout: (String) -> Unit,
    onOpenExercise: (String) -> Unit,
    onDelete: () -> Unit,
) {
            SectionCard(modifier = Modifier.clickable { onOpenWorkout(workout.id) }) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(Fmt.relativeDay(workout.date), style = MaterialTheme.typography.titleMedium)
                        workout.title?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        Fmt.volume(workout.volumeKg),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Nach Muskelgruppe gruppiert – wie in der Anforderung.
                val grouped = workout.exercises.groupBy { it.muscleGroups.firstOrNull() ?: "other" }
                grouped.forEach { (group, exercises) ->
                    SectionLabel(muscleLabel(group), modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    exercises.forEach { exercise ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenExercise(exercise.exerciseId) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(exercise.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    summarize(exercise),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (exercise.sets.any { it.source != "MANUAL" }) AiBadge(null)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${workout.totalSets} Sätze · ${workout.totalReps} Wdh" +
                            (workout.durationSec?.takeIf { it > 0 }?.let { " · ${Fmt.duration(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onDelete,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text("Löschen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
}

private fun summarize(exercise: com.gymapp.tracker.data.remote.WorkoutExerciseDto): String {
    if (exercise.sets.isEmpty()) return "–"
    // Gleiche aufeinanderfolgende Sätze zusammenfassen: "100 kg × 10 × 3"
    val groups = mutableListOf<Pair<String, Int>>()
    exercise.sets.forEach { set ->
        val label = listOfNotNull(
            set.weightKg?.takeIf { it > 0 }?.let { Fmt.weight(it) },
            set.reps?.takeIf { it > 0 }?.toString(),
            set.durationSec?.takeIf { it > 0 && set.reps == null }?.let { Fmt.duration(it) },
            set.distanceM?.takeIf { it > 0 }?.let { Fmt.distance(it) },
        ).joinToString(" × ").ifEmpty { "–" }
        val last = groups.lastOrNull()
        if (last != null && last.first == label) groups[groups.lastIndex] = label to last.second + 1
        else groups.add(label to 1)
    }
    return groups.joinToString(", ") { (label, count) -> if (count > 1) "$label × $count" else label }
}

@Composable
private fun CalendarGrid(month: YearMonth, days: List<CalendarDayDto>, onSelect: (CalendarDayDto) -> Unit) {
    val byDate = days.associateBy { it.date }
    val firstDayOfWeek = (month.atDay(1).dayOfWeek.value + 6) % 7 // Montag = 0
    val today = LocalDate.now()

    Column {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "D", "M", "D", "F", "S", "S").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        val cells = firstDayOfWeek + month.lengthOfMonth()
        val rows = (cells + 6) / 7
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (column in 0 until 7) {
                    val index = row * 7 + column
                    val dayNumber = index - firstDayOfWeek + 1
                    if (dayNumber < 1 || dayNumber > month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(dayNumber)
                        val entry = byDate[date.toString()]
                        val isToday = date == today
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    when {
                                        isToday -> MaterialTheme.colorScheme.primary
                                        entry != null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    },
                                )
                                .clickable(enabled = entry != null) { entry?.let(onSelect) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$dayNumber",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (entry != null) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isToday -> MaterialTheme.colorScheme.onPrimary
                                    entry != null -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if ((entry?.records ?: 0) > 0) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 4.dp)
                                        .size(4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.error),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun monthName(month: Int): String = listOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)[month - 1]
