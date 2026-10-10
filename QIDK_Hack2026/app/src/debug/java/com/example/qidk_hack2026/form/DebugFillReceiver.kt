package com.example.qidk_hack2026.form

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * adb shell am broadcast -n com.example.qidk_hack2026/.form.DebugFillReceiver --es text "name is Priya"
 */
class DebugFillReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = FormPipeline.run(text)
                Log.i("FormDebug", "RESULT text=\"$text\" -> $result")
            } catch (e: Exception) {
                Log.e("FormDebug", "pipeline threw", e)
            } finally {
                pending.finish()
            }
        }
    }
}
