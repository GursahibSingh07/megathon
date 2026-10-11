package com.example.qidk_hack2026.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.example.qidk_hack2026.MainActivity
import com.example.qidk_hack2026.asr.DictationSession
import com.example.qidk_hack2026.asr.ModelManager
import com.example.qidk_hack2026.asr.NpuClient
import com.example.qidk_hack2026.form.FillResult
import com.example.qidk_hack2026.form.FormPipeline
import com.example.qidk_hack2026.form.MissingFieldPrompt
import com.example.qidk_hack2026.tts.PiperTts
import com.example.qidk_hack2026.form.SlmFormExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var needsModel = false
    // Filling a form moves focus between fields, which restarts this keyboard's input; don't let
    // that restart reset the status message showing the result.
    private var noAutoListenUntil = 0L
    // Set when the user tapped the mic and was sent to the permission dialog; start once they return.
    private var resumeAfterPermission = false

    // Retry loop for form mode: after each pass the empty fields are read aloud (Piper) and the mic
    // reopens, until the form is complete, the user stops, or passes stop making progress.
    private var speakJob: Job? = null
    private var silenceJob: Job? = null
    private var fruitlessRounds = 0
    private var heardAnything = false

    private lateinit var panel: KeyboardPanel

    override fun onCreateInputView(): View {
        formMode = prefs().getBoolean(PREF_FORM_MODE, false)
        panel = KeyboardPanel(this).apply {
            setMode(formMode)
            modeLocked = { busy }
            // The mic always retries (the service or model may be there now); the button under the status
            // line is what sends the user to settings or the app.
            onMic = {
                fruitlessRounds = 0
                needsModel = false
                needsAccessibility = false
                onActionClicked()
            }
            onHint = { fruitlessRounds = 0; onActionClicked() }
            onModeChanged = {
                stopSpeaking()
                // Switching mid-recording drops that recording; the new mode starts clean.
                if (session?.isRecording == true) {
                    cancelSession()
                    currentInputConnection?.setComposingText("", 1) // drop the live preview text
                    setIdle(if (it) "Fill form: tap the mic and say the details" else "Dictate: tap the mic and speak")
                }
                formMode = it
                prefs().edit().putBoolean(PREF_FORM_MODE, formMode).apply()
            }
            onBack = {
                stopSpeaking()
                currentInputConnection?.finishComposingText()
                switchBack()
                cancelSession() // after switching, so the keyboard appears without waiting
            }
        }
        return panel.root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // The mic starts off; the user taps it. (Filling a form moves focus and restarts input, so keep the
        // result message in that case.)
        if (!restarting && session == null && !busy && SystemClock.uptimeMillis() >= noAutoListenUntil) {
            setIdle(if (formMode) "Fill form: tap the mic and say the details" else "Tap the mic to speak")
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Frosted glass: blur whatever is behind the panel (Android 12+, where the device allows it).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setBackgroundBlurRadius(BLUR_RADIUS_PX)
            }
        }
        // Returning from the permission dialog: start listening without another tap.
        if (resumeAfterPermission && session == null && !busy && hasMicPermission()) {
            resumeAfterPermission = false
            beginListening()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Filling fields moves focus and restarts input; only a real exit ends the spoken prompt.
        if (finishingInput) stopSpeaking()
        cancelSession()
        if (::panel.isInitialized) panel.setState(KeyboardPanel.State.IDLE) // stops the pulse animation
        currentInputConnection?.finishComposingText()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopSpeaking()
        cancelSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun onActionClicked() {
        when {
            speakJob?.isActive == true -> { // tap while the question is being read: skip it and answer
                stopSpeaking()
                beginListening()
            }
            needsAccessibility -> {
                needsAccessibility = false
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            session?.isRecording == true -> finishListening()
            !hasMicPermission() -> requestMicPermission()
            needsModel -> openApp()
            else -> beginListening()
        }
    }

    private fun beginListening(autoStarted: Boolean = false) {
        if (busy) return
        if (!hasMicPermission()) {
            setIdle("Microphone permission needed", "Grant microphone access")
            return
        }
        busy = true // also covers the readiness check below, so a second trigger can't start a second session
        scope.launch {
            // Either the on-device CPU model or the NPU service can do the transcription.
            val ready = ModelManager.isReady(this@WhisperIME) || NpuClient.probe()
            busy = false
            if (!ready) {
                needsModel = true
                setIdle("Whisper model not downloaded", "Open app to download")
                return@launch
            }
            needsModel = false
            startSession(autoStarted)
        }
    }

    private fun startSession(autoStarted: Boolean = false) {
        heardAnything = false
        if (formMode) scope.launch { PiperTts.preload(this@WhisperIME) } // overlap voice load with the user speaking
        // Silence after speech ends the dictation, like a voice keyboard should.
        val s = DictationSession(
            this, scope,
            onAutoStop = { scope.launch { if (session?.isRecording == true) finishListening() } },
        ) {
            if (it.isNotBlank()) heardAnything = true
            if (formMode) {
                // The focused field belongs to the form; keep the live text in the panel instead.
                panel.setStatus("Listening… $it")
            } else {
                // Live transcript goes into the focused text field itself, not the panel.
                currentInputConnection?.setComposingText(it, 1)
            }
        }
        if (s.start()) {
            session = s
            panel.setState(KeyboardPanel.State.LISTENING)
            panel.setStatus(if (formMode) "Listening… say the details for the form" else "Listening…")
            if (autoStarted) {
                // Nobody answered the question: end this pass instead of listening forever.
                silenceJob = scope.launch {
                    delay(NO_ANSWER_TIMEOUT_MS)
                    if (session === s && !heardAnything) finishListening()
                }
            }
        } else {
            setIdle("Couldn't open the microphone. Tap to try again.")
        }
    }

    private fun finishListening() {
        val s = session ?: return
        silenceJob?.cancel()
        session = null
        busy = true
        panel.setState(KeyboardPanel.State.WORKING)
        panel.setStatus("Transcribing…")
        scope.launch {
            val text = runCatching { s.stop() }.getOrElse {
                busy = false
                setIdle("Transcription failed. Tap to try again.")
                return@launch
            }
            if (formMode && text.isNotBlank()) {
                fillForm(text)
                return@launch
            }
            busy = false
            panel.setState(KeyboardPanel.State.IDLE)
            if (text.isNotBlank()) {
                currentInputConnection?.commitText(text, 1)
                // Stay on the panel: leaving here made the mode switch unreachable. The keyboard icon leaves.
                setIdle("Inserted. Tap the mic to continue.")
            } else {
                currentInputConnection?.setComposingText("", 1)
                setIdle("Didn't catch that. Tap to try again.")
            }
        }
    }

    private suspend fun fillForm(transcript: String) {
        panel.setStatus("Filling the form…")
        noAutoListenUntil = SystemClock.uptimeMillis() + AUTO_LISTEN_PAUSE_MS
        val result = FormPipeline.run(transcript, SlmFormExtractor.get(this))
        noAutoListenUntil = SystemClock.uptimeMillis() + AUTO_LISTEN_PAUSE_MS
        busy = false
        panel.setState(KeyboardPanel.State.IDLE)

        val missing = result.missing.take(4).joinToString(", ") +
            if (result.missing.size > 4) " and ${result.missing.size - 4} more" else ""
        when (result.status) {
            FillResult.Status.SERVICE_OFF -> {
                needsAccessibility = true
                setIdle("Turn on Whisper Form Filler in Accessibility settings to fill forms", "Open settings")
            }
            FillResult.Status.NO_FORM -> setIdle("No form found behind the keyboard. Tap to try again.")
            FillResult.Status.NOTHING_MATCHED -> {
                setIdle("Heard \"$transcript\" but matched no fields." + if (missing.isNotEmpty()) " Empty: $missing" else "")
                askForMissing(result)
            }
            FillResult.Status.DONE -> setIdle(
                "Filled ${result.filled} field(s) (${if (result.usedSlm) "AI" else "rule-based fallback"})." +
                    (if (result.failed > 0) " ${result.failed} failed." else "") +
                    (if (missing.isNotEmpty()) " Still empty: $missing. Tap to speak the rest." else " All fields have a value."),
            ).also { askForMissing(result) }
        }
    }

    /**
     * The retry loop: read the still-empty fields aloud, then listen for the answer. Only ever asks;
     * submitting the form is left to the user.
     */
    private fun askForMissing(result: FillResult) {
        fruitlessRounds = if (result.filled > 0) 0 else fruitlessRounds + 1
        val (text, listenAfter) = when {
            result.missing.isEmpty() -> MissingFieldPrompt.allDone() to false
            fruitlessRounds > MAX_FRUITLESS_ROUNDS -> MissingFieldPrompt.givingUp() to false
            else -> MissingFieldPrompt.ask(result.missing, didntCatch = result.filled == 0) to true
        }
        if (!PiperTts.isAvailable(this)) return // no voice bundled: the text status above is all there is
        speakJob?.cancel()
        speakJob = scope.launch {
            // Tell the user what is being read; the mic stays tappable to cut it short.
            panel.setStatus(text)
            val spoken = PiperTts.speak(this@WhisperIME, text)
            if (spoken && listenAfter && !busy && session == null && hasMicPermission()) {
                beginListening(autoStarted = true)
            }
        }
    }

    private fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        silenceJob?.cancel()
        PiperTts.stop()
    }

    private fun prefs() = getSharedPreferences("ime", MODE_PRIVATE)

    private fun cancelSession() {
        val s = session ?: return
        session = null
        Thread { s.cancel() }.start() // recorder.stop() joins its thread; keep it off the UI thread
    }

    /** Shows [message] and goes back to idle. [actionLabel] becomes a button only when the user has to do something. */
    private fun setIdle(message: String, actionLabel: String? = null) {
        panel.setState(KeyboardPanel.State.IDLE)
        panel.setStatus(message, actionLabel)
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
        resumeAfterPermission = true
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
        const val MAX_FRUITLESS_ROUNDS = 2
        const val NO_ANSWER_TIMEOUT_MS = 10_000L
        const val BLUR_RADIUS_PX = 60
    }
}
