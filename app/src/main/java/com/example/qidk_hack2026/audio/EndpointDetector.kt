package com.example.qidk_hack2026.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Streaming speech/pause detector for 16 kHz mono PCM16.
 *
 * Energy-based with an adaptive noise floor and hysteresis (several consecutive loud frames to
 * start speech, a longer run of quiet frames to end it). It turns a continuous recording into
 * utterance-sized segments so that Whisper only ever sees speech, each segment is decoded once,
 * and the work stays linear in recording length instead of re-decoding the whole buffer.
 *
 * Sample indices are positions in the recording; not thread-safe except for the @Volatile reads.
 */
class EndpointDetector(private val listener: Listener) {

    interface Listener {
        /** A finished utterance occupying samples [startSample, endSample). */
        fun onSegment(startSample: Int, endSample: Int)

        /** Speech was heard earlier and has now been followed by [AUTO_STOP_SILENCE_MS] of quiet. */
        fun onLongSilence()
    }

    /** Start of the utterance currently being spoken, or -1 when idle. */
    @Volatile var segmentStart = -1
        private set

    /** True once any speech has been detected. */
    @Volatile var everSpoke = false
        private set

    /** Loudest frame seen so far (dBFS); lets callers tell "silent mic" from "missed speech". */
    @Volatile var peakDb = SILENCE_DB
        private set

    private val frame = ShortArray(FRAME)
    private var frameFill = 0
    private var frameIndex = 0 // frames fully consumed

    private var noiseDb = INITIAL_NOISE_DB
    private var inSpeech = false
    private var onsetCount = 0
    private var onsetFrames = 0
    private var quietFrames = 0
    private var lastVoicedEnd = 0
    private var longSilenceFired = false

    fun accept(samples: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val take = min(FRAME - frameFill, count - i)
            System.arraycopy(samples, i, frame, frameFill, take)
            frameFill += take
            i += take
            if (frameFill == FRAME) {
                processFrame()
                frameFill = 0
            }
        }
    }

    /** Closes any open utterance at [totalSamples] (call when recording stops). */
    fun finish(totalSamples: Int) {
        if (inSpeech && segmentStart >= 0 && totalSamples - segmentStart >= MIN_SEGMENT) {
            listener.onSegment(segmentStart, totalSamples)
        }
        inSpeech = false
        segmentStart = -1
    }

    private fun processFrame() {
        val db = frameDb()
        val frameEnd = (frameIndex + 1) * FRAME
        if (db > peakDb) peakDb = db

        val threshold = max(noiseDb + SPEECH_MARGIN_DB, MIN_SPEECH_DB)
        val loud = db > threshold

        if (!inSpeech) {
            // Track the noise floor: drop fast when it gets quieter, creep up slowly otherwise.
            noiseDb += (db - noiseDb) * (if (db < noiseDb) 0.3f else 0.01f)
            noiseDb = noiseDb.coerceIn(MIN_NOISE_DB, MAX_NOISE_DB)

            if (loud) {
                onsetCount++
                if (onsetCount == 1) onsetFrames = frameEnd - FRAME
                if (onsetCount >= ONSET_FRAMES) {
                    inSpeech = true
                    everSpoke = true
                    longSilenceFired = false
                    quietFrames = 0
                    lastVoicedEnd = frameEnd
                    segmentStart = max(0, onsetFrames - PRE_ROLL)
                }
            } else {
                onsetCount = 0
                if (everSpoke && !longSilenceFired &&
                    frameEnd - lastVoicedEnd >= AUTO_STOP_SILENCE_MS * SAMPLES_PER_MS
                ) {
                    longSilenceFired = true
                    listener.onLongSilence()
                }
            }
        } else {
            if (loud) {
                quietFrames = 0
                lastVoicedEnd = frameEnd
            } else {
                quietFrames++
            }

            if (quietFrames >= PAUSE_FRAMES) {
                emit(segmentStart, min(lastVoicedEnd + TRAIL, frameEnd))
                inSpeech = false
                segmentStart = -1
                onsetCount = 0
            } else if (frameEnd - segmentStart >= MAX_SEGMENT) {
                // Unbroken speech: cut here so latency and memory stay bounded.
                emit(segmentStart, frameEnd)
                segmentStart = frameEnd
            }
        }
        frameIndex++
    }

    private fun emit(start: Int, end: Int) {
        if (end - start >= MIN_SEGMENT) listener.onSegment(start, end)
    }

    private fun frameDb(): Float {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / FRAME) / 32768.0
        return if (rms < 1e-6) SILENCE_DB else (20 * log10(rms)).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = AudioRecorder.SAMPLE_RATE
        private const val SAMPLES_PER_MS = SAMPLE_RATE / 1000
        private const val FRAME = 20 * SAMPLES_PER_MS // 20 ms

        private const val SILENCE_DB = -120f
        private const val INITIAL_NOISE_DB = -55f
        private const val MIN_NOISE_DB = -75f
        private const val MAX_NOISE_DB = -35f
        private const val SPEECH_MARGIN_DB = 10f // speech must sit this far above the noise floor
        private const val MIN_SPEECH_DB = -48f   // ...and above this absolute level

        private const val ONSET_FRAMES = 4                      // 80 ms of speech to start
        private const val PAUSE_FRAMES = 700 / 20               // 700 ms of quiet ends an utterance
        private const val PRE_ROLL = 250 * SAMPLES_PER_MS       // keep soft word onsets
        private const val TRAIL = 200 * SAMPLES_PER_MS
        private const val MIN_SEGMENT = 300 * SAMPLES_PER_MS
        private const val MAX_SEGMENT = 20_000 * SAMPLES_PER_MS
        const val AUTO_STOP_SILENCE_MS = 2_000
    }
}
