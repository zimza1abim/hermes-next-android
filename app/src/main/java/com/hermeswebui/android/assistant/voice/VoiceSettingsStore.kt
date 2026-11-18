package com.hermeswebui.android.assistant.voice

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Where the voice settings live: model, voice, tone, per-bot overrides, hand-off.
 *
 * Encrypted like the key, and read with defaults for anything missing — a settings file written by an
 * older version must not stop a call.
 */
class VoiceSettingsStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): VoiceSettings {
        val raw = prefs.getString(KEY, null) ?: return VoiceSettings()
        return runCatching { decode(JSONObject(raw)) }.getOrElse { VoiceSettings() }
    }

    fun save(settings: VoiceSettings) {
        prefs.edit().putString(KEY, encode(settings).toString()).apply()
    }

    private fun encode(settings: VoiceSettings): JSONObject {
        val perBot = JSONObject()
        settings.perBot.forEach { (bot, override) ->
            perBot.put(bot, JSONObject()
                .put("voice", override.voice.id)
                .put("preset", override.persona.preset.id)
                .put("warmth", override.persona.warmth)
                .put("custom", override.persona.custom))
        }
        return JSONObject()
            .put("model", settings.model.id)
            .put("speed", settings.speed.toDouble())
            .put("voice", settings.voice.id)
            .put("preset", settings.persona.preset.id)
            .put("warmth", settings.persona.warmth)
            .put("custom", settings.persona.custom)
            .put("handOff", settings.handOff)
            .put("subtitles", settings.subtitles)
            .put("speaker", settings.speakerOn)
            .put("perBot", perBot)
            .put("language", settings.language.name)
            .put("customLanguage", settings.customLanguage)
            .put("assistantName", settings.assistantName)
    }

    private fun decode(json: JSONObject): VoiceSettings {
        val perBot = mutableMapOf<String, BotVoice>()
        json.optJSONObject("perBot")?.let { map ->
            map.keys().forEach { bot ->
                val entry = map.optJSONObject(bot) ?: return@forEach
                val voice = Voice.fromId(entry.optString("voice", null)) ?: Voice.DEFAULT
                perBot[bot] = BotVoice(voice, persona(entry))
            }
        }
        return VoiceSettings(
            model = LiveModel.fromId(json.optString("model", null)),
            speed = json.optDouble("speed", 1.0).toFloat().coerceIn(VoiceSettings.MIN_SPEED, VoiceSettings.MAX_SPEED),
            voice = Voice.fromId(json.optString("voice", null)) ?: Voice.DEFAULT,
            persona = persona(json),
            handOff = json.optBoolean("handOff", true),
            subtitles = json.optBoolean("subtitles", true),
            speakerOn = json.optBoolean("speaker", true),
            perBot = perBot,
            language = VoiceLanguage.fromId(json.optString("language", null)),
            customLanguage = json.optString("customLanguage", ""),
            assistantName = json.optString("assistantName", ""),
        )
    }

    private fun persona(json: JSONObject): Persona = Persona(
        preset = PersonaPreset.fromId(json.optString("preset", null)),
        warmth = json.optInt("warmth", Persona.DEFAULT.warmth).coerceIn(Persona.MIN_WARMTH, Persona.MAX_WARMTH),
        custom = json.optString("custom", ""),
    )

    companion object {
        private const val FILE = "hermes_voice_settings"
        private const val KEY = "voice_settings"
    }
}
