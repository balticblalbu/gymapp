package com.gymapp.tracker.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

data class DashboardUiState(
    val dashboard: DashboardDto? = null,
    val loading: Boolean = true,
    val offline: Boolean = false,
    val error: String? = null,
    val pendingChanges: Int = 0,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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

            // --- Heutiges Training -----------------------------------------
            item {
                SectionCard(accent = data.today.hasWorkout) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel("Training heute", accent = data.today.hasWorkout)
                        if (data.today.hasWorkout) TrendPill(data.comparisons.vsLastWorkout, filled = true)
                    }
                    Spacer(Modifier.height(12.dp))

                    if (!data.today.hasWorkout) {
                        Text("Heute noch kein Training erfasst", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Starte ein Training oder diktiere es einfach dem Telegram-Bot.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
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

            // --- Rekorde ---------------------------------------------------
            if (data.recentRecords.isNotEmpty()) {
                item {
                    SectionCard {
                        SectionLabel("Neueste Rekorde")
                        Spacer(Modifier.height(10.dp))
                        data.recentRecords.take(3).forEach { record ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
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
            }

            // --- Vergleiche -------------------------------------------------
            item {
                SectionCard {
                    SectionLabel("Entwicklung")
                    Spacer(Modifier.height(10.dp))
                    ComparisonRow("vs. letztes Training", data.comparisons.vsLastWorkout)
                    ComparisonRow("vs. letzte Woche", data.comparisons.vsLastWeek)
                    ComparisonRow("vs. letzter Monat", data.comparisons.vsLastMonth)
                    ComparisonRow("Kraftentwicklung", data.comparisons.strengthTrend)
                }
            }

            // --- Muskelgruppen ----------------------------------------------
            if (data.muscleGroups.isNotEmpty()) {
                item {
                    SectionCard {
                        SectionLabel("Muskelgruppen · 30 Tage")
                        Spacer(Modifier.height(8.dp))
                        val maxChange = data.muscleGroups.mapNotNull { it.changePercent }
                            .maxOfOrNull { abs(it) } ?: 1.0
                        data.muscleGroups.take(7).forEach { group ->
                            MuscleGroupRow(
                                name = muscleLabel(group.key),
                                fraction = if (group.changePercent != null) safeFraction(group.changePercent, maxChange)
                                else safeFraction(group.volumeKg, data.muscleGroups.maxOf { it.volumeKg }),
                                changePercent = group.changePercent,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Vergleich je Übung mit sich selbst (Median), gewichtet nach Sätzen – Ausreißer verzerren den Trend nicht.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
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
