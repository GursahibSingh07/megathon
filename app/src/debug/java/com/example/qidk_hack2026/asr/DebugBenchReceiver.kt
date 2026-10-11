package com.example.qidk_hack2026.asr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-only: times the current whisper.cpp (CPU) path on a 16 kHz mono 16-bit WAV, as a baseline
 * for the NPU work.
 *
 * adb shell am broadcast -n com.example.qidk_hack2026/.asr.DebugBenchReceiver --es wav /data/local/tmp/x.wav --ei runs 3
 */
class DebugBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("wav") ?: return
        val runs = intent.getIntExtra("runs", 3)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val audio = readWav16k(File(path))
                Log.i(TAG, "audio: ${audio.size} samples = ${"%.2f".format(audio.size / 16000.0)} s")
                val t0 = SystemClock.elapsedRealtime()
                WhisperEngine.load(context.applicationContext)
                Log.i(TAG, "model load: ${SystemClock.elapsedRealtime() - t0} ms")
                repeat(runs) { i ->
                    val s = SystemClock.elapsedRealtime()
                    val text = WhisperEngine.transcribe(context.applicationContext, audio, final = true)
                    Log.i(TAG, "run ${i + 1}: ${SystemClock.elapsedRealtime() - s} ms -> \"$text\"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "bench failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    // Plain 44-byte-header PCM16 WAV, which is what ffmpeg writes.
    private fun readWav16k(f: File): FloatArray {
        val bytes = f.readBytes()
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(pcm.remaining()) { pcm.get() / 32768f }
    }

    private companion object {
        const val TAG = "AsrBench"
    }
}
