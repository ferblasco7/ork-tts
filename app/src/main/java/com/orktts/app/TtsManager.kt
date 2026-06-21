package com.orktts.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI

/**
 * Reads a [Book] sentence by sentence: synthesizes each sentence to a WAV file and plays it with
 * a [MediaPlayer]. The bass-cut option filters the PCM samples directly in software (a cascaded
 * one-pole high-pass) instead of relying on the device's hardware Equalizer effect, since several
 * OEM audio chips (MIUI included) apply that effect as a sticky global DSP that ignores per-app
 * enable/disable — filtering the samples ourselves always works, on every device.
 * "Skip 10s" has no real meaning for synthesized speech (there's no audio timeline),
 * so forward/back simply move one sentence — instant and trivial to implement.
 */
class TtsManager(
    private val context: Context,
    private val book: Book,
    private var chapterIndex: Int,
    private var sentenceIndex: Int,
    voiceSettings: VoiceSettings,
    private val onPositionChanged: (chapterIndex: Int, sentenceIndex: Int) -> Unit,
    private val onPlayingChanged: (isPlaying: Boolean) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var wantsToPlay = false
    private var settings = voiceSettings
    private val handler = Handler(Looper.getMainLooper())
    private val sentenceFile = File(context.cacheDir, "tts_current.wav")
    private val nextFile = File(context.cacheDir, "tts_next.wav")
    private val filteredFile = File(context.cacheDir, "tts_current_filtered.wav")
    private val filteredNextFile = File(context.cacheDir, "tts_next_filtered.wav")
    private var mediaPlayer: MediaPlayer? = null
    private var currentlySynthesising = false
    private val synthQueue = LinkedBlockingQueue<SynthesisJob>()

    private data class SynthesisJob(val chapterIdx: Int, val sentenceIdx: Int, val text: String, val file: File, val utteranceId: String)

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
            applySettings()
            if (ready && wantsToPlay) speakCurrent()
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post {
                    currentlySynthesising = false
                    if (utteranceId == "current" && wantsToPlay) {
                        playSynthesizedSentence()
                    }
                    processQueue()
                }
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
        stopPlayer()
        onPlayingChanged(false)
    }

    fun skipForward() = jump(+1)
    fun skipBack() = jump(-1)

    /** Jumps straight to a sentence in the current chapter (used for page navigation) without
     * synthesizing every sentence in between — a single synthesis call at the destination. */
    fun jumpToSentence(index: Int) {
        val wasPlaying = wantsToPlay
        wantsToPlay = false
        stopPlayer()
        val chapterSize = book.chapters.getOrNull(chapterIndex)?.sentences?.size ?: 1
        sentenceIndex = index.coerceIn(0, chapterSize - 1)
        onPositionChanged(chapterIndex, sentenceIndex)
        if (wasPlaying) {
            wantsToPlay = true
            if (ready) speakCurrent()
        }
    }

    fun jumpToChapter(index: Int) {
        val wasPlaying = wantsToPlay
        wantsToPlay = false
        stopPlayer()
        chapterIndex = index.coerceIn(0, book.chapters.size - 1)
        sentenceIndex = 0
        onPositionChanged(chapterIndex, sentenceIndex)
        if (wasPlaying) {
            wantsToPlay = true
            if (ready) speakCurrent()
        }
    }

    fun updateVoiceSettings(newSettings: VoiceSettings) {
        settings = newSettings
        applySettings()
    }

    fun release() {
        wantsToPlay = false
        stopPlayer()
        tts?.stop()
        tts?.shutdown()
    }

    private fun applySettings() {
        tts?.setPitch(settings.pitch)
        tts?.setSpeechRate(settings.speed)
    }

    private fun jump(delta: Int) {
        val wasPlaying = wantsToPlay
        wantsToPlay = false
        stopPlayer()
        sentenceIndex += delta
        clampPosition()
        onPositionChanged(chapterIndex, sentenceIndex)
        if (wasPlaying) {
            wantsToPlay = true
            if (ready) speakCurrent()
        }
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
        val textToSpeak = if (settings.ignorePunctuation) cleanPunctuation(sentence) else sentence
        synthQueue.clear()
        enqueueSynthesis(chapterIndex, sentenceIndex, textToSpeak, sentenceFile, "current")
        enqueueTwoAhead()
        processQueue()
    }

    private fun enqueueSynthesis(chap: Int, sent: Int, text: String, file: File, id: String) {
        synthQueue.offer(SynthesisJob(chap, sent, text, file, id))
    }

    private fun enqueueTwoAhead() {
        var nextChap = chapterIndex
        var nextSent = sentenceIndex + 1
        if (nextSent >= (book.chapters.getOrNull(nextChap)?.sentences?.size ?: 0)) {
            nextChap++
            nextSent = 0
        }
        if (nextChap < book.chapters.size) {
            val nextText = book.chapters[nextChap].sentences[nextSent]
            val textToSpeak = if (settings.ignorePunctuation) cleanPunctuation(nextText) else nextText
            enqueueSynthesis(nextChap, nextSent, textToSpeak, nextFile, "next")
        }
    }

    private fun processQueue() {
        if (currentlySynthesising || synthQueue.isEmpty()) return
        val job = synthQueue.poll() ?: return
        currentlySynthesising = true
        tts?.synthesizeToFile(job.text, Bundle(), job.file, job.utteranceId)
    }

    private fun cleanPunctuation(text: String): String =
        text.replace(Regex("[.!?¡¿]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun playSynthesizedSentence() {
        if (!wantsToPlay) return
        if (!sentenceFile.exists()) {
            onSentenceFinished()
            return
        }
        stopPlayer()
        val playable = if (settings.eqCutBass) {
            try {
                applyBassCutFilter(sentenceFile, filteredFile, BASS_CUTOFF_HZ)
                filteredFile
            } catch (e: Exception) {
                sentenceFile
            }
        } else {
            sentenceFile
        }
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(playable.absolutePath)
                setOnCompletionListener { onSentenceFinished() }
                prepare()
                val durationMs = duration
                start()
                if (nextFile.exists() && durationMs > 1000) {
                    handler.postDelayed({ playNextOverlap() }, (durationMs - 1000).toLong())
                }
            }
        } catch (e: Exception) {
            onSentenceFinished()
        }
    }

    private fun playNextOverlap() {
        if (!wantsToPlay || !nextFile.exists() || mediaPlayer == null) return
        try {
            val playable = if (settings.eqCutBass) {
                try {
                    applyBassCutFilter(nextFile, filteredNextFile, BASS_CUTOFF_HZ)
                    filteredNextFile
                } catch (e: Exception) {
                    nextFile
                }
            } else {
                nextFile
            }
            val nextPlayer = MediaPlayer().apply {
                setDataSource(playable.absolutePath)
                setOnCompletionListener { onSentenceFinished() }
                prepare()
                start()
            }
            handler.postDelayed({
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = nextPlayer
                sentenceFile.delete()
                nextFile.renameTo(sentenceFile)
                if (!advance()) {
                    wantsToPlay = false
                    onPlayingChanged(false)
                    return@postDelayed
                }
                enqueueTwoAhead()
            }, 1000)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun onSentenceFinished() {
        if (!wantsToPlay) return
        if (!advance()) {
            wantsToPlay = false
            onPlayingChanged(false)
            return
        }
        if (nextFile.exists()) {
            sentenceFile.delete()
            nextFile.renameTo(sentenceFile)
            enqueueTwoAhead()
            playSynthesizedSentence()
        } else {
            speakCurrent()
        }
    }

    private fun stopPlayer() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) {
                // ignore
            }
            it.release()
        }
        mediaPlayer = null
    }

    companion object {
        private const val BASS_CUTOFF_HZ = 900f

        /** Cascaded one-pole high-pass filter applied directly to 16-bit PCM samples in a WAV file. */
        private fun applyBassCutFilter(input: File, output: File, cutoffHz: Float) {
            val bytes = input.readBytes()
            if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") {
                input.copyTo(output, overwrite = true)
                return
            }

            var pos = 12
            var channels = 1
            var sampleRate = 22050
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = -1
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = readLE32(bytes, pos + 4)
                val chunkDataStart = pos + 8
                when (chunkId) {
                    "fmt " -> {
                        channels = readLE16(bytes, chunkDataStart + 2)
                        sampleRate = readLE32(bytes, chunkDataStart + 4)
                        bitsPerSample = readLE16(bytes, chunkDataStart + 14)
                    }
                    "data" -> {
                        dataOffset = chunkDataStart
                        dataSize = chunkSize.coerceAtMost(bytes.size - chunkDataStart)
                    }
                }
                if (dataOffset >= 0) break
                pos = chunkDataStart + chunkSize + (chunkSize % 2)
            }

            if (dataOffset < 0 || bitsPerSample != 16) {
                input.copyTo(output, overwrite = true)
                return
            }

            val result = bytes.copyOf()
            val rc = 1.0 / (2 * PI * cutoffHz)
            val dt = 1.0 / sampleRate
            val alpha = rc / (rc + dt)
            val bytesPerFrame = 2 * channels
            val frameCount = dataSize / bytesPerFrame

            for (ch in 0 until channels) {
                var prevXa = 0.0
                var prevYa = 0.0
                var prevXb = 0.0
                var prevYb = 0.0
                for (i in 0 until frameCount) {
                    val byteIndex = dataOffset + i * bytesPerFrame + ch * 2
                    val sample = ((bytes[byteIndex + 1].toInt() shl 8) or (bytes[byteIndex].toInt() and 0xFF)).toShort().toDouble()

                    val ya = alpha * (prevYa + sample - prevXa)
                    prevXa = sample
                    prevYa = ya

                    val yb = alpha * (prevYb + ya - prevXb)
                    prevXb = ya
                    prevYb = yb

                    val outSample = yb.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    result[byteIndex] = (outSample and 0xFF).toByte()
                    result[byteIndex + 1] = ((outSample shr 8) and 0xFF).toByte()
                }
            }

            output.writeBytes(result)
        }

        private fun readLE16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readLE32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
