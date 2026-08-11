package com.gymapp.tracker.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymapp.tracker.AppContainer
import com.gymapp.tracker.data.remote.UpdateUserRequest
import com.gymapp.tracker.data.remote.UserDto
import com.gymapp.tracker.ui.components.DraggableSectionList
import com.gymapp.tracker.ui.components.PeriodSelector
import com.gymapp.tracker.ui.components.SectionCard
import com.gymapp.tracker.ui.components.SectionLabel
import com.gymapp.tracker.ui.theme.AppTheme
import com.gymapp.tracker.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Stable keys for the draggable Profil cards, persisted as their order. */
private val ALL_SECTIONS = listOf("claude", "display", "update", "data")

data class SettingsUiState(
    val user: UserDto? = null,
    val updateUrl: String = "",
    val updateStatus: String? = null,
    val updateAvailable: com.gymapp.tracker.data.update.Updater.Result.Available? = null,
    val checkingUpdate: Boolean = false,
    val currentVersion: String = "",
    val apiKey: String = "",
    val hasApiKey: Boolean = false,
    val model: String = "claude-opus-5",
    val message: String? = null,
    val error: String? = null,
    val sectionOrder: List<String> = ALL_SECTIONS,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        val saved = container.settings.settingsOrder.split(",").filter { it.isNotBlank() }
        val withNewKeys = saved + ALL_SECTIONS.filterNot { it in saved }
        _state.value = _state.value.copy(sectionOrder = withNewKeys)
        load()
    }

    fun load() {
        _state.value = _state.value.copy(
            user = container.settings.profile(),
            apiKey = container.settings.apiKey.orEmpty(),
            hasApiKey = container.settings.hasApiKey,
            model = container.settings.model,
            updateUrl = container.settings.updateUrl,
            currentVersion = container.updater.currentVersion,
        )
    }

    fun reorderSections(newOrder: List<String>) {
        check(newOrder.toSet() == ALL_SECTIONS.toSet()) { "Reorder must not lose or invent section keys" }
        _state.value = _state.value.copy(sectionOrder = newOrder)
        container.settings.settingsOrder = newOrder.joinToString(",")
    }

    fun onUpdateUrlChange(value: String) {
        _state.value = _state.value.copy(updateUrl = value)
    }

    fun checkForUpdate() {
        container.settings.updateUrl = _state.value.updateUrl
        _state.value = _state.value.copy(checkingUpdate = true, updateStatus = null, updateAvailable = null)
        viewModelScope.launch {
            when (val result = container.updater.check(_state.value.updateUrl)) {
                is com.gymapp.tracker.data.update.Updater.Result.UpToDate ->
                    _state.value = _state.value.copy(checkingUpdate = false, updateStatus = "Du hast die neueste Version.")
                is com.gymapp.tracker.data.update.Updater.Result.Available ->
                    _state.value = _state.value.copy(
                        checkingUpdate = false,
                        updateAvailable = result,
                        updateStatus = "Neu verfügbar: ${result.versionName}",
                    )
                is com.gymapp.tracker.data.update.Updater.Result.Failed ->
                    _state.value = _state.value.copy(checkingUpdate = false, updateStatus = result.reason)
            }
        }
    }

    fun installUpdate() {
        val available = _state.value.updateAvailable ?: return
        _state.value = _state.value.copy(checkingUpdate = true, updateStatus = "Lade herunter…")
        viewModelScope.launch {
            val error = container.updater.downloadAndInstall(available.apkUrl)
            _state.value = _state.value.copy(
                checkingUpdate = false,
                updateStatus = error ?: "Installation gestartet.",
            )
        }
    }

    fun onApiKeyChange(value: String) {
        _state.value = _state.value.copy(apiKey = value)
    }

    fun saveApiKey() {
        container.settings.apiKey = _state.value.apiKey
        _state.value = _state.value.copy(
            hasApiKey = container.settings.hasApiKey,
            message = if (container.settings.hasApiKey) "API-Key gespeichert" else "API-Key entfernt",
        )
    }

    /** Throws away unsaved edits, restoring the key that is actually stored. */
    fun discardApiKeyDraft() {
        _state.value = _state.value.copy(apiKey = container.settings.apiKey.orEmpty())
    }

    fun onModelChange(value: String) {
        container.settings.model = value
        _state.value = _state.value.copy(model = value)
    }

    fun updateUser(request: UpdateUserRequest) {
        _state.value = _state.value.copy(user = container.settings.update(request), message = "Gespeichert")
    }

    fun export(context: Context, format: String) {
        viewModelScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { container.export.export(format) }
                val file = File(context.cacheDir, "trainings-${System.currentTimeMillis()}.$format")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                file
            }.fold(
                onSuccess = { shareFile(context, it, format) },
                onFailure = { _state.value = _state.value.copy(error = it.message ?: "Export fehlgeschlagen") },
            )
        }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.export.deleteEverything() }
            _state.value = _state.value.copy(message = "Alle Trainingsdaten gelöscht")
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null, error = null) }
}

