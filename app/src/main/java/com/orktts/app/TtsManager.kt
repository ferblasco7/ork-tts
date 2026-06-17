package com.orktts.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads a [Book] sentence by sentence using the system TTS engine.
 * "Skip 10s" has no real meaning for synthesized speech (there's no audio timeline),
 * so forward/back simply move one sentence — instant and trivial to implement.
 */
class TtsManager(
    context: Context,
    private val book: Book,
    private var chapterIndex: Int,
    private var sentenceIndex: Int,
    private val onPositionChanged: (chapterIndex: Int, sentenceIndex: Int) -> Unit,
    private val onPlayingChanged: (isPlaying: Boolean) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var wantsToPlay = false

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
            if (ready && wantsToPlay) speakCurrent()
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (!wantsToPlay) return
                if (!advance()) {
                    wantsToPlay = false
                    onPlayingChanged(false)
                    return
                }
                speakCurrent()
            }
        })
    }

    fun play() {
        wantsToPlay = true
        onPlayingChanged(true)
        if (ready) speakCurrent()
    }

    fun pause() {
        wantsToPlay = false
        tts?.stop()
        onPlayingChanged(false)
    }

    fun skipForward() = jump(+1)
    fun skipBack() = jump(-1)

    fun release() {
        tts?.stop()
        tts?.shutdown()
    }

    private fun jump(delta: Int) {
        val wasPlaying = wantsToPlay
        tts?.stop()
        sentenceIndex += delta
        clampPosition()
        onPositionChanged(chapterIndex, sentenceIndex)
        if (wasPlaying && ready) speakCurrent()
    }

    private fun advance(): Boolean {
        sentenceIndex++
        val chapter = book.chapters.getOrNull(chapterIndex)
        if (chapter != null && sentenceIndex >= chapter.sentences.size) {
            sentenceIndex = 0
            chapterIndex++
        }
        if (chapterIndex >= book.chapters.size) return false
        onPositionChanged(chapterIndex, sentenceIndex)
        return true
    }

    private fun clampPosition() {
        if (chapterIndex < 0) {
            chapterIndex = 0
            sentenceIndex = 0
        }
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return
        if (sentenceIndex < 0) {
            if (chapterIndex > 0) {
                chapterIndex--
                sentenceIndex = (book.chapters.getOrNull(chapterIndex)?.sentences?.size ?: 1) - 1
            } else {
                sentenceIndex = 0
            }
        } else if (sentenceIndex >= chapter.sentences.size) {
            if (chapterIndex < book.chapters.size - 1) {
                chapterIndex++
                sentenceIndex = 0
            } else {
                sentenceIndex = chapter.sentences.size - 1
            }
        }
    }

    private fun speakCurrent() {
        val sentence = book.chapters.getOrNull(chapterIndex)?.sentences?.getOrNull(sentenceIndex) ?: return
        tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "s$chapterIndex-$sentenceIndex")
    }

    fun currentChapterIndex() = chapterIndex
    fun currentSentenceIndex() = sentenceIndex
}
