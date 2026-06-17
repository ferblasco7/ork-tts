package com.orktts.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "reading_position")

data class Position(val bookUri: String, val chapterIndex: Int, val sentenceIndex: Int)

/** Remembers which EPUB the user has open and exactly which sentence they were on. */
object ReadingPosition {
    private val KEY_URI = stringPreferencesKey("book_uri")
    private val KEY_CHAPTER = intPreferencesKey("chapter_index")
    private val KEY_SENTENCE = intPreferencesKey("sentence_index")

    suspend fun save(context: Context, bookUri: String, chapterIndex: Int, sentenceIndex: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_URI] = bookUri
            prefs[KEY_CHAPTER] = chapterIndex
            prefs[KEY_SENTENCE] = sentenceIndex
        }
    }

    suspend fun load(context: Context): Position? {
        val prefs = context.dataStore.data.first()
        val uri = prefs[KEY_URI] ?: return null
        return Position(
            bookUri = uri,
            chapterIndex = prefs[KEY_CHAPTER] ?: 0,
            sentenceIndex = prefs[KEY_SENTENCE] ?: 0
        )
    }
}
