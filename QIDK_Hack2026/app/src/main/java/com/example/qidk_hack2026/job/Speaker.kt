package com.example.qidk_hack2026.job

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.qidk_hack2026.tts.PiperTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Text to speech for the questions about missing fields and the read-aloud button on records.
 * Speaks with the Piper voice (decoder on the NPU, CPU fallback; see [PiperTts]) and falls back to
 * Android's built-in TextToSpeech if the Piper voice is missing or fails.
 */
object Speaker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var piperJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private val callbacks = mutableMapOf<String, () -> Unit>()

    @Volatile
    var speaking = false
        private set

    fun speak(context: Context, text: String, onDone: (() -> Unit)? = null) {
        piperJob?.cancel()
        PiperTts.stop()
        if (!PiperTts.isAvailable(context)) return speakAndroid(context, text, onDone)
        speaking = true
        val job = scope.launch {
            val ok = PiperTts.speak(context.applicationContext, text)
            if (!ok) speakAndroid(context, text, onDone) // no usable Piper voice: use the system one
            else { speaking = false; onDone?.invoke() }
        }
        piperJob = job
        job.invokeOnCompletion { cause -> // stop() cancels the job: callers still get their callback
            if (cause != null) {
                if (piperJob === job) speaking = false
                onDone?.invoke()
            }
        }
    }

    private fun speakAndroid(context: Context, text: String, onDone: (() -> Unit)?) {
        if (tts == null) {
            pending = text to onDone
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    val engine = tts
                    val want = Locale.getDefault()
                    engine?.language = if ((engine?.isLanguageAvailable(want) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0) want else Locale.US
                    engine?.setOnUtteranceProgressListener(listener)
                }
                pending?.let { (t, cb) ->
                    pending = null
                    if (ready) say(t, cb) else cb?.invoke()
                }
            }
        } else if (ready) say(text, onDone) else pending = text to onDone
    }

    fun stop() {
        piperJob?.cancel()
        piperJob = null
        PiperTts.stop()
        tts?.stop()
        speaking = false
    }

    private fun say(text: String, onDone: (() -> Unit)?) {
        val id = "u" + System.nanoTime()
        onDone?.let { callbacks[id] = it }
        speaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { speaking = true }
        override fun onDone(utteranceId: String?) = finished(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)

        private fun finished(id: String?) {
            speaking = false
            id?.let { callbacks.remove(it) }?.invoke()
        }
    }
}
