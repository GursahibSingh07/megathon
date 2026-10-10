package com.example.qidk_hack2026

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.qidk_hack2026.asr.DictationSession
import com.example.qidk_hack2026.asr.ModelManager
import com.example.qidk_hack2026.asr.WhisperEngine
import com.example.qidk_hack2026.ui.theme.QIDK_Hack2026Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        debugLogShortcutIme() // TEMP-DEBUG
        setContent {
            QIDK_Hack2026Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    DictationScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

private enum class Phase { NoModel, Downloading, Loading, Idle, Recording, Transcribing }

@Composable
private fun DictationScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    var phase by remember {
        mutableStateOf(if (ModelManager.isReady(appContext)) Phase.Loading else Phase.NoModel)
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var transcript by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var testField by remember { mutableStateOf("") }

    val session = remember {
        DictationSession(appContext, scope) { partial -> transcript = partial }
    }

    // Load the model into memory as soon as it's on disk.
    androidx.compose.runtime.LaunchedEffect(phase) {
        if (phase == Phase.Loading) {
            runCatching { WhisperEngine.load(appContext) }
                .onSuccess { phase = Phase.Idle }
                .onFailure { error = "Failed to load model: ${it.message}"; phase = Phase.NoModel }
        }
    }

    fun startRecording() {
        error = null
        transcript = ""
        if (session.start()) phase = Phase.Recording else error = "Couldn't open the microphone"
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startRecording() else error = "Microphone permission is required"
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Whisper dictation", style = MaterialTheme.typography.headlineSmall)
        Text("Model: ${ModelManager.MODEL_NAME}", style = MaterialTheme.typography.bodySmall)

        when (phase) {
            Phase.NoModel -> Button(onClick = {
                phase = Phase.Downloading; progress = 0f; error = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { ModelManager.download(appContext) { progress = it } }
                    }.onSuccess { phase = Phase.Loading }
                        .onFailure { error = "Download failed: ${it.message}"; phase = Phase.NoModel }
                }
            }) { Text("Download model (~180 MB)") }

            Phase.Downloading -> {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("Downloading… ${(progress * 100).toInt()}%")
            }

            Phase.Loading -> Text("Loading model…")

            Phase.Idle, Phase.Recording, Phase.Transcribing -> Button(
                enabled = phase != Phase.Transcribing,
                onClick = {
                    if (phase == Phase.Recording) {
                        phase = Phase.Transcribing
                        scope.launch {
                            transcript = runCatching { session.stop() }
                                .getOrElse { error = "Transcription failed: ${it.message}"; transcript }
                            phase = Phase.Idle
                        }
                    } else if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
            ) {
                Text(
                    when (phase) {
                        Phase.Recording -> "Stop"
                        Phase.Transcribing -> "Transcribing…"
                        else -> "Start listening"
                    }
                )
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                transcript.ifEmpty { "Transcript will appear here." },
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Text("Keyboard setup", style = MaterialTheme.typography.titleSmall)
        Text(
            "1. Enable \"Whisper Voice Input\" and HeliBoard in system keyboard settings.\n" +
                "2. Select HeliBoard as your keyboard; its mic key will switch to Whisper.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }) { Text("Enable keyboards") }
        OutlinedButton(onClick = {
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }) { Text("Choose keyboard") }
        OutlinedTextField(
            value = testField, onValueChange = { testField = it },
            label = { Text("Test field — tap, then use the keyboard's mic") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}


// TEMP-DEBUG: what does Android report as voice shortcut IMEs? (the list HeliBoard uses)
private fun android.content.Context.debugLogShortcutIme() {
    try {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val m = InputMethodManager::class.java.getMethod("getShortcutInputMethodsAndSubtypes")
        val map = m.invoke(imm) as Map<*, *>
        android.util.Log.w("SHORTCUTDBG", "shortcut IMEs: " + map.entries.joinToString { (k, v) ->
            "${(k as android.view.inputmethod.InputMethodInfo).id}=${(v as List<*>).size}"
        })
        imm.enabledInputMethodList.forEach { imi ->
            val subs = imm.getEnabledInputMethodSubtypeList(imi, true)
            android.util.Log.w("SHORTCUTDBG", "${imi.id} sys=${imi.isSystem} enabledSubtypes=" + subs.joinToString { it.mode + "/" + it.locale })
        }
    } catch (e: Throwable) {
        android.util.Log.w("SHORTCUTDBG", "failed", e)
    }
}
