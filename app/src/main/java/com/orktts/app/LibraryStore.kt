package com.orktts.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.libraryDataStore by preferencesDataStore(name = "library")

data class LibraryEntry(
    val bookUri: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val lastOpened: Long
)

/** Remembers every book the user has opened, each with its own reading position. */
object LibraryStore {
    private val KEY_BOOKS = stringPreferencesKey("books_json")

    fun flow(context: Context): Flow<List<LibraryEntry>> =
        context.libraryDataStore.data.map { prefs -> parse(prefs[KEY_BOOKS]) }

    suspend fun all(context: Context): List<LibraryEntry> = parse(context.libraryDataStore.data.first()[KEY_BOOKS])

    suspend fun get(context: Context, bookUri: String): LibraryEntry? =
        all(context).find { it.bookUri == bookUri }

    suspend fun upsert(
        context: Context,
        bookUri: String,
        title: String,
        author: String?,
        coverBytes: ByteArray?,
        chapterIndex: Int,
        sentenceIndex: Int
    ) {
        val existing = get(context, bookUri)
        val coverPath = coverBytes?.let { saveCover(context, bookUri, it) } ?: existing?.coverPath
        write(context, bookUri, title, author, coverPath, chapterIndex, sentenceIndex)
        BookWidgetProvider.requestUpdate(context)
    }

    suspend fun savePosition(context: Context, bookUri: String, chapterIndex: Int, sentenceIndex: Int) {
        val existing = get(context, bookUri) ?: return
        write(context, bookUri, existing.title, existing.author, existing.coverPath, chapterIndex, sentenceIndex)
    }

    suspend fun remove(context: Context, bookUri: String) {
        val existing = get(context, bookUri) ?: return
        existing.coverPath?.let { File(it).delete() }
        context.libraryDataStore.edit { prefs ->
            val list = parse(prefs[KEY_BOOKS]).filterNot { it.bookUri == bookUri }
            prefs[KEY_BOOKS] = serialize(list)
        }
        BookWidgetProvider.requestUpdate(context)
    }

    private suspend fun write(
        context: Context,
        bookUri: String,
        title: String,
        author: String?,
        coverPath: String?,
        chapterIndex: Int,
        sentenceIndex: Int
    ) {
        context.libraryDataStore.edit { prefs ->
            val list = parse(prefs[KEY_BOOKS]).filterNot { it.bookUri == bookUri }.toMutableList()
            list.add(LibraryEntry(bookUri, title, author, coverPath, chapterIndex, sentenceIndex, System.currentTimeMillis()))
            prefs[KEY_BOOKS] = serialize(list)
        }
    }

    private fun saveCover(context: Context, bookUri: String, bytes: ByteArray): String {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "${bookUri.hashCode()}.img")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun parse(json: String?): List<LibraryEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            LibraryEntry(
                bookUri = o.getString("uri"),
                title = o.getString("title"),
                author = o.optString("author").ifEmpty { null },
                coverPath = o.optString("cover").ifEmpty { null },
                chapterIndex = o.getInt("chapter"),
                sentenceIndex = o.getInt("sentence"),
                lastOpened = o.optLong("lastOpened")
            )
        }.sortedByDescending { it.lastOpened }
    }

    private fun serialize(list: List<LibraryEntry>): String {
        val array = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("uri", e.bookUri)
            o.put("title", e.title)
            o.put("author", e.author ?: "")
            o.put("cover", e.coverPath ?: "")
            o.put("chapter", e.chapterIndex)
            o.put("sentence", e.sentenceIndex)
            o.put("lastOpened", e.lastOpened)
            array.put(o)
        }
        return array.toString()
    }
}