/** "sk-ant-api03-…kD9x" — enough to recognise the key, not enough to use it. */
private fun maskedKey(key: String): String = when {
    key.isBlank() -> "Kein Key hinterlegt"
    key.length <= 12 -> "•••• gespeichert"
    else -> "${key.take(10)}…${key.takeLast(4)} · gespeichert"
}

private fun shareFile(context: Context, file: File, format: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (format == "csv") "text/csv" else "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Trainingsdaten teilen"))
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    // Collapsed by default once a key is saved, so opening Profil never shows
    // (or half-shows) the key. Starts expanded only when there is none yet.
    var editingKey by rememberSaveable(state.hasApiKey) { mutableStateOf(!state.hasApiKey) }
    var nameDraft by remember(state.user?.name) { mutableStateOf(state.user?.name ?: "") }

    LaunchedEffect(state.message, state.error) {
        val text = state.message ?: state.error
        if (text != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Profil", style = MaterialTheme.typography.headlineMedium) }

        // --- Verschiebbare Karten --------------------------------------------
        item {
            DraggableSectionList(
                items = state.sectionOrder,
                key = { it },
                onReorder = viewModel::reorderSections,
                modifier = Modifier.fillMaxWidth(),
            ) { sectionKey ->
                Box(Modifier.padding(bottom = 12.dp)) {
                    when (sectionKey) {
                        "claude" -> ClaudeSection(
                            state = state,
                            viewModel = viewModel,
                            editingKey = editingKey,
                            onEditingKeyChange = { editingKey = it },
                            keyVisible = keyVisible,
                            onKeyVisibleChange = { keyVisible = it },
                        )
                        "display" -> DisplaySection(
                            state = state,
                            viewModel = viewModel,
                            themeMode = themeMode,
                            onThemeChange = onThemeChange,
                            nameDraft = nameDraft,
                            onNameDraftChange = { nameDraft = it },
                        )
                        "update" -> UpdateSection(state = state, viewModel = viewModel)
                        "data" -> DataSection(
                            viewModel = viewModel,
                            context = context,
                            onDeleteRequested = { confirmDelete = true },
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Workout Tracker · Version ${com.gymapp.tracker.BuildConfig.VERSION_NAME}\n" +
                    "Läuft vollständig auf dem Gerät – nur die Auswertung fragt Claude.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item { Spacer(Modifier.height(80.dp)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Wirklich alles löschen?") },
            text = {
                Text(
                    "Alle Trainings und Rekorde werden unwiderruflich gelöscht. Der Übungskatalog " +
                        "bleibt erhalten. Exportiere vorher, wenn du die Daten behalten willst.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteEverything()
                }) { Text("Endgültig löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
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
private fun ClaudeSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    editingKey: Boolean,
    onEditingKeyChange: (Boolean) -> Unit,
    keyVisible: Boolean,
    onKeyVisibleChange: (Boolean) -> Unit,
) {
            SectionCard(accent = !state.hasApiKey) {
                CardHeader(
                    "Claude API",
                    accent = !state.hasApiKey,
                    trailing = {
                        Text(
                            if (state.hasApiKey) "Aktiv" else "Kein Key",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.hasApiKey) AppTheme.colors.positive
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Der Key liegt verschlüsselt auf diesem Gerät und geht nur an die Anthropic-API. " +
                        "Die Spracherkennung läuft auf dem Handy – es wird nie Audio übertragen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (!editingKey) {
                    // Collapsed: no key material on screen at all, just the
                    // fact that one is stored and a way to change it.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            maskedKey(state.apiKey),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            onKeyVisibleChange(false)
                            onEditingKeyChange(true)
                        }) { Text("Ändern") }
                    }
                } else {
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = viewModel::onApiKeyChange,
                        label = { Text("sk-ant-…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { onKeyVisibleChange(!keyVisible) }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (keyVisible) "Key verbergen" else "Key anzeigen",
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.saveApiKey()
                            onEditingKeyChange(false)
                        }) { Text("Key speichern") }
                        if (state.hasApiKey) {
                            TextButton(onClick = {
                                viewModel.discardApiKeyDraft()
                                onEditingKeyChange(false)
                            }) { Text("Abbrechen") }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("Modell")
                Spacer(Modifier.height(8.dp))
                PeriodSelector(
                    periods = listOf(
                        "claude-opus-5" to "Opus 5",
                        "claude-sonnet-5" to "Sonnet 5",
                        "claude-haiku-4-5" to "Haiku 4.5",
                    ),
                    selected = state.model,
                    onSelect = viewModel::onModelChange,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Opus 5 versteht freie Formulierungen am besten; Sonnet und Haiku sind günstiger und schneller.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
}

@Composable
private fun DisplaySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    nameDraft: String,
    onNameDraftChange: (String) -> Unit,
) {
            SectionCard {
                CardHeader("Anzeige")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = onNameDraftChange,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = { viewModel.updateUser(UpdateUserRequest(name = nameDraft)) }) {
                            Text("OK")
                        }
                    },
                )

                Spacer(Modifier.height(16.dp))
                SectionLabel("Erscheinungsbild")
                Spacer(Modifier.height(10.dp))
                PeriodSelector(
                    periods = listOf(
                        ThemeMode.SYSTEM.name to "System",
                        ThemeMode.LIGHT.name to "Hell",
                        ThemeMode.DARK.name to "Dunkel",
                    ),
                    selected = themeMode.name,
                    onSelect = { onThemeChange(ThemeMode.valueOf(it)) },
                )

                Spacer(Modifier.height(16.dp))
                SectionLabel("Einheiten")
                Spacer(Modifier.height(10.dp))
                PeriodSelector(
                    periods = listOf("KG" to "Kilogramm", "LB" to "Pfund"),
                    selected = state.user?.unitSystem ?: "KG",
                    onSelect = { viewModel.updateUser(UpdateUserRequest(unitSystem = it)) },
                )
            }
}

@Composable
private fun UpdateSection(state: SettingsUiState, viewModel: SettingsViewModel) {
            SectionCard {
                CardHeader(
                    "App-Update",
                    trailing = {
                        Text(
                            state.currentVersion,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Adresse einer JSON-Datei mit Versionsinfo oder direkt einer .apk. " +
                        "Der Link muss die Datei sofort liefern – Filehoster mit Downloadseite " +
                        "funktionieren nicht.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.updateUrl,
                    onValueChange = viewModel::onUpdateUrlChange,
                    label = { Text("https://…/update.json") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::checkForUpdate,
                        enabled = !state.checkingUpdate && state.updateUrl.isNotBlank(),
                    ) { Text("Nach Update suchen") }

                    if (state.updateAvailable != null) {
                        Button(onClick = viewModel::installUpdate, enabled = !state.checkingUpdate) {
                            Text("Installieren")
                        }
                    }
                }
                if (state.checkingUpdate) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.updateStatus?.let { status ->
                    Spacer(Modifier.height(10.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
                state.updateAvailable?.notes?.let { notes ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
}

@Composable
private fun DataSection(
    viewModel: SettingsViewModel,
    context: Context,
    onDeleteRequested: () -> Unit,
) {
            SectionCard {
                CardHeader("Daten")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Deine Trainings liegen ausschließlich auf diesem Gerät. Exportiere sie " +
                        "regelmäßig – das ist deine einzige Sicherung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.export(context, "csv") }, modifier = Modifier.weight(1f)) {
                        Text("CSV")
                    }
                    OutlinedButton(onClick = { viewModel.export(context, "json") }, modifier = Modifier.weight(1f)) {
                        Text("JSON")
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDeleteRequested, modifier = Modifier.fillMaxWidth()) {
                    Text("Alle Trainingsdaten löschen", color = MaterialTheme.colorScheme.error)
                }
            }
}
