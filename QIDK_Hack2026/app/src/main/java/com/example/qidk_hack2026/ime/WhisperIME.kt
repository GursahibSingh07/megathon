package com.example.qidk_hack2026.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.qidk_hack2026.MainActivity
import com.example.qidk_hack2026.asr.DictationSession
import com.example.qidk_hack2026.asr.ModelManager
import com.example.qidk_hack2026.tts.PiperTts
import com.example.qidk_hack2026.form.FillResult
import com.example.qidk_hack2026.form.FormPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Voice-only IME. It declares a subtype with mode "voice" (res/xml/method.xml), which is what
 * HeliBoard's mic key looks for. Tapping the mic there switches to this IME, which starts
 * listening immediately, commits the transcript into the focused field when stopped, and then
 * hands control back to the previous keyboard.
 */
class WhisperIME : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: DictationSession? = null
    private var busy = false

    // "Fill form" mode: instead of typing the transcript into the focused field, the transcript is
    // matched to the form on screen and typed into the right fields (see FormPipeline).
    private var formMode = false
    private var needsAccessibility = false
    // Filling a form moves focus between fields, which restarts this keyboard's input; don't let
    // that restart start another recording.
    private var noAutoListenUntil = 0L

    private lateinit var status: TextView
    private lateinit var partialText: TextView
    private lateinit var action: Button
    private lateinit var speakButton: Button
    private lateinit var modeButton: Button

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        partialText = TextView(this)
        action = Button(this).apply { setOnClickListener { onActionClicked() } }
        formMode = prefs().getBoolean(PREF_FORM_MODE, false)
        modeButton = Button(this).apply {
            text = modeLabel()
            setOnClickListener {
                if (busy || session?.isRecording == true) return@setOnClickListener
                formMode = !formMode
                prefs().edit().putBoolean(PREF_FORM_MODE, formMode).apply()
                text = modeLabel()
            }
        }
        val back = Button(this).apply {
            text = "Back to keyboard"
            setOnClickListener {
                currentInputConnection?.finishComposingText()
                switchBack()
                cancelSession() // after switching, so the keyboard appears without waiting
            }
        }
        val speak = Button(this).apply {
            text = "Read text aloud"
            setOnClickListener { speakFieldText(this) }
        }.also { speakButton = it }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(16), px(16), px(16), px(16))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            addView(status); addView(action); addView(speak); addView(modeButton); addView(back)
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting && SystemClock.uptimeMillis() >= noAutoListenUntil) beginListening()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Returning from the permission dialog: start listening without another tap.
        if (session == null && !busy && SystemClock.uptimeMillis() >= noAutoListenUntil &&
            hasMicPermission() && ModelManager.isReady(this)
        ) beginListening()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelSession()
        currentInputConnection?.finishComposingText()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        PiperTts.stop()
        cancelSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun onActionClicked() {
        when {
            needsAccessibility -> {
                needsAccessibility = false
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            session?.isRecording == true -> finishListening()
            !hasMicPermission() -> requestMicPermission()
            !ModelManager.isReady(this) -> openApp()
            else -> beginListening()
        }
    }

    private fun beginListening() {
        if (busy) return
        if (PiperTts.isSpeaking) {
            // Block speech-to-text while text-to-speech plays, or the mic would transcribe the speaker.
            setIdle("Speech is playing", "Try again when it ends")
            return
        }
        partialText.text = ""
        when {
            !hasMicPermission() -> {
                setIdle("Microphone permission needed", "Grant microphone access")
                return
            }
            !ModelManager.isReady(this) -> {
                setIdle("Whisper model not downloaded", "Open app to download")
                return
            }
        }
        // Silence after speech ends the dictation, like a voice keyboard should.
        val s = DictationSession(
            this, scope,
            onAutoStop = { scope.launch { if (session?.isRecording == true) finishListening() } },
        ) {
            if (formMode) {
                // The focused field belongs to the form; keep the live text in the panel instead.
                status.text = "Listening… $it"
            } else {
                // Live transcript goes into the focused text field itself, not the panel.
                currentInputConnection?.setComposingText(it, 1)
            }
        }
        if (s.start()) {
            session = s
            updateSpeakButton()
            status.text = if (formMode) "Listening… say the details for the form" else "Listening…"
            action.text = "Done"
        } else {
            setIdle("Couldn't open the microphone", "Try again")
        }
    }

    private fun finishListening() {
        val s = session ?: return
        session = null
        busy = true
        updateSpeakButton()
        status.text = "Transcribing…"
        action.isEnabled = false
        scope.launch {
            val text = runCatching { s.stop() }.getOrElse {
                busy = false; action.isEnabled = true; updateSpeakButton()
                setIdle("Transcription failed: ${it.message}", "Try again")
                return@launch
            }
            if (formMode && text.isNotBlank()) {
                fillForm(text)
                return@launch
            }
            busy = false
            action.isEnabled = true
            updateSpeakButton()
            if (text.isNotBlank()) {
                currentInputConnection?.commitText(text, 1)
                switchBack()
            } else {
                currentInputConnection?.setComposingText("", 1)
                setIdle("Didn't catch that", "Try again")
            }
        }
    }

    private suspend fun fillForm(transcript: String) {
        status.text = "Filling the form…"
        noAutoListenUntil = SystemClock.uptimeMillis() + AUTO_LISTEN_PAUSE_MS
        val result = FormPipeline.run(transcript)
        noAutoListenUntil = SystemClock.uptimeMillis() + AUTO_LISTEN_PAUSE_MS
        busy = false
        action.isEnabled = true

        val missing = result.missing.take(4).joinToString(", ") +
            if (result.missing.size > 4) " and ${result.missing.size - 4} more" else ""
        when (result.status) {
            FillResult.Status.SERVICE_OFF -> {
                needsAccessibility = true
                setIdle("Turn on \"Whisper Form Filler\" in Accessibility settings to fill forms", "Open accessibility settings")
            }
            FillResult.Status.NO_FORM -> setIdle("No form found behind the keyboard", "Try again")
            FillResult.Status.NOTHING_MATCHED ->
                setIdle("Heard \"$transcript\" but matched no fields." + if (missing.isNotEmpty()) " Empty: $missing" else "", "Speak again")
            FillResult.Status.DONE -> setIdle(
                "Filled ${result.filled} field(s)." +
                    (if (result.failed > 0) " ${result.failed} failed." else "") +
                    (if (missing.isNotEmpty()) " Still empty: $missing" else " All fields have a value."),
                if (missing.isNotEmpty()) "Speak the rest" else "Dictate again",
            )
        }
    }

    private fun modeLabel() = if (formMode) "Mode: fill form (tap for dictation)" else "Mode: dictation (tap for form fill)"

    private fun prefs() = getSharedPreferences("ime", MODE_PRIVATE)

    /** Reads the text before the cursor in the focused field with the Piper (NPU) voice. */
    private fun speakFieldText(button: Button) {
        if (PiperTts.isSpeaking) { PiperTts.stop(); return }
        if (session != null || busy) return // don't speak while the mic is open
        val text = currentInputConnection?.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        if (text.isBlank()) { status.text = "Nothing to read"; return }
        button.text = "Stop reading"
        scope.launch {
            runCatching { PiperTts.speak(this@WhisperIME, text) }
                .onFailure { status.text = "Speech failed: ${it.message}" }
            button.text = "Read text aloud"
        }
    }

    private fun updateSpeakButton() {
        speakButton.isEnabled = session == null && !busy
    }

    private fun cancelSession() {
        val s = session ?: return
        session = null
        if (::speakButton.isInitialized) updateSpeakButton()
        Thread { s.cancel() }.start() // recorder.stop() joins its thread; keep it off the UI thread
    }

    private fun setIdle(message: String, actionLabel: String) {
        status.text = message
        action.text = actionLabel
    }

    private fun switchBack() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .switchToLastInputMethod(window.window?.attributes?.token)
        }
        if (!switched) requestHideSelf(0)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        startActivity(
            Intent(this, PermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val PREF_FORM_MODE = "form_mode"
        const val AUTO_LISTEN_PAUSE_MS = 4_000L
    }
}
