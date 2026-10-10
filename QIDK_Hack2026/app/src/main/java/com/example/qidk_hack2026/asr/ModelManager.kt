package com.example.qidk_hack2026.asr

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Locates and downloads the ggml Whisper model (too big to bundle in the APK). */
object ModelManager {
    const val MODEL_NAME = "ggml-small.en-q5_1.bin"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_NAME"
    private const val MIN_VALID_BYTES = 100L * 1024 * 1024 // real file is ~181 MB

    private fun internalFile(ctx: Context) = File(ctx.filesDir, "models/$MODEL_NAME")

    /** Alternative: `adb push` the model to /sdcard/Android/data/<pkg>/files/ */
    private fun sideloadedFile(ctx: Context) = ctx.getExternalFilesDir(null)?.let { File(it, MODEL_NAME) }

    fun modelFile(ctx: Context): File? =
        listOfNotNull(internalFile(ctx), sideloadedFile(ctx))
            .firstOrNull { it.isFile && it.length() >= MIN_VALID_BYTES }

    fun isReady(ctx: Context) = modelFile(ctx) != null

    /** Blocking; call from a background dispatcher. [onProgress] gets 0f..1f. */
    fun download(ctx: Context, onProgress: (Float) -> Unit) {
        val dest = internalFile(ctx)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "$MODEL_NAME.part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            check(part.length() >= MIN_VALID_BYTES) { "Download incomplete" }
            check(part.renameTo(dest)) { "Could not move model into place" }
        } finally {
            conn.disconnect()
            part.delete()
        }
    }
}
