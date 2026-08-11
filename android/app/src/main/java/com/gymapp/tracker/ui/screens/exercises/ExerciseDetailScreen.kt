package com.gymapp.tracker.ui.screens.exercises

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymapp.tracker.AppContainer
import com.gymapp.tracker.core.Fmt
import com.gymapp.tracker.core.Periods
import com.gymapp.tracker.core.RecordTypeLabels
import com.gymapp.tracker.core.formatRecordValue
import com.gymapp.tracker.data.remote.ExerciseStatsResponse
import com.gymapp.tracker.data.remote.PersonalRecordDto
import com.gymapp.tracker.ui.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

    /** Logs a record entered by hand – e.g. a PR from before the app was used. */
    fun addManualRecord(type: String, value: Double, weightKg: Double?, reps: Int?, achievedAt: String) {
        viewModelScope.launch {
            runCatching {
                container.exercises.addManualRecord(exerciseId, type, value, weightKg, reps, achievedAt)
            }.fold(
                onSuccess = { load() },
                onFailure = { _state.value = _state.value.copy(error = it.message) },
            )
        }
    }

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            runCatching { container.exercises.deleteRecord(id) }.onSuccess { load() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(viewModel: ExerciseDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val stats = state.data?.stats
    var showFormulaInfo by remember { mutableStateOf(false) }
    var showAddRecord by remember { mutableStateOf(false) }
    var pendingDeleteRecord by remember { mutableStateOf<PersonalRecordDto?>(null) }

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

                // --- Rekorde als Verlauf ------------------------------------
                item {
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(6.dp))
                                SectionLabel("Persönliche Rekorde")
                            }
                            TextButton(onClick = { showAddRecord = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Nachtragen", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        if (state.data!!.records.isEmpty()) {
                            Text(
                                "Noch keine Rekorde",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // Verlauf statt nur dem aktuellen Bestwert: gruppiert nach
                        // Rekordart, neueste zuerst – jede Verbesserung bleibt sichtbar.
                        state.data!!.records
                            .groupBy { it.type }
                            .toList()
                            .sortedByDescending { (_, entries) -> entries.maxOf { it.achievedAt } }
                            .forEach { (type, entries) ->
                                Text(
                                    RecordTypeLabels[type] ?: type,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                )
                                entries.sortedByDescending { it.achievedAt }.forEach { record ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(Fmt.dayLabel(record.achievedAt), style = MaterialTheme.typography.bodySmall)
                                                if (record.source == "MANUAL") {
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        "nachgetragen",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            formatRecordValue(record.type, record.value),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (record.source == "MANUAL") {
                                            IconButton(
                                                onClick = { pendingDeleteRecord = record },
                                                modifier = Modifier.size(28.dp).padding(start = 4.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Rekord löschen",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                    }
                }

                if (data.series.isEmpty()) {
                    item { EmptyState("Noch keine Daten") }
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

    if (showAddRecord) {
        AddRecordDialog(
            onDismiss = { showAddRecord = false },
            onSave = { type, value, weightKg, reps, achievedAt ->
                viewModel.addManualRecord(type, value, weightKg, reps, achievedAt)
                showAddRecord = false
            },
        )
    }

    pendingDeleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRecord = null },
            title = { Text("Nachgetragenen Rekord löschen?") },
            text = {
                Text("${RecordTypeLabels[record.type] ?: record.type} vom ${Fmt.dayLabel(record.achievedAt)} wird entfernt.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecord(record.id)
                    pendingDeleteRecord = null
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteRecord = null }) { Text("Abbrechen") } },
        )
    }
}

/** Manually log a record — e.g. a PR that happened before the app was in use. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRecordDialog(
    onDismiss: () -> Unit,
    onSave: (type: String, value: Double, weightKg: Double?, reps: Int?, achievedAt: String) -> Unit,
) {
    var type by remember { mutableStateOf("MAX_WEIGHT") }
    var valueText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var repsText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun parse(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rekord nachtragen") },
        text = {
            Column {
                Text(
                    "Für einen Bestwert, der nicht über ein geloggtes Training erfasst wurde.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Text("Art des Rekords", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                PeriodSelector(
                    periods = RecordTypeLabels.toList(),
                    selected = type,
                    onSelect = { type = it },
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text(recordValueLabel(type)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (type in listOf("MAX_WEIGHT", "MAX_REPS", "BEST_E1RM", "MAX_VOLUME_SET")) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = weightText,
                            onValueChange = { weightText = it },
                            label = { Text("Gewicht (optional)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = repsText,
                            onValueChange = { repsText = it },
                            label = { Text("Wdh (optional)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Datum: ${Fmt.dayLabel(selectedDate.toString())}")
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = parse(valueText)
                if (value == null || value <= 0) {
                    error = "Bitte einen gültigen Wert eingeben."
                } else {
                    onSave(type, value, parse(weightText), repsText.trim().toIntOrNull(), selectedDate.toString())
                }
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        selectedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") } },
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}

private fun recordValueLabel(type: String): String = when (type) {
    "MAX_REPS" -> "Wiederholungen"
    "LONGEST_DURATION" -> "Dauer (Sekunden)"
    "LONGEST_DISTANCE" -> "Distanz (Meter)"
    "MAX_VOLUME_SET", "MAX_VOLUME_SESSION" -> "Volumen (kg)"
    "BEST_E1RM" -> "Geschätztes 1RM (kg)"
    else -> "Gewicht (kg)"
}
