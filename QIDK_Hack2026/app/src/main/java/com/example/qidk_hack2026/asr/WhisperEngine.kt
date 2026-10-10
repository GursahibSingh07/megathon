package com.example.qidk_hack2026.asr

import android.content.Context
import com.example.qidk_hack2026.audio.AudioRecorder
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide Whisper instance shared by the Activity and the IME, so the ~180 MB model
 * is loaded once. Whisper itself is single-threaded-access; WhisperContext enforces that.
 */
object WhisperEngine {
    private val loadLock = Mutex()
    private var ctx: WhisperContext? = null

    private const val MIN_SAMPLES = AudioRecorder.SAMPLE_RATE * 11 / 10 // whisper needs >1 s

    suspend fun load(appContext: Context): WhisperContext = loadLock.withLock {
        ctx?.let { return it }
        val file = ModelManager.modelFile(appContext)
            ?: error("Whisper model not downloaded yet")
        withContext(Dispatchers.IO) {
            WhisperContext.createContextFromFile(file.absolutePath)
        }.also { ctx = it }
    }

    suspend fun transcribe(appContext: Context, audio: FloatArray): String {
        if (audio.isEmpty()) return ""
        val padded = if (audio.size < MIN_SAMPLES) audio.copyOf(MIN_SAMPLES) else audio
        return load(appContext).transcribeData(padded, printTimestamp = false).clean()
    }

    /** Whisper emits markers like [BLANK_AUDIO] / (silence) for non-speech. */
    private fun String.clean(): String =
        replace(Regex("""\[[A-Z_ ]+]|\((?:silence|music|noise)\)""", RegexOption.IGNORE_CASE), "")
            .trim()
}
