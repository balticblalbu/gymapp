package com.gymapp.tracker.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.gymapp.tracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app updater.
 *
 * Checks a URL you control for a newer build, downloads the APK and hands it to
 * Android's package installer. Two shapes are supported:
 *
 *  - a JSON manifest (recommended), so the app only downloads when the version
 *    actually changed:
 *    `{"versionCode": 2, "versionName": "1.1.0", "apkUrl": "https://…/app.apk", "notes": "…"}`
 *  - a direct `.apk` URL, which always downloads and always offers the install.
 *
 * The installer itself shows Android's normal confirmation dialog — nothing is
 * installed silently.
 */
class Updater(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Manifest(
        val versionCode: Int = 0,
        val versionName: String = "",
        val apkUrl: String = "",
        val notes: String? = null,
    )

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val versionName: String, val notes: String?, val apkUrl: String) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun check(url: String): Result = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return@withContext Result.Failed("Keine Update-Adresse hinterlegt.")

        if (trimmed.endsWith(".apk", ignoreCase = true)) {
            // No manifest to compare against — always offer it.
            return@withContext Result.Available("neueste Version", null, trimmed)
        }

        val body = try {
            http.newCall(Request.Builder().url(trimmed).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.Failed("Update-Adresse antwortet mit ${response.code}.")
                }
                response.body?.string().orEmpty()
            }
        } catch (error: IOException) {
            return@withContext Result.Failed("Update-Server nicht erreichbar.")
        } catch (error: IllegalArgumentException) {
            return@withContext Result.Failed("Die Update-Adresse ist keine gültige URL.")
        }

        val manifest = runCatching { json.decodeFromString(Manifest.serializer(), body) }
            .getOrElse { return@withContext Result.Failed("Update-Datei konnte nicht gelesen werden.") }

        when {
            manifest.apkUrl.isBlank() -> Result.Failed("In der Update-Datei fehlt die APK-Adresse.")
            manifest.versionCode <= BuildConfig.VERSION_CODE -> Result.UpToDate
            else -> Result.Available(
                versionName = manifest.versionName.ifBlank { "Version ${manifest.versionCode}" },
                notes = manifest.notes,
                apkUrl = manifest.apkUrl,
            )
        }
    }

    /** Downloads the APK and opens Android's installer. */
    suspend fun downloadAndInstall(apkUrl: String): String? = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "update.apk")
        try {
            http.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Download fehlgeschlagen (${response.code})."
                val stream = response.body?.byteStream() ?: return@withContext "Leere Antwort beim Download."
                target.outputStream().use { output -> stream.copyTo(output) }
            }
        } catch (error: IOException) {
            return@withContext "Download abgebrochen: ${error.message ?: "Verbindungsfehler"}"
        }

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .exceptionOrNull()
            ?.let { return@withContext "Installer konnte nicht geöffnet werden." }

        null
    }

    val currentVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
