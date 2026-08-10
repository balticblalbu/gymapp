package com.gymapp.tracker.data.ai

import com.gymapp.tracker.core.ai.normalizeNumberWords
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Talks to the Anthropic Messages API straight from the phone.
 *
 * Request shape notes — each of these is a 400 if you get it wrong on Opus 5:
 *  - No `temperature` / `top_p` / `top_k`; sampling parameters are rejected.
 *  - `effort` goes *inside* `output_config`, not at the top level.
 *  - Always check `stop_reason` before reading `content`: on a refusal the
 *    content array is empty.
 *
 * `effort: "low"` is deliberate — pulling sets and reps out of one sentence is
 * not a reasoning-heavy task, and low effort keeps latency and cost down.
 */
class ClaudeClient(
    private val apiKeyProvider: () -> String?,
    private val model: () -> String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    class MissingKey : Exception("Kein Anthropic-API-Key hinterlegt. Trage ihn unter Profil ein.")
    class Refused : Exception("Die Anfrage wurde vom Modell abgelehnt.")
    class Failed(message: String) : Exception(message)

    suspend fun parseWorkout(text: String, knownExercises: List<String>, today: LocalDate): ParsedMessage {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: throw MissingKey()
        // Spelled-out numbers become digits first, so the model never has to
        // decide whether "zehn" is a count or part of a name.
        val prepared = normalizeNumberWords(text)

        val body = buildJsonObject {
            put("model", model())
            put("max_tokens", 4096)
            put("system", buildSystemPrompt(knownExercises, today))
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", prepared)
                    },
                )
            }
            putJsonObject("output_config") {
                put("effort", "low")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", WORKOUT_SCHEMA)
                }
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        // OkHttp's execute() blocks, so it must never run on the main thread:
        // Android kills such a call with a NetworkOnMainThreadException whose
        // message is empty, which looks like "nothing happened" in the UI.
        val raw = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw Failed(describeHttpError(response.code, payload))
                    }
                    payload
                }
            } catch (error: IOException) {
                throw Failed("Keine Verbindung zur Anthropic-API. Ist das Handy online?")
            }
        }

        val message = runCatching { json.decodeFromString(AnthropicMessage.serializer(), raw) }
            .getOrElse { throw Failed("Unerwartete Antwort der API.") }

        if (message.stopReason == "refusal") throw Refused()

        val content = message.content.firstOrNull { it.type == "text" }?.text
            ?: throw Failed("Leere Antwort vom Modell.")

        return runCatching { json.decodeFromString(ParsedMessage.serializer(), content) }
            .getOrElse { throw Failed("Die Antwort passte nicht zum erwarteten Format.") }
    }

    private fun describeHttpError(code: Int, payload: String): String {
        val apiMessage = runCatching {
            json.decodeFromString(AnthropicError.serializer(), payload).error?.message
        }.getOrNull()
        return when (code) {
            401 -> "Der Anthropic-API-Key wurde abgelehnt. Bitte in den Einstellungen prüfen."
            429 -> "Zu viele Anfragen an die API. Kurz warten und nochmal."
            in 500..599 -> "Die Anthropic-API ist gerade nicht erreichbar."
            else -> apiMessage ?: "API-Fehler ($code)."
        }
    }
}

// --- wire types -------------------------------------------------------------

@Serializable
private data class AnthropicMessage(
    val content: List<ContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val model: String? = null,
)

@Serializable
private data class ContentBlock(val type: String, val text: String? = null)

@Serializable
private data class AnthropicError(val error: ErrorDetail? = null)

@Serializable
private data class ErrorDetail(val type: String? = null, val message: String? = null)

// --- the structure Claude has to produce -------------------------------------

@Serializable
data class ParsedSet(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val isWarmup: Boolean = false,
)

@Serializable
data class ParsedExercise(
    val name: String,
    val muscleGroups: List<String> = emptyList(),
    val type: String = "STRENGTH",
    val sets: List<ParsedSet> = emptyList(),
)

@Serializable
data class ParsedMessage(
    /** log_workout | create_exercise | correction | query | unknown */
    val intent: String = "unknown",
    /** Verbatim date wording, e.g. "gestern". Resolved locally, not by the model. */
    val dateExpression: String? = null,
    val exercises: List<ParsedExercise> = emptyList(),
    val confidence: Double = 0.0,
    val clarificationQuestion: String? = null,
    val note: String? = null,
)

