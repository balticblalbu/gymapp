package com.gymapp.tracker.ui.screens.exercises

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymapp.tracker.AppContainer
import com.gymapp.tracker.core.Fmt
import com.gymapp.tracker.core.Periods
import com.gymapp.tracker.core.RecordTypeLabels
import com.gymapp.tracker.core.formatRecordValue
import com.gymapp.tracker.data.remote.ExerciseStatsResponse
import com.gymapp.tracker.ui.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExerciseDetailUiState(
    val data: ExerciseStatsResponse? = null,
    val period: String = "90d",
    val loading: Boolean = true,
    val error: String? = null,
)

class ExerciseDetailViewModel(
    private val container: AppContainer,
    private val exerciseId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ExerciseDetailUiState())
    val state: StateFlow<ExerciseDetailUiState> = _state.asStateFlow()

    init { load() }

    fun setPeriod(period: String) {
        _state.value = _state.value.copy(period = period)
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { container.exercises.stats(exerciseId, _state.value.period) }.fold(
                onSuccess = { _state.value = _state.value.copy(data = it, loading = false) },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(viewModel: ExerciseDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val stats = state.data?.stats
    var showFormulaInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stats?.name ?: "Übung", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PeriodSelector(Periods, state.period, viewModel::setPeriod)
            }

            state.error?.let { item { StatusBanner(it, isError = true, onAction = viewModel::load, actionLabel = "Erneut") } }
            if (state.loading && stats == null) item { LoadingBox() }

            stats?.let { data ->
                // --- 1RM-Verlauf ------------------------------------------
                item {
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionLabel("Geschätztes 1RM")
                            TrendPill(data.progressPercent)
                        }
                        Spacer(Modifier.height(12.dp))
                        LineChart(
                            points = data.series.mapNotNull { point ->
                                point.bestE1rm?.let {
                                    ChartPoint(
                                        label = Fmt.shortDay(point.date),
                                        value = it,
                                        detail = "${point.sets} Sätze · ${Fmt.volume(point.totalVolumeKg)}",
                                    )
                                }
                            },
                            valueFormatter = { Fmt.weight(it) },
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showFormulaInfo = true }, contentPadding = PaddingValues(0.dp)) {
                            Text("Wie wird das berechnet?", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // --- Kennzahlen -------------------------------------------
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(Fmt.weight(data.personalBestKg), "Bestleistung", Modifier.weight(1f))
                            StatTile(
                                data.bestReps?.let { "${it.reps} × ${Fmt.weight(it.weightKg)}" } ?: "–",
                                "Beste Wdh",
                                Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(Fmt.volume(data.bestVolumeKg ?: 0.0), "Bestes Volumen", Modifier.weight(1f))
                            StatTile(Fmt.weight(data.avgWeightKg), "Ø Gewicht", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("${data.totalSets}", "Sätze", Modifier.weight(1f))
                            StatTile("${data.sessions}", "Einheiten", Modifier.weight(1f))
                            StatTile(Fmt.number(data.frequencyPerWeek, 1), "pro Woche", Modifier.weight(1f))
                        }
                    }
                }

                // --- Volumen ----------------------------------------------
                item {
                    SectionCard {
                        SectionLabel("Volumen über Zeit")
                        Spacer(Modifier.height(12.dp))
                        BarChart(
                            points = data.series.map {
                                ChartPoint(Fmt.shortDay(it.date), it.totalVolumeKg, "${it.sets} Sätze")
                            }.takeLast(12),
                        )
                    }
                }

                // --- Gewicht / Wiederholungen -----------------------------
                item {
                    SectionCard {
                        SectionLabel("Gewicht über Zeit")
                        Spacer(Modifier.height(12.dp))
                        LineChart(
                            points = data.series.mapNotNull { point ->
                                point.maxWeightKg?.let {
                                    ChartPoint(Fmt.shortDay(point.date), it, "${point.totalReps} Wdh")
                                }
                            },
                            height = 120.dp,
                            valueFormatter = { Fmt.weight(it) },
                        )
                    }
                }

                item {
                    SectionCard {
                        SectionLabel("Wiederholungen über Zeit")
                        Spacer(Modifier.height(12.dp))
                        LineChart(
                            points = data.series.map {
                                ChartPoint(Fmt.shortDay(it.date), it.totalReps.toDouble(), "${it.sets} Sätze")
                            },
                            height = 110.dp,
                            valueFormatter = { "${Fmt.number(it)} Wdh" },
                        )
                    }
                }

                // --- Rekorde -----------------------------------------------
                if (state.data!!.records.isNotEmpty()) {
                    item {
                        SectionCard {
                            SectionLabel("Persönliche Rekorde")
                            Spacer(Modifier.height(8.dp))
                            state.data!!.records.forEach { record ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            RecordTypeLabels[record.type] ?: record.type,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            Fmt.dayLabel(record.achievedAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        formatRecordValue(record.type, record.value),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            if (data.hasMeasuredOneRm) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Enthält einen echten 1RM-Versuch (nicht nur geschätzt).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                if (data.series.isEmpty()) {
                    item { EmptyState("Noch keine Daten", "Erfasse diese Übung im Training oder per Telegram.") }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showFormulaInfo) {
        AlertDialog(
            onDismissRequest = { showFormulaInfo = false },
            title = { Text("Berechnung") },
            text = {
                Text(
                    "Geschätztes 1RM nach der Epley-Formel:\n\n" +
                        "1RM = Gewicht × (1 + Wiederholungen / 30)\n\n" +
                        "Ab 12 Wiederholungen wird der Wert gedeckelt, weil die Formel darüber deutlich überschätzt. " +
                        "Ein echter 1RM-Versuch wird separat markiert und nicht mit einer Schätzung vermischt.\n\n" +
                        "Volumen = Gewicht × Wiederholungen, aufsummiert über alle Sätze.\n\n" +
                        "Der Fortschritt vergleicht den Median der Leistungswerte im gewählten Zeitraum mit dem " +
                        "gleich langen Zeitraum davor – so verzerrt ein einzelner starker Satz das Ergebnis nicht.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { showFormulaInfo = false }) { Text("Verstanden") } },
        )
    }
}
