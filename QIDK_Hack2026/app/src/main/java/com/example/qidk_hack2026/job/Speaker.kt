package com.example.qidk_hack2026.job

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text to speech for the questions about missing fields and the read-aloud button on records. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private val callbacks = mutableMapOf<String, () -> Unit>()

    @Volatile
    var speaking = false
        private set

    fun speak(context: Context, text: String, onDone: (() -> Unit)? = null) {
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