private val WORKOUT_SCHEMA: JsonElement = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") {
        add(kotlinx.serialization.json.JsonPrimitive("intent"))
        add(kotlinx.serialization.json.JsonPrimitive("exercises"))
        add(kotlinx.serialization.json.JsonPrimitive("confidence"))
    }
    putJsonObject("properties") {
        putJsonObject("intent") {
            put("type", "string")
            putJsonArray("enum") {
                listOf("log_workout", "create_exercise", "correction", "query", "unknown").forEach {
                    add(kotlinx.serialization.json.JsonPrimitive(it))
                }
            }
        }
        putJsonObject("dateExpression") {
            putJsonArray("type") {
                add(kotlinx.serialization.json.JsonPrimitive("string"))
                add(kotlinx.serialization.json.JsonPrimitive("null"))
            }
        }
        putJsonObject("confidence") {
            // Numerical constraints (minimum/maximum/multipleOf) are not
            // supported by structured outputs and make the request fail.
            // The range is stated in the prompt instead and clamped locally.
            put("type", "number")
        }
        putJsonObject("clarificationQuestion") {
            putJsonArray("type") {
                add(kotlinx.serialization.json.JsonPrimitive("string"))
                add(kotlinx.serialization.json.JsonPrimitive("null"))
            }
        }
        putJsonObject("note") {
            putJsonArray("type") {
                add(kotlinx.serialization.json.JsonPrimitive("string"))
                add(kotlinx.serialization.json.JsonPrimitive("null"))
            }
        }
        putJsonObject("exercises") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonArray("required") {
                    add(kotlinx.serialization.json.JsonPrimitive("name"))
                    add(kotlinx.serialization.json.JsonPrimitive("sets"))
                }
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("type") {
                        put("type", "string")
                        putJsonArray("enum") {
                            listOf("STRENGTH", "BODYWEIGHT", "CARDIO", "DURATION").forEach {
                                add(kotlinx.serialization.json.JsonPrimitive(it))
                            }
                        }
                    }
                    putJsonObject("muscleGroups") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                    putJsonObject("sets") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            put("additionalProperties", false)
                            putJsonArray("required") { }
                            putJsonObject("properties") {
                                putJsonObject("weightKg") {
                                    putJsonArray("type") {
                                        add(kotlinx.serialization.json.JsonPrimitive("number"))
                                        add(kotlinx.serialization.json.JsonPrimitive("null"))
                                    }
                                }
                                putJsonObject("reps") {
                                    putJsonArray("type") {
                                        add(kotlinx.serialization.json.JsonPrimitive("integer"))
                                        add(kotlinx.serialization.json.JsonPrimitive("null"))
                                    }
                                }
                                putJsonObject("durationSec") {
                                    putJsonArray("type") {
                                        add(kotlinx.serialization.json.JsonPrimitive("integer"))
                                        add(kotlinx.serialization.json.JsonPrimitive("null"))
                                    }
                                }
                                putJsonObject("distanceM") {
                                    putJsonArray("type") {
                                        add(kotlinx.serialization.json.JsonPrimitive("number"))
                                        add(kotlinx.serialization.json.JsonPrimitive("null"))
                                    }
                                }
                                putJsonObject("isWarmup") { put("type", "boolean") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildSystemPrompt(knownExercises: List<String>, today: LocalDate): String = """
Du wandelst gesprochene Trainingsnotizen in strukturierte Daten um. Antworte ausschließlich im vorgegebenen JSON-Format.

Heutiges Datum: $today

Bekannte Übungen (nutze exakt diese Schreibweise, wenn gemeint):
${knownExercises.joinToString(", ")}

Regeln:
- Gewichte immer in Kilogramm. Pfund (lb/lbs) umrechnen: 1 lb = 0,45359237 kg.
- Dauer in Sekunden, Distanz in Metern. "20 Minuten" -> durationSec 1200. "5 km" -> distanceM 5000.
- Jeder Satz ist ein eigener Eintrag in "sets". "3x10 mit 100 kg" sind DREI Sätze mit je 100 kg und 10 Wiederholungen.
- Gilt ein Gewicht für mehrere Sätze, wiederhole es in jedem Satz.
- Nennt jemand nur Wiederholungen für Folgesätze ("danach noch zweimal fünf"), übernimm das zuletzt genannte Gewicht.
- Mehrere Übungen in einer Nachricht ergeben mehrere Einträge in "exercises".
- Schreibe in "dateExpression" WÖRTLICH, was zum Zeitpunkt gesagt wurde ("heute", "gestern", "letzten Freitag", "am 5. August"). Rechne das Datum NICHT selbst aus. Wurde nichts gesagt: null.
- Fehlen Sätze oder Wiederholungen, setze sie auf null, gib eine niedrige confidence und stelle in "clarificationQuestion" eine kurze deutsche Rückfrage.
- intent "create_exercise" nur, wenn jemand ausdrücklich eine neue Übung anlegen will ("Neue Übung: ...").
- intent "correction", wenn ein zuvor genannter Wert berichtigt wird ("Die 120 Kilo waren 110").
- confidence: 0.9+ wenn alles eindeutig ist, 0.5-0.85 bei Unsicherheiten, unter 0.5 wenn Wesentliches fehlt.
- Erfinde niemals Zahlen, die nicht gesagt wurden.
""".trimIndent()
