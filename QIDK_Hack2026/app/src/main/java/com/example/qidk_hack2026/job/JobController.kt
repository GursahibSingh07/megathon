package com.example.qidk_hack2026.job

import android.content.Context
import com.example.qidk_hack2026.form.FillResult
import com.example.qidk_hack2026.form.FormAccessibilityService
import com.example.qidk_hack2026.form.FormPipeline
import com.example.qidk_hack2026.form.SlmFormExtractor

/**
 * What happens between "the user said something" and "the form is filled and the gaps are asked about".
 * Shared by the keyboard (form on screen) and the bubble (the user came back to the form later).
 */
object JobController {
    private var skipped = mutableSetOf<String>()
    private var skippedFor = 0L

    /** Fills the form from [transcript]; if the assistant had asked about a field, a bare answer goes there. */
    suspend fun fill(context: Context, transcript: String): FillResult {
        val app = context.applicationContext
        JobStore.filling()
        val job = JobStore.job.value
        if (job.id != skippedFor) { skipped = mutableSetOf(); skippedFor = job.id }
        val asking = job.asking

        if (asking != null && isSkip(transcript)) {
            skipped += asking
            val next = nextQuestion(job.missing)
            JobStore.abort()
            JobStore.asked(next)
            announce(app, job.missing, next, intro = false)
            return FillResult(FillResult.Status.DONE, missing = job.missing)
        }

        var result = FormPipeline.run(transcript, SlmFormExtractor.get(app))
        if (result.status == FillResult.Status.NOTHING_MATCHED && asking != null) {
            result = FormPipeline.fillField(asking, transcript)
        }
        if (result.status != FillResult.Status.DONE) {
            JobStore.abort()
            return result
        }
        JobStore.filled(result.title, result.entries, result.missing)
        val next = nextQuestion(result.missing)
        JobStore.asked(next)
        announce(app, result.missing, next, intro = true)
        return result
    }

    /** The bubble was tapped: the user is back on the form, so fill it and bring the keyboard up. */
    suspend fun fillFromBubble(context: Context): FillResult? {
        val job = JobStore.job.value
        if (job.phase != Phase.READY) return null
        val result = fill(context, job.transcript)
        FormAccessibilityService.instance?.raiseKeyboard()
        return result
    }

    private fun nextQuestion(missing: List<String>) = missing.firstOrNull { it !in skipped }

    private fun announce(context: Context, missing: List<String>, next: String?, intro: Boolean) {
        val text = when {
            missing.isEmpty() -> "The form is complete."
            else -> buildString {
                if (intro) {
                    val shown = missing.take(4)
                    append(if (missing.size == 1) "One field is still empty: " else "${missing.size} fields are still empty: ")
                    append(shown.joinToString(", "))
                    if (missing.size > shown.size) append(" and ${missing.size - shown.size} more")
                    append(". ")
                }
                if (next != null) append("What is the $next?") else append("Those are all left to fill by hand.")
            }
        }
        Speaker.speak(context, text)
    }

    private fun isSkip(t: String): Boolean {
        val s = t.lowercase().trim { !it.isLetter() }
        return s in setOf("skip", "next", "pass", "skip it", "next one", "i don't know", "i do not know", "later")
    }
}
