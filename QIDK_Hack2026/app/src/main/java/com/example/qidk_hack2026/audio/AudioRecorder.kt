package com.example.qidk_hack2026.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Records 16 kHz mono PCM16 from the mic (the format Whisper expects) and keeps it in memory.
 * Caller must hold RECORD_AUDIO.
 */
class AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16_000
    }

    private val lock = Any()
    private var samples = ShortArray(SAMPLE_RATE * 30)
    private var size = 0

    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null

    val isRecording: Boolean get() = record != null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (record != null) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        synchronized(lock) { size = 0 }
        record = rec
        rec.startRecording()
        thread = Thread({
            val buf = ShortArray(minBuf)
            while (record === rec) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) append(buf, n) else if (n < 0) break
            }
        }, "AudioRecorder").also { it.start() }
        return true
    }

    private fun append(buf: ShortArray, n: Int) = synchronized(lock) {
        if (size + n > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + n))
        System.arraycopy(buf, 0, samples, size, n)
        size += n
    }

    /** Audio captured so far, as floats in [-1, 1]. Safe to call while recording. */
    fun snapshot(): FloatArray = synchronized(lock) {
        FloatArray(size) { samples[it] / 32768f }
    }

    /** Stops recording and returns everything captured. */
    fun stop(): FloatArray {
        val rec = record
        record = null
        thread?.join()
        thread = null
        rec?.let {
            runCatching { it.stop() }
            it.release()
        }
        return snapshot()
    }
}
