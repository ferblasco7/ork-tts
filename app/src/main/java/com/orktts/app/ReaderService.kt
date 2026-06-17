package com.orktts.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that owns the TTS playback and shows a fixed notification
 * with play/pause and skip-by-one-sentence ("10s") controls.
 */
class ReaderService : Service() {

    companion object {
        const val ACTION_LOAD = "com.orktts.app.LOAD"
        const val ACTION_PLAY = "com.orktts.app.PLAY"
        const val ACTION_PAUSE = "com.orktts.app.PAUSE"
        const val ACTION_SKIP_FORWARD = "com.orktts.app.SKIP_FORWARD"
        const val ACTION_SKIP_BACK = "com.orktts.app.SKIP_BACK"
        const val EXTRA_URI = "uri"

        private const val CHANNEL_ID = "ork_tts_playback"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var ttsManager: TtsManager? = null
    private var bookUri: String? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lectura en voz alta", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> intent.getStringExtra(EXTRA_URI)?.let { loadBook(it) }
            ACTION_PLAY -> ttsManager?.play()
            ACTION_PAUSE -> ttsManager?.pause()
            ACTION_SKIP_FORWARD -> ttsManager?.skipForward()
            ACTION_SKIP_BACK -> ttsManager?.skipBack()
        }
        return START_NOT_STICKY
    }

    private fun loadBook(uriString: String) {
        bookUri = uriString
        ReaderState.state.value = ReaderState.state.value.copy(loading = true, error = null)
        startForeground(NOTIF_ID, buildNotification(null, false))

        scope.launch {
            val book = try {
                withContext(Dispatchers.IO) { parseEpub(Uri.parse(uriString)) }
            } catch (e: Exception) {
                ReaderState.state.value = ReaderState.state.value.copy(loading = false, error = e.message)
                return@launch
            }

            val saved = ReadingPosition.load(this@ReaderService)
            val startChapter = if (saved?.bookUri == uriString) saved.chapterIndex else 0
            val startSentence = if (saved?.bookUri == uriString) saved.sentenceIndex else 0

            ttsManager?.release()
            ttsManager = TtsManager(
                context = this@ReaderService,
                book = book,
                chapterIndex = startChapter,
                sentenceIndex = startSentence,
                onPositionChanged = { chapterIndex, sentenceIndex ->
                    ReaderState.state.value = ReaderState.state.value.copy(
                        chapterIndex = chapterIndex,
                        sentenceIndex = sentenceIndex
                    )
                    scope.launch { ReadingPosition.save(this@ReaderService, uriString, chapterIndex, sentenceIndex) }
                    updateNotification(book)
                },
                onPlayingChanged = { isPlaying ->
                    ReaderState.state.value = ReaderState.state.value.copy(isPlaying = isPlaying)
                    updateNotification(book)
                }
            )

            ReaderState.state.value = ReaderState.state.value.copy(
                loading = false,
                book = book,
                chapterIndex = startChapter,
                sentenceIndex = startSentence
            )
            updateNotification(book)
        }
    }

    private fun parseEpub(uri: Uri): Book {
        val tempFile = File(cacheDir, "current_book.epub")
        contentResolver.openInputStream(uri)!!.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        return EpubParser.parse(tempFile)
    }

    private fun updateNotification(book: Book) {
        val isPlaying = ReaderState.state.value.isPlaying
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(book, isPlaying))
    }

    private fun buildNotification(book: Book?, isPlaying: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(book?.title ?: "Cargando libro…")
            .setContentText(currentChapterTitle(book))
            .setLargeIcon(book?.coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_rew, "Retroceder", actionIntent(ACTION_SKIP_BACK))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pausa" else "Reproducir",
                actionIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(android.R.drawable.ic_media_ff, "Avanzar", actionIntent(ACTION_SKIP_FORWARD))
            .build()

    private fun currentChapterTitle(book: Book?): String {
        if (book == null) return ""
        val state = ReaderState.state.value
        return book.chapters.getOrNull(state.chapterIndex)?.title ?: ""
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, ReaderService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ttsManager?.release()
        super.onDestroy()
    }
}
