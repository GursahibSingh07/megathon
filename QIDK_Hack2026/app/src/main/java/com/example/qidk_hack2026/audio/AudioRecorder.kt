package com.example.qidk_hack2026.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Records 16 kHz mono PCM16 (the format Whisper expects) and keeps it in memory, while an
 * [EndpointDetector] watches the stream for utterance boundaries. Caller must hold RECORD_AUDIO.
 */
class AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val READ_SAMPLES = SAMPLE_RATE / 10 // 100 ms per read
    }

    private val lock = Any()
    private var samples = ShortArray(SAMPLE_RATE * 30)
    private var size = 0

    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var detector: EndpointDetector? = null

    val isRecording: Boolean get() = record != null

    /** Start of the utterance being spoken right now (sample index), or -1. */
    val liveSegmentStart: Int get() = detector?.segmentStart ?: -1
    val heardSpeech: Boolean get() = detector?.everSpoke == true
    val peakDb: Float get() = detector?.peakDb ?: -120f
    val sampleCount: Int get() = synchronized(lock) { size }

    /** [listener] is called on the recorder thread; hand heavy work off quickly. */
    @SuppressLint("MissingPermission")
    fun start(listener: EndpointDetector.Listener? = null): Boolean {
        if (record != null) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = openRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, minBuf)
            ?: openRecord(MediaRecorder.AudioSource.MIC, minBuf)
            ?: return false

        synchronized(lock) { size = 0 }
        val det = EndpointDetector(listener ?: NoopListener)
        detector = det
        record = rec
        rec.startRecording()
        thread = Thread({
            val buf = ShortArray(maxOf(minBuf, READ_SAMPLES))
            while (record === rec) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    append(buf, n)
                    det.accept(buf, n)
                } else if (n < 0) break
            }
        }, "AudioRecorder").also { it.start() }
        return true
    }

    // VOICE_RECOGNITION skips the aggressive gain/noise processing the plain MIC path applies,
    // which is what recognizers want; fall back to MIC on devices that refuse it.
    @SuppressLint("MissingPermission")
    private fun openRecord(source: Int, minBuf: Int): AudioRecord? {
        val rec = runCatching {
            AudioRecord(
                source, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    private fun append(buf: ShortArray, n: Int) = synchronized(lock) {
        if (size + n > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + n))
        System.arraycopy(buf, 0, samples, size, n)
        size += n
    }

    /** Samples [start, end) as floats in [-1, 1], clamped to what has been captured. */
    fun slice(start: Int, end: Int = Int.MAX_VALUE): FloatArray = synchronized(lock) {
        val s = start.coerceIn(0, size)
        val e = end.coerceIn(s, size)
        FloatArray(e - s) { samples[s + it] / 32768f }
    }

    /** Stops recording; returns the total number of samples captured. */
    fun stop(): Int {
        val rec = record
        record = null
        thread?.join()
        thread = null
        rec?.let {
            runCatching { it.stop() }
            it.release()
        }
        val total = synchronized(lock) { size }
        detector?.finish(total)
        return total
    }

    private object NoopListener : EndpointDetector.Listener {
        override fun onSegment(startSample: Int, endSample: Int) = Unit
        override fun onLongSilence() = Unit
    }
}
