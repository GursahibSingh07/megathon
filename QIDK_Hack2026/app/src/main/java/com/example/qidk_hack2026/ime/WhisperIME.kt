package com.example.qidk_hack2026.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
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

    private lateinit var status: TextView
    private lateinit var partialText: TextView
    private lateinit var action: Button

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        partialText = TextView(this)
        action = Button(this).apply { setOnClickListener { onActionClicked() } }
        val back = Button(this).apply {
            text = "Back to keyboard"
            setOnClickListener {
                currentInputConnection?.finishComposingText()
                switchBack()
                cancelSession() // after switching, so the keyboard appears without waiting
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(16), px(16), px(16), px(16))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            addView(status); addView(action); addView(back)
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) beginListening()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Returning from the permission dialog: start listening without another tap.
        if (session == null && !busy && hasMicPermission() && ModelManager.isReady(this)) beginListening()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelSession()
        currentInputConnection?.finishComposingText()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        cancelSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun onActionClicked() {
        when {
            session?.isRecording == true -> finishListening()
            !hasMicPermission() -> requestMicPermission()
            !ModelManager.isReady(this) -> openApp()
            else -> beginListening()
        }
    }

    private fun beginListening() {
        if (busy) return
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
            // Live transcript goes into the focused text field itself, not the panel.
            currentInputConnection?.setComposingText(it, 1)
        }
        if (s.start()) {
            session = s
            status.text = "Listening…"
            action.text = "Done"
        } else {
            setIdle("Couldn't open the microphone", "Try again")
        }
    }

    private fun finishListening() {
        val s = session ?: return
        session = null
        busy = true
        status.text = "Transcribing…"
        action.isEnabled = false
        scope.launch {
            val text = runCatching { s.stop() }.getOrElse {
                busy = false; action.isEnabled = true
                setIdle("Transcription failed: ${it.message}", "Try again")
                return@launch
            }
            busy = false
            action.isEnabled = true
            if (text.isNotBlank()) {
                currentInputConnection?.commitText(text, 1)
                switchBack()
            } else {
                currentInputConnection?.setComposingText("", 1)
                setIdle("Didn't catch that", "Try again")
            }
        }
    }

    private fun cancelSession() {
        val s = session ?: return
        session = null
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
}
