package com.example.qidk_hack2026.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min

/**
 * Piper (VITS, en_US-lessac-low) text-to-speech, split in two ONNX graphs:
 *  - piper_enc: text encoder, duration predictor and alignment. Dynamic shapes, runs on the CPU.
 *  - piper_dec_t400: flow + HiFi-GAN decoder, fixed at [FRAMES] frames, runs on the Hexagon NPU
 *    through ONNX Runtime's QNN execution provider (HTP backend, fp16).
 * The decoder is created with CPU fallback disabled, so if it loads at all it is fully on the NPU.
 */
object PiperTts {
    private const val TAG = "PiperTts"

    private val lock = Mutex()
    private var env: OrtEnvironment? = null
    private var enc: OrtSession? = null
    private var dec: OrtSession? = null
    private var phonemizer: Phonemizer? = null
    private var voice: JSONObject? = null
    private var idMap: Map<String, Int> = emptyMap()

    @Volatile private var track: AudioTrack? = null
    @Volatile private var cancelled = false
    @Volatile private var written = 0

    /** Human-readable description of where the decoder runs, e.g. "NPU (Hexagon HTP, fp16)". */
    @Volatile var backend: String = "not loaded"
        private set

    /** True from the moment [speak] is called until playback ends; speech-to-text must not start meanwhile. */
    @Volatile var isSpeaking = false
        private set

    /** True when the voice assets are bundled; callers fall back to text-only otherwise. */
    fun isAvailable(context: Context): Boolean =
        runCatching { context.assets.list("tts")?.contains("piper_enc.onnx") == true }.getOrDefault(false)

    /** Loads the voice ahead of time so the first sentence isn't delayed. Never throws. */
    suspend fun preload(context: Context) {
        runCatching { load(context) }.onFailure { Log.e(TAG, "Could not load the Piper voice", it) }
    }

    suspend fun load(context: Context): String = lock.withLock {
        if (dec != null) return backend
        withContext(Dispatchers.IO) {
            val ctx = context.applicationContext
            val v = JSONObject(ctx.assets.open("tts/voice.json").bufferedReader().readText())
            voice = v
            idMap = v.getJSONObject("id_map").let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
            phonemizer = Phonemizer(ctx)

            val e = OrtEnvironment.getEnvironment().also { env = it }
            enc = e.createSession(copyAsset(ctx, "piper_enc.onnx").path, OrtSession.SessionOptions())
            dec = createDecoder(ctx, e)
        }
        backend
    }

