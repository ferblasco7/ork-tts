package com.orktts.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voiceDataStore by preferencesDataStore(name = "voice_settings")

data class VoiceSettings(val pitch: Float = 1f, val speed: Float = 1f, val pauseMs: Int = 250)

/** Pitch, speed and inter-sentence pause — the practical stand-in for "punctuation handling" in TTS. */
object VoiceSettingsStore {
    private val KEY_PITCH = floatPreferencesKey("pitch")
    private val KEY_SPEED = floatPreferencesKey("speed")
    private val KEY_PAUSE_MS = intPreferencesKey("pause_ms")

    fun flow(context: Context): Flow<VoiceSettings> =
        context.voiceDataStore.data.map { prefs ->
            VoiceSettings(
                pitch = prefs[KEY_PITCH] ?: 1f,
                speed = prefs[KEY_SPEED] ?: 1f,
                pauseMs = prefs[KEY_PAUSE_MS] ?: 250
            )
        }

    suspend fun save(context: Context, settings: VoiceSettings) {
        context.voiceDataStore.edit { prefs ->
            prefs[KEY_PITCH] = settings.pitch
            prefs[KEY_SPEED] = settings.speed
            prefs[KEY_PAUSE_MS] = settings.pauseMs
        }
    }
}
