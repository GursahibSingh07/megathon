package com.example.qidk_hack2026.job

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The one place that knows what the assistant is doing. The keyboard, the bubble and the app all
 * run in this process, so they share this object and watch the flows.
 */
object JobStore {
    private val _job = MutableStateFlow(Job())
    val job: StateFlow<Job> = _job.asStateFlow()

    private val _records = MutableStateFlow<List<Record>>(emptyList())
    val records: StateFlow<List<Record>> = _records.asStateFlow()

    private val _imeVisible = MutableStateFlow(false)
    /** True while the voice keyboard is on screen; the bubble only shows when it is not. */
    val imeVisible: StateFlow<Boolean> = _imeVisible.asStateFlow()

    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "records.json")
        file = f
        _records.value = runCatching { parse(f.readText()) }.getOrDefault(emptyList())
    }

    fun setImeVisible(visible: Boolean) { _imeVisible.value = visible }

    /** Starts a job, or continues the current one while it is still open. */
    fun begin(): Job {
        val cur = _job.value
        val next = if (cur.phase == Phase.IDLE || cur.phase == Phase.COMPLETE) {
            Job(id = System.currentTimeMillis(), phase = Phase.PROCESSING)
        } else cur.copy(phase = Phase.PROCESSING)
        _job.value = next
        return next
    }

    fun ready(transcript: String) {
        _job.value = _job.value.copy(phase = Phase.READY, transcript = transcript)
    }

    fun filling() {
        _job.value = _job.value.copy(phase = Phase.FILLING)
    }

    /** Back to where it was, when nothing came of the speech. */
    fun abort() {
        val cur = _job.value
        _job.value = if (cur.entries.isEmpty()) Job()
        else cur.copy(phase = if (cur.missing.isEmpty()) Phase.COMPLETE else Phase.FILLED)
    }

    fun filled(title: String, added: List<Entry>, missing: List<String>) {
        val cur = _job.value
        val merged = cur.entries.filterNot { e -> added.any { it.label == e.label } } + added
        val next = cur.copy(
            phase = if (missing.isEmpty() && merged.isNotEmpty()) Phase.COMPLETE else Phase.FILLED,
            title = title.ifBlank { cur.title },
            entries = merged,
            missing = missing,
            asking = missing.firstOrNull(),
        )
        _job.value = next
        save(next)
    }

    /** The user answered or skipped the question; [next] is the following empty field. */
    fun asked(next: String?) {
        _job.value = _job.value.copy(asking = next)
    }

    /** Closes the current job so the next dictation starts a new one. */
    fun finish() {
        _job.value = Job()
    }

    fun delete(id: Long) {
        _records.value = _records.value.filterNot { it.id == id }
        persist()
    }

    private fun save(job: Job) {
        if (job.entries.isEmpty()) return
        val rec = Record(job.id, job.id, job.title, job.transcript, job.entries, job.missing)
        _records.value = (listOf(rec) + _records.value.filterNot { it.id == job.id }).take(200)
        persist()
    }

    @Synchronized
    private fun persist() {
        val f = file ?: return
        runCatching { f.writeText(write(_records.value)) }
    }

    internal fun write(records: List<Record>): String = JSONArray().apply {
        records.forEach { r ->
            put(JSONObject().apply {
                put("id", r.id); put("time", r.time); put("title", r.title); put("transcript", r.transcript)
                put("entries", JSONArray().apply { r.entries.forEach { put(JSONObject().put("l", it.label).put("v", it.value)) } })
                put("missing", JSONArray(r.missing))
            })
        }
    }.toString()

    internal fun parse(text: String): List<Record> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val es = o.getJSONArray("entries")
            val ms = o.getJSONArray("missing")
            Record(
                o.getLong("id"), o.getLong("time"), o.getString("title"), o.getString("transcript"),
                (0 until es.length()).map { Entry(es.getJSONObject(it).getString("l"), es.getJSONObject(it).getString("v")) },
                (0 until ms.length()).map { ms.getString(it) },
            )
        }
    }
}
