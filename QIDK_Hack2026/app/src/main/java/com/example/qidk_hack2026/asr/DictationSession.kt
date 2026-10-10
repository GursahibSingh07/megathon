package com.example.qidk_hack2026.asr

import android.content.Context
import com.example.qidk_hack2026.audio.AudioRecorder
import com.example.qidk_hack2026.audio.EndpointDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One record -> transcribe cycle. Shared by MainActivity and WhisperIME.
 *
 * Audio is split into utterances at natural pauses (see [EndpointDetector]). Each finished
 * utterance is decoded exactly once, in order, with the text so far as context; by the time the
 * user stops, only the last fragment is left to decode. While someone is mid-sentence,
 * [onPartial] gets the committed text plus a quick preview of the current utterance.
 *
 * @param onAutoStop called (on the recorder thread) after a long silence following speech; the
 *   host decides whether to call [stop].
 */
class DictationSession(
    context: Context,
    private val scope: CoroutineScope,
    private val onAutoStop: (() -> Unit)? = null,
    private val onPartial: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val recorder = AudioRecorder()

    private class Chunk(val start: Int, val end: Int)

    private var chunks = Channel<Chunk>(Channel.UNLIMITED) // fresh per recording
    private var worker: Job? = null
    private var previewJob: Job? = null

    private val lock = Any()
    private var committed = ""
    @Volatile private var decoding = false
    private val autoStopped = AtomicBoolean(false)

    val isRecording get() = recorder.isRecording

    fun start(): Boolean {
        synchronized(lock) { committed = "" }
        autoStopped.set(false)
        val queue = Channel<Chunk>(Channel.UNLIMITED)
        chunks = queue

        val started = recorder.start(object : EndpointDetector.Listener {
            override fun onSegment(startSample: Int, endSample: Int) {
                queue.trySend(Chunk(startSample, endSample))
            }

            override fun onLongSilence() {
                if (autoStopped.compareAndSet(false, true)) onAutoStop?.invoke()
            }
        })
        if (!started) return false

        worker = scope.launch {
            // Overlap the model load with the user starting to talk.
            WhisperEngine.preload(appContext)
            for (chunk in queue) decodeChunk(chunk)
        }
        previewJob = scope.launch {
            while (isActive) {
                delay(PREVIEW_INTERVAL_MS)
                preview()
            }
        }
        return true
    }

    /** Stops recording and returns the final transcript of the whole utterance. */
    suspend fun stop(): String {
        previewJob?.cancel()
        previewJob = null
        val total = recorder.stop() // flushes the open utterance into [chunks]

        // Detector heard nothing but the mic did pick up sound: don't throw the audio away.
        val heard = recorder.heardSpeech
        if (!heard && total >= MIN_FALLBACK_SAMPLES && recorder.peakDb > FALLBACK_PEAK_DB) {
            chunks.trySend(Chunk(0, total))
        }
        chunks.close()
        worker?.join()
        worker = null
        return synchronized(lock) { committed }.trim()
    }

    fun cancel() {
        previewJob?.cancel()
        previewJob = null
        worker?.cancel()
        worker = null
        chunks.close()
        recorder.stop()
    }

    private suspend fun decodeChunk(chunk: Chunk) {
        val audio = recorder.slice(chunk.start, chunk.end)
        val before = synchronized(lock) { committed }
        decoding = true
        val text = try {
            runCatching { WhisperEngine.transcribe(appContext, audio, before, final = true) }
                .getOrDefault("")
        } finally {
            decoding = false
        }
        if (text.isEmpty()) return
        val now = synchronized(lock) {
            committed = if (committed.isEmpty()) text else "$committed $text"
            committed
        }
        onPartial(now)
    }

    /** Cheap greedy decode of the utterance in progress, skipped while the worker is busy. */
    private suspend fun preview() {
        if (decoding || !recorder.isRecording) return
        val start = recorder.liveSegmentStart
        if (start < 0) return
        val end = recorder.sampleCount
        // Only the most recent seconds: keeps the preview cheap however long the utterance runs.
        val from = maxOf(start, end - PREVIEW_MAX_SAMPLES)
        if (end - from < MIN_PREVIEW_SAMPLES) return

        val base = synchronized(lock) { committed }
        val text = runCatching {
            WhisperEngine.transcribe(appContext, recorder.slice(from, end), base, final = false)
        }.getOrNull() ?: return
        if (text.isNotEmpty() && recorder.isRecording) {
            onPartial(if (base.isEmpty()) text else "$base $text")
        }
    }

    private companion object {
        const val PREVIEW_INTERVAL_MS = 1_200L
        const val MIN_PREVIEW_SAMPLES = AudioRecorder.SAMPLE_RATE * 6 / 5
        const val PREVIEW_MAX_SAMPLES = AudioRecorder.SAMPLE_RATE * 8
        const val MIN_FALLBACK_SAMPLES = AudioRecorder.SAMPLE_RATE * 11 / 10
        const val FALLBACK_PEAK_DB = -40f
    }
}
