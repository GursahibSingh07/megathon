package com.example.qidk_hack2026.form

/** Turns the fields a form pass left empty into a sentence for the TTS to read. */
object MissingFieldPrompt {
    private const val MAX_NAMED = 3

    fun ask(missing: List<String>, didntCatch: Boolean = false): String {
        val names = missing.map(::clean).filter { it.isNotEmpty() }.distinct()
        val lead = if (didntCatch) "Sorry, I didn't catch that. " else ""
        if (names.isEmpty()) return "${lead}Some fields are still empty. Please tell me the rest."
        val named = names.take(MAX_NAMED)
        val extra = names.size - named.size
        val list = when {
            extra > 0 -> named.joinToString(", ") + " and $extra more"
            named.size == 1 -> named[0]
            else -> named.dropLast(1).joinToString(", ") + " and " + named.last()
        }
        return "${lead}I still need $list. Please tell me."
    }

    /** Shown when nothing is left to ask. The form is never submitted by voice. */
    fun allDone() = "All fields are filled. Please check the form and submit it when you are ready."

    fun givingUp() = "I could not fill the rest. Tap the microphone when you want to continue."

    // Labels come from the screen: "Email *", "Phone number:", "field 3"
    private fun clean(label: String): String =
        label.replace(Regex("""\(.*?\)"""), " ")
            .replace(Regex("""[*:?]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeUnless { it.matches(Regex("""(?i)field \d+""")) } ?: ""
}
