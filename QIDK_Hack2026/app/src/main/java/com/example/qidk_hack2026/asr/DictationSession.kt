package com.example.qidk_hack2026.asr

import android.content.Context
import com.example.qidk_hack2026.audio.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One record -> transcribe cycle. Shared by MainActivity and WhisperIME:
 * AudioRecorder -> WhisperEngine -> text. While recording, [onPartial] gets a rolling
 * transcript of everything said so far.
 */
class DictationSession(
    context: Context,
    private val scope: CoroutineScope,
    private val onPartial: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val recorder = AudioRecorder()
    private var partialJob: Job? = null

    val isRecording get() = recorder.isRecording

    fun start(): Boolean {
        if (!recorder.start()) return false
        partialJob = scope.launch {
            while (isActive) {
                delay(PARTIAL_INTERVAL_MS)
                val audio = recorder.snapshot()
                if (audio.size < MIN_PARTIAL_SAMPLES) continue
                // Runs on Whisper's single thread, so it never overlaps the next one.
                runCatching { WhisperEngine.transcribe(appContext, audio) }
                    .getOrNull()?.let { if (isActive) onPartial(it) }
            }
        }
        return true
    }

    /** Stops recording and returns the final transcript of the whole utterance. */
    suspend fun stop(): String {
        partialJob?.cancel()
        partialJob = null
        return WhisperEngine.transcribe(appContext, recorder.stop())
    }

    fun cancel() {
        partialJob?.cancel()
        partialJob = null
        recorder.stop()
    }

    private companion object {
        const val PARTIAL_INTERVAL_MS = 2_500L
        const val MIN_PARTIAL_SAMPLES = AudioRecorder.SAMPLE_RATE * 3 / 2
    }
}
