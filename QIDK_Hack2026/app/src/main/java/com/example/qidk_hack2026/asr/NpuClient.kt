package com.example.qidk_hack2026.asr

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Talks to `whisper_npud`, the service that runs Whisper-Small on the Snapdragon NPU.
 *
 * Apps cannot use the NPU directly on QIDK builds (its DSP library is not public to apps), so the
 * model runs in a separate process and this client sends it audio over a loopback socket.
 * When the service is not running, [transcribe] returns null and the caller falls back to the CPU.
 */
object NpuClient {
    private const val TAG = "NpuClient"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5051
    private const val MAX_SAMPLES = 16_000 * 30            // the NPU model sees a fixed 30 s window
    private const val CONNECT_TIMEOUT_MS = 300
    private const val READ_TIMEOUT_MS = 30_000
    private const val RETRY_AFTER_MS = 10_000L             // don't hammer a service that is not there

    class Result(val text: String, val stats: String)

    private val lock = Mutex()
    private var socket: Socket? = null
    @Volatile private var downUntil = 0L

    /** Transcribes with the NPU service, or null if it is not reachable or failed. */
    suspend fun transcribe(audio: FloatArray): Result? = lock.withLock {
        if (audio.isEmpty()) return@withLock Result("", "")
        if (SystemClock.elapsedRealtime() < downUntil) return@withLock null
        withContext(Dispatchers.IO) {
            try {
                request(audio)
            } catch (e: IOException) {
                // The service may have restarted since the last call; try once on a fresh connection.
                close()
                try {
                    request(audio)
                } catch (e2: IOException) {
                    close()
                    downUntil = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
                    Log.i(TAG, "NPU service not reachable (${e2.message}); using the CPU")
                    null
                }
            }
        }
    }

    /** Forget a previous failure, e.g. after the service has been started. */
    fun retryNow() {
        downUntil = 0L
    }

    private fun request(audio: FloatArray): Result? {
        val s = socket ?: Socket().apply {
            connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
            tcpNoDelay = true
            soTimeout = READ_TIMEOUT_MS
        }.also { socket = it }

        val n = minOf(audio.size, MAX_SAMPLES)
        val out = ByteBuffer.allocate(4 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(n)
        out.asFloatBuffer().put(audio, 0, n)
        s.getOutputStream().apply { write(out.array()); flush() }

        val input = DataInputStream(s.getInputStream())
        fun readInt(): Int {
            val b = ByteArray(4)
            input.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
        }
        fun readText(): String {
            val len = readInt()
            if (len < 0 || len > 1_000_000) throw IOException("bad response length $len")
            val b = ByteArray(len)
            input.readFully(b)
            return String(b, Charsets.UTF_8)
        }

        val status = readInt()
        val text = readText()
        val stats = readText()
        if (status != 0) {
            Log.w(TAG, "NPU service reported an error: $stats")
            return null
        }
        Log.d(TAG, stats)
        return Result(text, stats)
    }

    private fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
