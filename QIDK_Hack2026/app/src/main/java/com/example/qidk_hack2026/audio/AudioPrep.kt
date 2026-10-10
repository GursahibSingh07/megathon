package com.example.qidk_hack2026.audio

import kotlin.math.abs
import kotlin.math.min

/** Cleans a speech segment before it goes to Whisper. */
object AudioPrep {
    private const val TARGET_PEAK = 0.6f
    private const val MAX_GAIN = 8f
    private const val QUIET_PEAK = 0.3f // only boost clearly quiet audio

    /**
     * Removes DC offset and a little low-frequency rumble (one-pole high-pass, ~80 Hz), then
     * lifts quiet recordings toward a healthy level. Whisper is noticeably worse on very soft
     * input, and it never benefits from clipping, so loud audio is left alone.
     */
    fun prepare(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio
        val out = FloatArray(audio.size)
        val a = 0.9689f // exp(-2*pi*80/16000)
        var prevIn = 0f
        var prevOut = 0f
        var peak = 0f
        for (i in audio.indices) {
            val x = audio[i]
            val y = a * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            out[i] = y
            peak = maxOf(peak, abs(y))
        }
        if (peak in 1e-4f..QUIET_PEAK) {
            val gain = min(TARGET_PEAK / peak, MAX_GAIN)
            for (i in out.indices) out[i] *= gain
        }
        return out
    }
}