    private fun createDecoder(ctx: Context, e: OrtEnvironment): OrtSession {
        val model = copyAsset(ctx, "piper_dec_t400.onnx").path
        val libDir = ctx.applicationInfo.nativeLibraryDir
        // The DSP-side skel libraries are found through this path.
        Os.setenv("ADSP_LIBRARY_PATH", libDir, true)
        return try {
            val opts = OrtSession.SessionOptions().apply {
                // Refuse to run if any node would land on the CPU: loading == fully on the NPU.
                addConfigEntry("session.disable_cpu_ep_fallback", "1")
                addQnn(mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "enable_htp_fp16_precision" to "1",
                ))
            }
            e.createSession(model, opts).also { backend = "NPU (Hexagon HTP, fp16)" }
        } catch (t: Throwable) {
            Log.w(TAG, "NPU decoder unavailable, falling back to CPU", t)
            backend = "CPU (NPU unavailable: ${t.message?.lineSequence()?.firstOrNull()})"
            e.createSession(model, OrtSession.SessionOptions())
        }
    }

    /** Synthesizes and plays [text]; returns when playback has finished or [stop] was called. */
    suspend fun speak(context: Context, text: String): Boolean {
        if (text.isBlank()) return true
        if (!isAvailable(context)) return false
        isSpeaking = true
        return try {
            speakInternal(context, text)
            true
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Speech failed", t)
            false
        } finally {
            isSpeaking = false
        }
    }

    private suspend fun speakInternal(context: Context, text: String) {
        load(context)
        cancelled = false
        written = 0
        withContext(Dispatchers.Default) {
            val sr = voice!!.getInt("sample_rate")
            val at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(sr * 2)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = at
            at.play()
            try {
                for (sentence in splitSentences(text)) {
                    if (cancelled || !isActive) break
                    synth(sentence, at)
                }
                // write() only queues audio; wait for the tail to play out so the mic doesn't hear it.
                while (!cancelled && isActive && at.playbackHeadPosition < written) delay(20)
                at.stop()
            } finally {
                track = null
                at.release()
            }
        }
    }

    fun stop() {
        cancelled = true
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    private fun splitSentences(text: String) =
        Regex("[^.!?;\\n]+[.!?;]?").findAll(text).map { it.value.trim() }.filter { it.any(Char::isLetterOrDigit) }.toList()

    /** Synthesizes one chunk; splits it in half (by words) if it doesn't fit the decoder's fixed length. */
    private fun synth(chunk: String, at: AudioTrack) {
        if (cancelled) return
        val ph = phonemizer!!.phonemize(chunk)
        if (ph.isBlank()) return
        val v = voice!!
        val frames = v.getInt("frames")
        val ids = ArrayList<Long>().apply {
            add(idMap["^"]!!.toLong())
            for (ch in ph) idMap[ch.toString()]?.let { add(it.toLong()); add(0L) }
            add(idMap["$"]!!.toLong())
        }
        val e = env!!
        val scales = floatArrayOf(
            v.getDouble("noise_scale").toFloat(), v.getDouble("length_scale").toFloat(),
            v.getDouble("noise_w").toFloat(),
        )
        val z: FloatArray; val mask: FloatArray; val t: Int
        OnnxTensor.createTensor(e, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong())).use { x ->
        OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)).use { len ->
        OnnxTensor.createTensor(e, FloatBuffer.wrap(scales), longArrayOf(3)).use { sc ->
            enc!!.run(mapOf("input" to x, "input_lengths" to len, "scales" to sc)).use { r ->
                val zt = r[0] as OnnxTensor
                t = zt.info.shape[2].toInt()
                if (t > frames) {
                    val words = chunk.split(' ')
                    if (words.size < 2) return // can't split further
                    synth(words.take(words.size / 2).joinToString(" "), at)
                    synth(words.drop(words.size / 2).joinToString(" "), at)
                    return
                }
                z = FloatArray(192 * frames).also { pad ->
                    val src = zt.floatBuffer
                    for (c in 0 until 192) for (i in 0 until t) pad[c * frames + i] = src.get(c * t + i)
                }
                mask = FloatArray(frames) { if (it < t) 1f else 0f }
            }
        }}}
        val hop = v.getInt("hop")
        OnnxTensor.createTensor(e, FloatBuffer.wrap(z), longArrayOf(1, 192, frames.toLong())).use { zt ->
        OnnxTensor.createTensor(e, FloatBuffer.wrap(mask), longArrayOf(1, 1, frames.toLong())).use { mt ->
            val names = dec!!.inputNames.toList()
            dec!!.run(mapOf(names[0] to zt, names[1] to mt)).use { r ->
                val audio = (r[0] as OnnxTensor).floatBuffer
                val n = min(t * hop, audio.remaining())
                val pcm = ShortArray(n) { (audio.get(it).coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                at.write(pcm, 0, n)
                written += n
            }
        }}
    }

    private fun copyAsset(ctx: Context, name: String): File {
        val dest = File(ctx.filesDir, "tts/$name").also { it.parentFile?.mkdirs() }
        val size = ctx.assets.openFd("tts/$name").use { it.length }
        if (!dest.isFile || dest.length() != size) {
            val part = File(dest.path + ".part")
            ctx.assets.open("tts/$name").use { i -> part.outputStream().use { o -> i.copyTo(o) } }
            part.renameTo(dest)
        }
        return dest
    }
}
