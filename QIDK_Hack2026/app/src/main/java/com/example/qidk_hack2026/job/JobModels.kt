package com.example.qidk_hack2026.job

/** One value that went into the form, or one choice that was ticked. */
data class Entry(val label: String, val value: String)

enum class Phase {
    /** Nothing in progress. */
    IDLE,
    /** Speech is being transcribed and understood. */
    PROCESSING,
    /** Understood, waiting for the user to be back on the form. */
    READY,
    /** Typing into the form. */
    FILLING,
    /** The form has been filled; [Job.missing] lists what is still empty. */
    FILLED,
    /** Filled and nothing is left empty. */
    COMPLETE,
}

/** The form being worked on right now. */
data class Job(
    val id: Long = 0,
    val phase: Phase = Phase.IDLE,
    val title: String = "",
    val transcript: String = "",
    val entries: List<Entry> = emptyList(),
    val missing: List<String> = emptyList(),
    /** The empty field the assistant is currently asking about. */
    val asking: String? = null,
)

/** A finished (or abandoned) form, kept for the history. */
data class Record(
    val id: Long,
    val time: Long,
    val title: String,
    val transcript: String,
    val entries: List<Entry>,
    val missing: List<String>,
) {
    /** What the speaker button reads out. */
    fun spoken(): String = buildString {
        append(title.ifBlank { "Form" }).append(". ")
        if (entries.isEmpty()) append("Nothing was filled in. ")
        else {
            append("${entries.size} ${if (entries.size == 1) "field" else "fields"} filled. ")
            entries.forEach { append(it.label).append(": ").append(it.value).append(". ") }
        }
        if (missing.isNotEmpty()) append("Still empty: ").append(missing.joinToString(", ")).append(".")
    }
}
