package com.hermeswebui.android.assistant.voice

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The Gemini key, in the app's encrypted preferences.
 *
 * The plan puts the key in the device's secure storage and keeps the server untouched; the file never
 * reaches the repository, a log, or a screenshot of the settings screen (the field is masked and only
 * the last four characters are ever shown).
 */
class GeminiKey(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, trimmed).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    /** What the settings screen may show: never the key itself. */
    fun masked(): String? = get()?.let { key ->
        if (key.length <= 4) "••••" else "••••" + key.takeLast(4)
    }

    companion object {
        private const val FILE = "hermes_voice_keys"
        private const val KEY = "gemini_api_key"

        /** The websocket the Live API is reached on. */
        fun endpoint(apiKey: String, apiVersion: String = "v1beta"): String =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.$apiVersion" +
                ".GenerativeService.BidiGenerateContent?key=$apiKey"
    }
}
