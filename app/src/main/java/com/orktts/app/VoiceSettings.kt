package com.orktts.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voiceDataStore by preferencesDataStore(name = "voice_settings")

data class VoiceSettings(
    val pitch: Float = 1f,
    val speed: Float = 1f,
    val pauseMs: Int = 0,
    val eqCutBass: Boolean = false,
    val ignorePunctuation: Boolean = true
)

/** Pitch, speed, inter-sentence pause and an optional bass-cut equalizer for headphone listening. */
object VoiceSettingsStore {
    private val KEY_PITCH = floatPreferencesKey("pitch")
    private val KEY_SPEED = floatPreferencesKey("speed")
    private val KEY_PAUSE_MS = intPreferencesKey("pause_ms")
    private val KEY_EQ_CUT_BASS = booleanPreferencesKey("eq_cut_bass")
    private val KEY_IGNORE_PUNCTUATION = booleanPreferencesKey("ignore_punctuation")

    fun flow(context: Context): Flow<VoiceSettings> =
        context.voiceDataStore.data.map { prefs ->
            VoiceSettings(
                pitch = prefs[KEY_PITCH] ?: 1f,
                speed = prefs[KEY_SPEED] ?: 1f,
                pauseMs = prefs[KEY_PAUSE_MS] ?: 0,
                eqCutBass = prefs[KEY_EQ_CUT_BASS] ?: false,
                ignorePunctuation = prefs[KEY_IGNORE_PUNCTUATION] ?: true
            )
        }

    suspend fun save(context: Context, settings: VoiceSettings) {
        context.voiceDataStore.edit { prefs ->
            prefs[KEY_PITCH] = settings.pitch
            prefs[KEY_SPEED] = settings.speed
            prefs[KEY_PAUSE_MS] = settings.pauseMs
            prefs[KEY_EQ_CUT_BASS] = settings.eqCutBass
            prefs[KEY_IGNORE_PUNCTUATION] = settings.ignorePunctuation
        }
    }
}
