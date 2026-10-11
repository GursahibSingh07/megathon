package com.example.qidk_hack2026

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.qidk_hack2026.job.JobStore
import com.example.qidk_hack2026.ui.AppShell
import com.example.qidk_hack2026.ui.SetupScreen
import com.example.qidk_hack2026.ui.SetupState
import com.example.qidk_hack2026.ui.components.GlassBackground
import com.example.qidk_hack2026.ui.readSetup
import com.example.qidk_hack2026.ui.theme.QIDK_Hack2026Theme
import com.example.qidk_hack2026.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        JobStore.init(this)
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        setContent {
            var mode by remember { mutableStateOf(ThemeMode.load(this)) }
            var setupDone by remember { mutableStateOf(prefs.getBoolean("setup_done", false)) }
            var showSetup by remember { mutableStateOf(false) }
            val setup by produceState(SetupState(), resumes) { value = readSetup(this@MainActivity) }
            val job by JobStore.job.collectAsState()
            val records by JobStore.records.collectAsState()

            QIDK_Hack2026Theme(mode) {
                AnimatedContent(
                    !setupDone || showSetup,
                    transitionSpec = { fadeIn(tween(380)) togetherWith fadeOut(tween(220)) },
                    label = "root",
                ) { inSetup ->
                    if (inSetup) {
                        GlassBackground {
                            SetupScreen(setup, firstRun = !setupDone, onRefresh = { resumes++ }) {
                                prefs.edit().putBoolean("setup_done", true).apply()
                                setupDone = true
                                showSetup = false
                            }
                        }
                    } else {
                        AppShell(
                            job, records, mode, setup.remaining,
                            onTheme = { mode = mode.next(); ThemeMode.save(this@MainActivity, mode) },
                            onSetup = { showSetup = true },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }
}
