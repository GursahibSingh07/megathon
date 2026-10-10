package com.example.qidk_hack2026.asr

import android.content.Context
import com.example.qidk_hack2026.audio.AudioPrep
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

    /**
     * Minimum encoder frames (20 ms each) when trimming the 30 s window to the real audio.
     * 500 = 10 s: a safe speed-up for stock Whisper models. A model fine-tuned for short
     * contexts can go much lower (e.g. 100-150); 0 disables trimming.
     */
    private const val AUDIO_CTX_FLOOR = 500

    /** Beam width for finished utterances; live previews always use greedy. */
    private const val FINAL_BEAM_SIZE = 3

    private const val MAX_PROMPT_CHARS = 200

    suspend fun load(appContext: Context): WhisperContext = loadLock.withLock {
        ctx?.let { return it }
        val file = ModelManager.modelFile(appContext)
            ?: error("Whisper model not downloaded yet")
        withContext(Dispatchers.IO) {
            WhisperContext.createContextFromFile(file.absolutePath)
        }.also { ctx = it }
    }

    /** Loads the model ahead of time so the first utterance doesn't pay for it. */
    suspend fun preload(appContext: Context) {
        if (ModelManager.isReady(appContext)) runCatching { load(appContext) }
    }

    /**
     * @param context text that came before this audio; used as a decoding prompt.
     * @param final true for a finished utterance (beam search), false for a live preview.
     */
    suspend fun transcribe(
        appContext: Context,
        audio: FloatArray,
        context: String = "",
        final: Boolean = true,
    ): String {
        if (audio.isEmpty()) return ""
        val prepared = AudioPrep.prepare(audio)
        val padded = if (prepared.size < MIN_SAMPLES) prepared.copyOf(MIN_SAMPLES) else prepared
        val prompt = context.takeLast(MAX_PROMPT_CHARS).trim().ifEmpty { null }
        return load(appContext).transcribeData(
            padded,
            printTimestamp = false,
            prompt = prompt,
            beamSize = if (final) FINAL_BEAM_SIZE else 1,
            audioCtxFloor = AUDIO_CTX_FLOOR,
        ).clean()
    }

    private val markers =
        Regex("""\[[A-Z_ ]+]|\((?:silence|music|noise|[a-z ]*noise[a-z ]*)\)|♪+""", RegexOption.IGNORE_CASE)

    // Whisper sometimes falls into a loop ("and then and then and then ...").
    private val loops = Regex("""(\b[\w' ]{2,40}?)(?:[ ,.]+\1){3,}""", RegexOption.IGNORE_CASE)

    /** Whisper emits markers like [BLANK_AUDIO] / (silence) for non-speech. */
    private fun String.clean(): String =
        replace(markers, "")
            .replace(loops, "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
