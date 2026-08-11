package com.gymapp.tracker.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gymapp.tracker.BuildConfig

/**
 * Device settings, including the Anthropic API key.
 *
 * The key can be entered by hand in the app, or pre-filled from a build-time
 * default (see [BuildConfig.DEFAULT_ANTHROPIC_KEY]) so a shared key doesn't
 * have to be typed on every phone. Either way it is stored in
 * EncryptedSharedPreferences, backed by the Android Keystore, and never leaves
 * the device except in the `x-api-key` header of the request it authenticates.
 *
 * The build-time default is a convenience for a small, trusted group sharing
 * one key — unlike a value typed into Settings, it *is* present in the
 * compiled APK and can be extracted from it. It comes from
 * `android/local.properties` (gitignored), never from a file that reaches the
 * repository.
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
        // Only falls back to the baked-in default when nothing has been
        // stored yet at all (KEY_HAS_API_DECISION unset) — once the user
        // saves an empty value on purpose (clearing the key), that decision
        // sticks and the default is not silently reapplied.
        get() = prefs.getString(KEY_API, null)
            ?: DEFAULT_ANTHROPIC_KEY.takeIf { it.isNotBlank() && !prefs.getBoolean(KEY_HAS_API_DECISION, false) }
        set(value) {
            prefs.edit()
                .putString(KEY_API, value)
                .putBoolean(KEY_HAS_API_DECISION, true)
                .apply()
        }

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

    /** Order of the Dashboard's draggable cards, comma separated section keys. */
    var dashboardOrder: String
        get() = prefs.getString(KEY_DASHBOARD_ORDER, DEFAULT_DASHBOARD_ORDER) ?: DEFAULT_DASHBOARD_ORDER
        set(value) = prefs.edit().putString(KEY_DASHBOARD_ORDER, value).apply()

    /** Order of the Profil screen's draggable cards, comma separated section keys. */
    var settingsOrder: String
        get() = prefs.getString(KEY_SETTINGS_ORDER, DEFAULT_SETTINGS_ORDER) ?: DEFAULT_SETTINGS_ORDER
        set(value) = prefs.edit().putString(KEY_SETTINGS_ORDER, value).apply()

    private companion object {
        const val FILE_NAME = "gymapp_secure_prefs"
        const val KEY_API = "anthropic_api_key"
        const val KEY_HAS_API_DECISION = "anthropic_api_key_decided"
        val DEFAULT_ANTHROPIC_KEY: String = BuildConfig.DEFAULT_ANTHROPIC_KEY
        const val KEY_MODEL = "ai_model"
        const val KEY_NAME = "display_name"
        const val KEY_UNITS = "unit_system"
        const val KEY_UPDATE_URL = "update_url"
        const val KEY_DASHBOARD_ORDER = "dashboard_order"
        const val DEFAULT_MODEL = "claude-opus-5"
        const val DEFAULT_UPDATE_URL =
            "http://10.50.184.28:8080/update.json"
        const val DEFAULT_DASHBOARD_ORDER = "today,records,comparisons,muscleGroups"
        const val KEY_SETTINGS_ORDER = "settings_order"
        const val DEFAULT_SETTINGS_ORDER = "claude,display,update,data"
    }
}
