package com.gymapp.tracker.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Device settings, including the Anthropic API key.
 *
 * The key is entered by the user in the app and stored in
 * EncryptedSharedPreferences, backed by the Android Keystore — it is never
 * compiled into the APK and never leaves the device except in the
 * `x-api-key` header of the request it authenticates.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        android.util.Log.w("TokenStore", "Verschlüsselter Speicher nicht verfügbar, nutze Fallback")
        context.getSharedPreferences("${FILE_NAME}_fallback", Context.MODE_PRIVATE)
    }

    var anthropicApiKey: String?
        get() = prefs.getString(KEY_API, null)
        set(value) = prefs.edit().putString(KEY_API, value).apply()

    var aiModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var displayName: String
        get() = prefs.getString(KEY_NAME, "Athlet") ?: "Athlet"
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    /**
     * Where the app looks for a newer build. Defaults to the update server on
     * the home PC (serve-updates.sh) — no account and no token needed. Change
     * it in the settings if the PC's address changes or you host it elsewhere.
     */
    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(value) = prefs.edit().putString(KEY_UPDATE_URL, value.trim()).apply()

    var unitSystem: String
        get() = prefs.getString(KEY_UNITS, "KG") ?: "KG"
        set(value) = prefs.edit().putString(KEY_UNITS, value).apply()

    private companion object {
        const val FILE_NAME = "gymapp_secure_prefs"
        const val KEY_API = "anthropic_api_key"
        const val KEY_MODEL = "ai_model"
        const val KEY_NAME = "display_name"
        const val KEY_UNITS = "unit_system"
        const val KEY_UPDATE_URL = "update_url"
        const val DEFAULT_MODEL = "claude-opus-5"
        const val DEFAULT_UPDATE_URL =
            "http://10.50.184.28:8080/update.json"
    }
}
