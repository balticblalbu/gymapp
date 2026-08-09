package com.gymapp.tracker.ui.screens.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymapp.tracker.AppContainer
import com.gymapp.tracker.data.remote.AiParseResponse
import com.gymapp.tracker.ui.components.SectionCard
import com.gymapp.tracker.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Voice input for the app.
 *
 * Speech recognition runs **on the device** via Android's own recogniser — no
 * audio ever leaves the phone and no speech API key is needed. The recognised
 * text is sent to the backend, where Claude turns it into structured training
 * data through the same pipeline the bot uses.
 */
data class VoiceUiState(
    val listening: Boolean = false,
    val partialText: String = "",
    val text: String = "",
    val sending: Boolean = false,
    val reply: String? = null,
    val error: String? = null,
    val savedSomething: Boolean = false,
)

class VoiceViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    fun onListeningStarted() {
        _state.value = _state.value.copy(listening = true, error = null, reply = null, partialText = "")
    }

    fun onPartial(text: String) {
        _state.value = _state.value.copy(partialText = text)
    }

    fun onRecognised(text: String, autoSend: Boolean) {
        _state.value = _state.value.copy(listening = false, text = text, partialText = "")
        if (autoSend && text.isNotBlank()) send(spoken = true)
    }

    fun onRecognitionError(message: String) {
        _state.value = _state.value.copy(listening = false, partialText = "", error = message)
    }

    fun onTextChange(value: String) {
        _state.value = _state.value.copy(text = value, error = null)
    }

    fun send(spoken: Boolean = false) {
        val text = _state.value.text.trim()
        if (text.isEmpty()) return
        _state.value = _state.value.copy(sending = true, error = null, reply = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { container.ai.parse(text, spoken) } }.fold(
                onSuccess = { response ->
                    _state.value = _state.value.copy(
                        sending = false,
                        reply = response.message,
                        savedSomething = response.saved,
                        text = if (response.saved) "" else text,
                    )
                },
                onFailure = { error ->
                    // Some exceptions (NetworkOnMainThread, JSON parse) carry no
                    // message; showing the class name beats showing nothing.
                    val reason = error.message?.takeIf { it.isNotBlank() }
                        ?: error::class.simpleName
                        ?: "Unbekannter Fehler"
                    _state.value = _state.value.copy(sending = false, error = reason)
                },
            )
        }
    }

    fun dismissReply() {
        _state.value = _state.value.copy(reply = null, savedSomething = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoiceSheet(
    viewModel: VoiceViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val imeVisible = WindowInsets.isImeVisible
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    val recogniser = rememberSpeechRecogniser(
        onStart = viewModel::onListeningStarted,
        onPartial = viewModel::onPartial,
        onResult = { viewModel.onRecognised(it, autoSend = true) },
        onError = viewModel::onRecognitionError,
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) recogniser.start() else viewModel.onRecognitionError("Ohne Mikrofonzugriff geht keine Spracheingabe.")
    }

    LaunchedEffect(state.savedSomething) {
        if (state.savedSomething) onSaved()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    // When the keyboard opens, scroll the sheet so the field stays visible.
    LaunchedEffect(imeVisible) {
        if (imeVisible) scrollState.animateScrollTo(scrollState.maxValue)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .imePadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Training diktieren", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Sprich einfach los – zum Beispiel: „Heute Bankdrücken, drei Sätze mit 100 Kilo für zehn Wiederholungen.\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))
            MicButton(
                listening = state.listening,
                enabled = !state.sending,
                onClick = {
                    if (state.listening) {
                        recogniser.stop()
                    } else if (hasPermission) {
                        recogniser.start()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )

            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    state.listening && state.partialText.isNotBlank() -> state.partialText
                    state.listening -> "Ich höre zu…"
                    state.sending -> "Claude wertet aus…"
                    else -> "Antippen und sprechen"
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = if (state.listening) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChange,
                label = { Text("… oder tippen") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                trailingIcon = {
                    IconButton(
                        onClick = { viewModel.send(spoken = false) },
                        enabled = state.text.isNotBlank() && !state.sending,
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Senden")
                    }
                },
            )

            if (state.sending) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            state.reply?.let { reply ->
                Spacer(Modifier.height(16.dp))
                SectionCard(accent = state.savedSomething) {
                    SectionLabel(if (state.savedSomething) "Gespeichert" else "Antwort", accent = state.savedSomething)
                    Spacer(Modifier.height(8.dp))
                    Text(reply, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::dismissReply) { Text("Weiter erfassen") }
            }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "mic-scale",
    )

    Box(
        Modifier
            .size(96.dp)
            .scale(pulse)
            .clip(RoundedCornerShape(50))
            .background(
                if (listening) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(96.dp)) {
            Icon(
                Icons.Default.Mic,
                contentDescription = if (listening) "Aufnahme stoppen" else "Sprachaufnahme starten",
                modifier = Modifier.size(40.dp),
                tint = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Thin wrapper so the composable does not deal with the recogniser lifecycle. */
class SpeechController(private val start: () -> Unit, private val stop: () -> Unit) {
    fun start() = start.invoke()
    fun stop() = stop.invoke()
}

@Composable
private fun rememberSpeechRecogniser(
    onStart: () -> Unit,
    onPartial: (String) -> Unit,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
): SpeechController {
    val context = LocalContext.current
    val recogniser = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    DisposableEffect(recogniser) {
        onDispose { recogniser?.destroy() }
    }

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onStart()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onError("Nichts verstanden. Bitte nochmal.") else onResult(text)
            }

            override fun onError(error: Int) {
                onError(describeSpeechError(error))
            }
        }
    }

    return remember(recogniser) {
        SpeechController(
            start = {
                if (recogniser == null) {
                    onError("Auf diesem Gerät ist keine Spracherkennung verfügbar. Bitte tippe stattdessen.")
                } else {
                    recogniser.setRecognitionListener(listener)
                    recogniser.startListening(buildRecognitionIntent(context))
                }
            },
            stop = { recogniser?.stopListening() },
        )
    }
}

private fun buildRecognitionIntent(context: Context): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMANY.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Give the speaker room to finish a sentence before it cuts off.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
    }

private fun describeSpeechError(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "Problem mit der Audioaufnahme."
    SpeechRecognizer.ERROR_CLIENT -> "Spracherkennung konnte nicht gestartet werden."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Kein Mikrofonzugriff erlaubt."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        "Die Spracherkennung braucht kurz Internet."
    SpeechRecognizer.ERROR_NO_MATCH -> "Nichts verstanden. Bitte nochmal."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Spracherkennung ist gerade beschäftigt."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ich habe nichts gehört."
    else -> "Spracherkennung fehlgeschlagen ($code)."
}
