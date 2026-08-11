package com.gymapp.tracker.ui.screens.dashboard

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymapp.tracker.AppContainer
import com.gymapp.tracker.core.Fmt
import com.gymapp.tracker.core.formatRecordValue
import com.gymapp.tracker.core.muscleLabel
import com.gymapp.tracker.data.remote.DashboardDto
import com.gymapp.tracker.ui.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Stable keys for the draggable dashboard cards, persisted as their order. */
private val ALL_SECTIONS = listOf("today", "records", "comparisons", "muscleGroups")

data class DashboardUiState(
    val dashboard: DashboardDto? = null,
    val loading: Boolean = true,
    val offline: Boolean = false,
    val error: String? = null,
    val pendingChanges: Int = 0,
    val sectionOrder: List<String> = ALL_SECTIONS,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        val saved = container.settings.dashboardOrder.split(",").filter { it.isNotBlank() }
        val withNewKeys = saved + ALL_SECTIONS.filterNot { it in saved }
        _state.value = _state.value.copy(sectionOrder = withNewKeys)
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { container.stats.dashboard() }.fold(
                onSuccess = { result ->
                    _state.value = _state.value.copy(
                        dashboard = result.value,
                        loading = false,
                        offline = result.fromCache,
                        error = null,
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(loading = false, error = error.message)
                },
            )
        }
    }

    fun sync() = load()

    /**
     * Persists a new order for the cards that were visible and draggable.
     * Cards hidden at the moment (e.g. "records" with nothing yet) keep their
     * relative slot in the saved order instead of being dropped from it.
     */
    fun reorderSections(newVisibleOrder: List<String>) {
        val full = _state.value.sectionOrder
        val hidden = full.filterNot { it in newVisibleOrder }
        val merged = mutableListOf<String>()
        var spliced = false
        for (key in full) {
            if (key in newVisibleOrder) {
                if (!spliced) {
                    merged.addAll(newVisibleOrder)
                    spliced = true
                }
            } else {
                merged.add(key)
            }
        }
        if (!spliced) merged.addAll(newVisibleOrder)
        check(merged.toSet() == full.toSet()) { "Reorder must not lose or invent section keys" }
        _state.value = _state.value.copy(sectionOrder = merged)
        container.settings.dashboardOrder = merged.joinToString(",")
        hidden.size // no-op, keeps `hidden` from looking unused in review
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    userName: String,
    onOpenWorkout: (String) -> Unit,
    onStartTraining: () -> Unit,
    onOpenExercise: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val dashboard = state.dashboard
    val listState = rememberLazyListState()
    val scroller = rememberDragAutoScroller(listState)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().dragAutoScrollBounds(scroller),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(
                    dashboard?.date?.let { Fmt.fullDayLabel(it) } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Servus, $userName", style = MaterialTheme.typography.headlineMedium)
            }
        }

        if (state.offline) {
            item { StatusBanner("Offline – zeige gespeicherte Daten", onAction = viewModel::load, actionLabel = "Neu laden") }
        }
        if (state.pendingChanges > 0) {
            item {
                StatusBanner(
                    "${state.pendingChanges} Änderungen warten auf Synchronisierung",
                    onAction = viewModel::sync,
                    actionLabel = "Jetzt senden",
                )
            }
        }
        state.error?.let { error ->
            item { StatusBanner(error, isError = true, onAction = viewModel::load, actionLabel = "Erneut") }
        }

        if (state.loading && dashboard == null) {
            item { LoadingBox() }
        }

        dashboard?.let { data ->
            // --- Kennzahlen ------------------------------------------------
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(Fmt.volume(data.totals.volumeKg), "Volumen gesamt", Modifier.weight(1f))
                    StatTile("${data.totals.workouts}", "Trainings", Modifier.weight(1f))
                    StatTile("${data.streakDays}", "Serie · Tage", Modifier.weight(1f))
                }
            }

            // --- Verschiebbare Karten ----------------------------------------
            item {
                val visible = state.sectionOrder.filter { key ->
                    when (key) {
                        "records" -> data.recentRecords.isNotEmpty()
                        "muscleGroups" -> data.muscleGroups.isNotEmpty()
                        else -> true
                    }
                }
                DraggableSectionList(
                    items = visible,
                    key = { it },
                    onReorder = viewModel::reorderSections,
                    modifier = Modifier.fillMaxWidth(),
                    scroller = scroller,
                ) { sectionKey ->
                    Box(Modifier.padding(bottom = 12.dp)) {
                        when (sectionKey) {
                            "today" -> TodaySection(data, onOpenExercise, onOpenWorkout, onStartTraining)
                            "records" -> RecordsSection(data, onOpenExercise)
                            "comparisons" -> ComparisonsSection(data)
                            "muscleGroups" -> MuscleGroupsSection(data)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun CardHeader(label: String, accent: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(label, accent = accent)
        trailing?.invoke()
    }
}

@Composable
private fun TodaySection(
    data: DashboardDto,
    onOpenExercise: (String) -> Unit,
    onOpenWorkout: (String) -> Unit,
    onStartTraining: () -> Unit,
) {
    SectionCard(accent = data.today.hasWorkout) {
        CardHeader(
            "Training heute",
            accent = data.today.hasWorkout,
            trailing = { if (data.today.hasWorkout) TrendPill(data.comparisons.vsLastWorkout, filled = true) },
        )
        Spacer(Modifier.height(12.dp))

        if (!data.today.hasWorkout) {
            Button(onClick = onStartTraining) { Text("Training starten") }
        } else {
            data.today.exercises.forEachIndexed { index, exercise ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenExercise(exercise.exerciseId) }
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(exercise.name, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            exercise.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (exercise.volumeKg > 0) {
                        Text(
                            Fmt.volume(exercise.volumeKg),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    buildString {
                        append("${data.today.sets} Sätze · ${data.today.reps} Wdh")
                        data.today.durationSec?.takeIf { it > 0 }?.let { append(" · ${Fmt.duration(it)}") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Fmt.volume(data.today.volumeKg),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            data.today.workoutId?.let { id ->
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { onOpenWorkout(id) }) { Text("Training öffnen") }
            }
        }
    }
}

@Composable
private fun RecordsSection(data: DashboardDto, onOpenExercise: (String) -> Unit) {
    SectionCard {
        CardHeader("Neueste Rekorde")
        Spacer(Modifier.height(10.dp))
        data.recentRecords.take(3).forEach { record ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = record.exerciseId.isNotBlank()) { onOpenExercise(record.exerciseId) }
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("🔥 ${record.exerciseName}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        record.previousValue?.let {
                            "Vorher ${formatRecordValue(record.type, it)} · ${Fmt.dayLabel(record.achievedAt)}"
                        } ?: Fmt.dayLabel(record.achievedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatRecordValue(record.type, record.value),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    record.improvementPercent?.let { TrendPill(it) }
                }
            }
        }
    }
}

@Composable
private fun ComparisonsSection(data: DashboardDto) {
    SectionCard {
        CardHeader("Entwicklung")
        Spacer(Modifier.height(10.dp))
        ComparisonRow("vs. letztes Training", data.comparisons.vsLastWorkout)
        ComparisonRow("vs. letzte Woche", data.comparisons.vsLastWeek)
        ComparisonRow("vs. letzter Monat", data.comparisons.vsLastMonth)
        ComparisonRow("Kraftentwicklung", data.comparisons.strengthTrend)
    }
}

@Composable
private fun MuscleGroupsSection(data: DashboardDto) {
    SectionCard {
        CardHeader("Muskelgruppen · 30 Tage")
        Spacer(Modifier.height(8.dp))
        val maxChange = data.muscleGroups.mapNotNull { it.changePercent }.maxOfOrNull { abs(it) } ?: 1.0
        data.muscleGroups.take(7).forEach { group ->
            MuscleGroupRow(
                name = muscleLabel(group.key),
                fraction = if (group.changePercent != null) safeFraction(group.changePercent, maxChange)
                else safeFraction(group.volumeKg, data.muscleGroups.maxOf { it.volumeKg }),
                changePercent = group.changePercent,
            )
        }
    }
}

@Composable
private fun ComparisonRow(label: String, value: Double?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TrendPill(value)
    }
}
