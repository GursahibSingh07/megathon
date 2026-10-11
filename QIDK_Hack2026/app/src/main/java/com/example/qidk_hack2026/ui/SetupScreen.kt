package com.example.qidk_hack2026.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.qidk_hack2026.R
import com.example.qidk_hack2026.asr.ModelManager
import com.example.qidk_hack2026.asr.NpuClient
import com.example.qidk_hack2026.form.FormPipeline
import com.example.qidk_hack2026.ime.WhisperIME
import com.example.qidk_hack2026.ui.components.Divider
import com.example.qidk_hack2026.ui.components.GlassButton
import com.example.qidk_hack2026.ui.components.GlassCard
import com.example.qidk_hack2026.ui.components.PrimaryButton
import com.example.qidk_hack2026.ui.components.SectionLabel
import com.example.qidk_hack2026.ui.theme.Glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the app needs from the system before the voice keyboard can do its job. */
data class SetupState(
    val microphone: Boolean = false,
    val keyboard: Boolean = false,
    val accessibility: Boolean = false,
    val speech: Boolean = false,
    val typingKeyboard: Boolean = false,
) {
    val ready get() = microphone && keyboard && accessibility && speech
    val remaining get() = listOf(microphone, keyboard, accessibility, speech).count { !it }
}

suspend fun readSetup(context: Context): SetupState {
    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val kb = imm.enabledInputMethodList.any { it.packageName == context.packageName && it.serviceName == WhisperIME::class.java.name }
    val speech = ModelManager.isReady(context) || withContext(Dispatchers.IO) { NpuClient.probe() }
    val heli = runCatching { context.packageManager.getPackageInfo("helium314.keyboard", 0) }.isSuccess
    return SetupState(mic, kb, FormPipeline.isServiceEnabled(context), speech, heli)
}

@Composable
fun SetupScreen(state: SetupState, firstRun: Boolean, onRefresh: () -> Unit, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val g = Glass.colors
    val scope = rememberCoroutineScope()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }
    var progress by remember { mutableFloatStateOf(-1f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Reveal(0) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (firstRun) "Set up Voice Fill" else "Setup", style = MaterialTheme.typography.displaySmall, color = g.text)
                Text(
                    "Four things, once. Everything runs on this device.",
                    style = MaterialTheme.typography.bodyLarge, color = g.textMuted,
                )
            }
        }

        Reveal(1) {
            GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 20.dp)) {
                Column {
                    Step(
                        "Microphone", "Hears you while you dictate.", state.microphone,
                        if (state.microphone) null else "Allow",
                    ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    Divider()
                    Step(
                        "Voice keyboard", "Turn on Whisper Voice Input in the list.", state.keyboard,
                        if (state.keyboard) null else "Open",
                    ) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                    Divider()
                    Step(
                        "Form access", "Lets it read the form and type into it.", state.accessibility,
                        if (state.accessibility) null else "Open",
                    ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    Divider()
                    Step(
                        "Speech engine",
                        when {
                            downloadError != null -> downloadError!!
                            progress >= 0f -> "Downloading ${(progress * 100).toInt()}%"
                            state.speech -> "Ready."
                            else -> "No NPU service found. Download the on-device model instead (180 MB)."
                        },
                        state.speech,
                        if (state.speech || progress >= 0f) null else "Download",
                    ) {
                        downloadError = null
                        progress = 0f
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { ModelManager.download(context.applicationContext) { progress = it } } }
                                .onFailure { downloadError = "Download failed: ${it.message}" }
                            progress = -1f
                            onRefresh()
                        }
                    }
                }
            }
        }

        Reveal(2) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Typing keyboard")
                GlassCard(strong = false) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (state.typingKeyboard) "HeliBoard is installed" else "HeliBoard is not installed",
                                style = MaterialTheme.typography.titleMedium, color = g.text,
                            )
                            Text(
                                "Keep it for typing. Its mic key switches to Voice Fill.",
                                style = MaterialTheme.typography.bodyMedium, color = g.textMuted,
                            )
                        }
                        GlassButton("Switch", {
                            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                        })
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Reveal(3) {
            PrimaryButton(
                if (state.ready) (if (firstRun) "Continue" else "Done") else "${state.remaining} left",
                onDone, Modifier.fillMaxWidth(), enabled = state.ready || !firstRun,
            )
        }
    }
}

@Composable
private fun Step(title: String, detail: String, done: Boolean, action: String?, onAction: () -> Unit) {
    val g = Glass.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape)
                .background(if (done) g.good.copy(alpha = 0.9f) else g.textMuted.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = g.text)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = g.textMuted)
        }
        if (action != null) GlassButton(action, onAction)
    }
}

/** Fades and rises into place, a little later for each [index]. */
@Composable
fun Reveal(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(60L * index); shown = true }
    AnimatedVisibility(
        shown,
        enter = fadeIn(tween(420)) + slideInVertically(tween(480)) { it / 8 },
    ) { content() }
}
